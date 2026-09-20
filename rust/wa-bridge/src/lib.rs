uniffi::setup_scaffolding!();

use std::sync::{mpsc, Arc, Mutex, OnceLock};

use whatsapp_rust::prelude::{Bot, BotHandle, Client, SqliteStore};
use whatsapp_rust::types::call::{CallAction, ElsewhereOutcome, IncomingCall};
use whatsapp_rust::types::events::Event;
use whatsapp_rust::voip::CallHandle;
use whatsapp_rust::{async_channel as chan, Jid};

/// Event surface toward Kotlin. Task 3 + Task 5 surface; `Ringing` is the
/// one controller-authorized addition (F1): an outgoing call rings before
/// the peer answers, so `place_call` reports `Ringing` and `CallStarted`
/// waits for the peer's `<accept>`.
#[derive(uniffi::Enum)]
pub enum WaEvent {
    NeedPairing,
    PairingQr { payload: String },
    /// `fresh` distinguishes a real pairing (`PairSuccess`) from a mere
    /// transport connect of an existing session (`Connected`) — only the
    /// former resets the surface's respawn cap.
    Paired { fresh: bool },
    /// The server revoked this device's link (logged out from the primary).
    /// The stored registration is dead and reconnecting with it loops
    /// forever — the surface wipes the session store and restarts.
    Unlinked,
    /// The bot's run loop died (crash / unrecoverable disconnect) while the
    /// session was still valid: respawn without wiping the store.
    Disconnected,
    IncomingCall { peer: String, is_video: bool },
    /// An outgoing call is ringing — the offer is out, the peer has not
    /// answered yet. `peer` is the display string (`place_call` input).
    Ringing { peer: String },
    CallStarted { call_id: String },
    CallEnded { reason: String },
    MissedCall { peer: String },
}

#[derive(Debug, uniffi::Error)]
pub enum WaError {
    NotPaired,
    NoActiveCall,
    Io { msg: String },
    Protocol { msg: String },
}

impl std::fmt::Display for WaError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            WaError::NotPaired => write!(f, "not paired"),
            WaError::NoActiveCall => write!(f, "no active call"),
            WaError::Io { msg } => write!(f, "io: {msg}"),
            WaError::Protocol { msg } => write!(f, "protocol: {msg}"),
        }
    }
}

/// Logs panics to logcat (ERROR, tag `wa-bridge`). Uniffi surfaces a panicking
/// exported fn as an abort after the unwind trips a second panic
/// (`panic_in_cleanup`), losing the first panic's message — the hook keeps
/// payload + location. Payload/location only: never event content (JIDs).
#[cfg(target_os = "android")]
fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        use std::ffi::CString;
        let payload = info
            .payload()
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| info.payload().downcast_ref::<String>().map(String::as_str))
            .unwrap_or("unknown panic payload");
        let location = info
            .location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());
        let msg = format!("wa-bridge panic: {payload} at {location}");
        if let (Ok(tag), Ok(text)) =
            (CString::new("wa-bridge"), CString::new(msg.replace('\0', " ")))
        {
            unsafe {
                android_log_sys::__android_log_write(
                    android_log_sys::LogPriority::ERROR as android_log_sys::c_int,
                    tag.as_ptr(),
                    text.as_ptr(),
                );
            }
        }
    }));
}

/// Host (tests, desktop): panic hook still recovers the message, on stderr.
#[cfg(not(target_os = "android"))]
fn install_panic_hook() {
    std::panic::set_hook(Box::new(|info| {
        let payload = info
            .payload()
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| info.payload().downcast_ref::<String>().map(String::as_str))
            .unwrap_or("unknown panic payload");
        let location = info
            .location()
            .map(|l| format!("{}:{}:{}", l.file(), l.line(), l.column()))
            .unwrap_or_else(|| "unknown location".to_string());
        eprintln!("wa-bridge panic: {payload} at {location}");
    }));
}

#[uniffi::export]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[uniffi::export(callback_interface)]
pub trait WaListener: Send + Sync {
    fn on_event(&self, event: WaEvent);
}

#[derive(uniffi::Object)]
pub struct WaBridge {
    /// Bot events are pushed through this channel; the constructor's pump
    /// thread forwards them to the Kotlin listener. Cloned into the bot's
    /// event handlers (they run on the bot's tokio tasks).
    events: mpsc::Sender<WaEvent>,
    /// Hosts the bot's tasks for the process lifetime — `TokioRuntime::spawn`
    /// is `tokio::spawn`, so every bot task must land on a live runtime.
    runtime: OnceLock<tokio::runtime::Runtime>,
    /// The spawned bot; dropping a `BotHandle` aborts the run loop, so it is
    /// kept for the process lifetime (and for Task 5's call control).
    handle: Mutex<Option<BotHandle>>,
    /// Session store path from `start` — `restart_pairing` respawns the bot
    /// on the same store without Kotlin passing it again.
    db_path: Mutex<Option<std::path::PathBuf>>,
    /// Bumped on every respawn; each bot's watchdog exits when its
    /// generation is superseded (otherwise aborted bots' watchdogs fire
    /// spurious restarts 30 s later).
    generation: Arc<std::sync::atomic::AtomicU64>,
    /// Own JID as the UI renders it ("Paired as …"). Never logged.
    account: Arc<Mutex<Option<String>>>,
    /// Microphone push channel, bounded so a stalled engine can never block
    /// the Kotlin capture thread — `send_mic_pcm` drops the oldest frame
    /// instead of waiting.
    mic_tx: chan::Sender<Vec<i16>>,
    /// Receiver side of the mic channel: a clone rides along on every call as
    /// the engine's `AudioSource`; the bridge's own copy drains the stale
    /// frame in the drop-oldest path and flushes leftover frames when a new
    /// call attaches.
    mic_rx: chan::Receiver<Vec<i16>>,
    /// Playout pull channel (engine pushes, Kotlin pulls via
    /// `recv_playout_pcm`). The engine's jitter buffer paces it.
    playout_tx: chan::Sender<Vec<i16>>,
    playout_rx: chan::Receiver<Vec<i16>>,
    /// The live call, if any: stored by `place_call`/`accept_call`, taken by
    /// `terminate`, cleared by the `wait_ended` watcher, cloned for
    /// `set_muted`. Shared so the watcher task can reach it.
    call: Arc<Mutex<Option<CallHandle>>>,
    /// The call id whose end has already surfaced to Kotlin. The
    /// `wait_ended` watcher and the mapped Terminate/EndedElsewhere events
    /// both race to report the same end (a peer hangup reaches us as BOTH a
    /// bot event and a registry teardown); `claim_end` makes the emission
    /// single-shot per call id so the call log never double-writes.
    ended_emitted: Arc<Mutex<Option<String>>>,
    /// The newest unanswered 1:1 offer, kept for `accept_call`/`reject_call`.
    /// Shared with the bot's event handler, which fills and clears it.
    incoming: Arc<Mutex<Option<IncomingCall>>>,
    /// The outgoing call's ring state (F1): set by `place_call`, consumed
    /// one-shot by the peer's `<accept>` (which then emits `CallStarted`),
    /// cleared by a terminate so a timed-out ring can never be answered by a
    /// stale stanza.
    outgoing_ringing: OutgoingRinging,
}

impl WaBridge {
    fn emit(&self, event: WaEvent) {
        let _ = self.events.send(event);
    }

    /// The bot's client, once the device is paired. `NotPaired` before
    /// `start` or while the pairing flow is still open.
    fn client(&self) -> Result<Arc<Client>, WaError> {
        let bot = self.handle.lock().unwrap();
        let handle = bot.as_ref().ok_or(WaError::NotPaired)?;
        let client = handle.client();
        drop(bot);
        if client.pn().is_none() {
            return Err(WaError::NotPaired);
        }
        Ok(client)
    }

    /// A bare phone number becomes a `s.whatsapp.net` JID; a full JID passes
    /// through unchanged.
    fn parse_jid(s: &str) -> Result<Jid, WaError> {
        let raw = if s.contains('@') {
            s.to_string()
        } else {
            format!("{s}@s.whatsapp.net")
        };
        raw.parse()
            .map_err(|_| WaError::Protocol { msg: "invalid phone number or JID".to_string() })
    }

    /// Cloned shared state for a call-control body running on the tokio
    /// runtime (see [`CallCtx`]). Only callable after `start` — every caller
    /// hits `client()` first, which errors `NotPaired` before the runtime
    /// would be needed.
    fn call_ctx(&self) -> CallCtx {
        CallCtx {
            events: self.events.clone(),
            runtime: self
                .runtime
                .get()
                .expect("tokio runtime (initialized by start)")
                .handle()
                .clone(),
            mic_rx: self.mic_rx.clone(),
            playout_tx: self.playout_tx.clone(),
            call: self.call.clone(),
            ended_emitted: self.ended_emitted.clone(),
            incoming: self.incoming.clone(),
            outgoing_ringing: self.outgoing_ringing.clone(),
        }
    }
}

/// Shared bridge state, cloned onto the runtime for a call-control body. The
/// exported async fns are polled by uniffi on the *caller's* thread — a plain
/// thread with no tokio context — while the voip facade's internals
/// `tokio::spawn` (relay/media tasks), which panics outside a runtime. So the
/// bodies run inside `CallCtx::runtime.spawn`; awaiting the `JoinHandle`
/// back on the uniffi poll thread needs no runtime context.
struct CallCtx {
    events: mpsc::Sender<WaEvent>,
    runtime: tokio::runtime::Handle,
    mic_rx: chan::Receiver<Vec<i16>>,
    playout_tx: chan::Sender<Vec<i16>>,
    call: Arc<Mutex<Option<CallHandle>>>,
    ended_emitted: EndedEmitted,
    incoming: Arc<Mutex<Option<IncomingCall>>>,
    outgoing_ringing: OutgoingRinging,
}

/// Joins a spawned call-control body back on the uniffi poll thread. A
/// `JoinError` means the spawned task panicked (the bodies return `Err`,
/// never unwind by design) — surfaced as a protocol error so Kotlin gets a
/// rejection instead of a lost future.
async fn join<T>(spawned: tokio::task::JoinHandle<Result<T, WaError>>) -> Result<T, WaError> {
    spawned
        .await
        .map_err(|_| WaError::Protocol { msg: "bridge task panicked".to_string() })?
}

impl CallCtx {
    /// The builder tail shared by placing and answering: wire the bridge's
    /// audio channels into the call.
    fn audio_endpoints(&self) -> (chan::Receiver<Vec<i16>>, chan::Sender<Vec<i16>>) {
        (self.mic_rx.clone(), self.playout_tx.clone())
    }

    /// Registers a freshly started call and reports its end: the handle goes
    /// into `call`, and a runtime task awaiting `wait_ended()` clears `call`
    /// and emits `CallEnded` — unless the end already surfaced through the
    /// mapped Terminate/EndedElsewhere events (`claim_end` dedupes; exactly
    /// one `CallEnded` per call reaches Kotlin either way). Returns the call
    /// id. Does NOT announce the start: `place_call` reports `Ringing` and
    /// `accept_call` reports `CallStarted` itself.
    fn attach_call(&self, handle: CallHandle) -> String {
        let call_id = handle.call_id().to_string();
        // Stale frames from a previous call may still sit in the bounded(4)
        // mic channel (≈240 ms of audio) — flush them so this call never
        // drains them (the engine's receiver clone shares the buffer).
        while self.mic_rx.try_recv().is_ok() {}
        *self.call.lock().unwrap() = Some(handle.clone());
        let events = self.events.clone();
        let call_slot = self.call.clone();
        let ended = self.ended_emitted.clone();
        let watcher_call_id = call_id.clone();
        self.runtime.spawn(async move {
            handle.wait_ended().await;
            // The call is dead: mute/terminate must stop referring to it.
            // Only clear OUR entry — a new call may have attached already.
            {
                let mut slot = call_slot.lock().unwrap();
                if slot.as_ref().is_some_and(|h| h.call_id() == watcher_call_id) {
                    *slot = None;
                }
            }
            if claim_end(&ended, &watcher_call_id) {
                let _ = events.send(WaEvent::CallEnded {
                    reason: "ended".to_string(),
                });
            }
        });
        call_id
    }
}

#[uniffi::export]
impl WaBridge {
    #[uniffi::constructor]
    pub fn new(listener: Box<dyn WaListener>) -> Self {
        install_panic_hook();
        let (tx, rx) = mpsc::channel::<WaEvent>();
        std::thread::spawn(move || {
            while let Ok(event) = rx.recv() {
                listener.on_event(event);
            }
        });
        let (mic_tx, mic_rx) = chan::bounded(4);
        let (playout_tx, playout_rx) = chan::bounded(8);
        Self {
            events: tx,
            runtime: OnceLock::new(),
            handle: Mutex::new(None),
            db_path: Mutex::new(None),
            generation: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            account: Arc::new(Mutex::new(None)),
            mic_tx,
            mic_rx,
            playout_tx,
            playout_rx,
            call: Arc::new(Mutex::new(None)),
            ended_emitted: Arc::new(Mutex::new(None)),
            incoming: Arc::new(Mutex::new(None)),
            outgoing_ringing: Arc::new(Mutex::new(None)),
        }
    }

    /// Opens the SqliteStore session (restoring a previous pairing if one
    /// exists), builds and spawns the real whatsapp-rust bot. Returns once
    /// the bot is running; connection and pairing proceed on its tasks and
    /// surface as events (`NeedPairing` → `PairingQr` → `Paired`).
    pub fn start(&self, data_dir: String) -> Result<(), WaError> {
        std::fs::create_dir_all(&data_dir).map_err(|e| WaError::Io { msg: e.to_string() })?;
        let db_path = std::path::Path::new(&data_dir).join("whatsapp.db");
        *self.db_path.lock().unwrap() = Some(db_path.clone());
        self.spawn_bot(&db_path)
    }

    /// WA Web's UNPAIRED_IDLE model: the pairing QR refs expire after
    /// ~60 s + 5×20 s and whatsapp-rust then disconnects a QR-only flow,
    /// leaving the "click to reload" to the surface. Aborts the current bot
    /// and spawns a fresh one on the same session store — a new pairing flow
    /// with fresh QRs. Kotlin calls this when the user taps connect.
    pub fn restart_pairing(&self) -> Result<(), WaError> {
        let db_path = self
            .db_path
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::Io { msg: "not started".to_string() })?;
        // Dropping the handle aborts the old run loop; the pairing flow has
        // no state worth a graceful shutdown.
        let _ = self.handle.lock().unwrap().take();
        self.generation.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *self.account.lock().unwrap() = None;
        self.spawn_bot(&db_path)
    }

    /// After a server-side unlink the stored registration is revoked:
    /// reconnecting with it loops forever (connect → LoggedOut → ...). Drop
    /// the bot AND wipe the session store so the respawn registers as a
    /// brand-new device (fresh QRs, fresh device identity).
    pub fn reset_session(&self) -> Result<(), WaError> {
        let db_path = self
            .db_path
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::Io { msg: "not started".to_string() })?;
        let _ = self.handle.lock().unwrap().take();
        self.generation.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *self.account.lock().unwrap() = None;
        if let Some(dir) = db_path.parent() {
            let _ = std::fs::remove_dir_all(dir);
        }
        self.spawn_bot(&db_path)
    }
}

impl WaBridge {
    /// Builds and spawns the bot on `db_path`, publishes the handle and
    /// reports `Paired` (session restored) or `NeedPairing` (fresh device).
    /// Not part of the UniFFI surface (private helper; `&Path` can't lift).
    fn spawn_bot(&self, db_path: &std::path::Path) -> Result<(), WaError> {
        // `reset_session` wipes the store directory — recreate it.
        std::fs::create_dir_all(db_path.parent().ok_or(WaError::Io {
            msg: "no store dir".to_string(),
        })?)
        .map_err(|e| WaError::Io { msg: e.to_string() })?;
        let runtime = self.runtime.get_or_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
                .expect("tokio runtime")
        });

        let event_tx = self.events.clone();
        let qr_tx = self.events.clone();
        let account = self.account.clone();
        let incoming = self.incoming.clone();
        let ended_emitted = self.ended_emitted.clone();
        let outgoing_ringing = self.outgoing_ringing.clone();
        let handle: BotHandle = runtime.block_on(async move {
            let store = SqliteStore::new(&db_path.to_string_lossy())
                .await
                .map_err(|e| WaError::Io { msg: e.to_string() })?;
            let bot = Bot::builder()
                .with_backend(store)
                // The companion name WhatsApp shows in "Linked devices" comes
                // from these props — the default os ("rust") renders as
                // "Other device". Advertise the real device everywhere.
                .with_device_props(
                    whatsapp_rust::wacore::store::DevicePropsOverride::new()
                        .with_os("Light Phone 3")
                        // Device label (verified by relink tests): with the
                        // platform UNKNOWN both the name and platform fields
                        // read "Other device" — WhatsApp ignores the os
                        // string there. With CHROME the entry reads
                        // "Google Chrome (Light Phone 3)", which is the best
                        // achievable label; see WORKLOG outstanding item.
                        .with_platform_type(
                            whatsapp_rust::waproto::whatsapp::device_props::PlatformType::CHROME,
                        ),
                )
                .on_qr_code(move |code, _timeout| {
                    let tx = qr_tx.clone();
                    async move {
                        let _ = tx.send(WaEvent::PairingQr { payload: code });
                    }
                })
                .on_event(move |event: Arc<Event>, client: Arc<Client>| {
                    let tx = event_tx.clone();
                    let account = account.clone();
                    let incoming = incoming.clone();
                    let ended_emitted = ended_emitted.clone();
                    let outgoing_ringing = outgoing_ringing.clone();
                    async move {
                        // PairSuccess carries the freshly paired account; the
                        // restored-session case reads it off the client below
                        // (via `WaBridge::account`).
                        if let Event::PairSuccess(success) = &*event {
                            *account.lock().unwrap() = Some(success.id.to_string());
                            let _ = tx.send(WaEvent::Paired { fresh: true });
                            return;
                        }
                        // The primary revoked the link: the stored identity is
                        // dead — drop it so `account()` reports unpaired.
                        if matches!(event.as_ref(), Event::LoggedOut(_)) {
                            *account.lock().unwrap() = None;
                        }
                        // Keep the newest unanswered 1:1 offer for accept/reject;
                        // a peer cancel ends the ring. A missed-call notice or an
                        // answer on another of our devices does the same. A peer
                        // <accept> for OUR ringing outgoing call answers it: the
                        // one-shot claim emits `CallStarted` (F1). A terminate
                        // also clears the ringing mark so a timed-out ring can
                        // never be answered by a stale stanza.
                        if let Event::IncomingCall(call) = &*event {
                            match &call.action {
                                CallAction::Offer { group_jid: None, .. } => {
                                    *incoming.lock().unwrap() = Some(call.clone());
                                }
                                CallAction::Accept { call_id, .. } => {
                                    if claim_outgoing_answer(&outgoing_ringing, call_id) {
                                        let _ = tx.send(WaEvent::CallStarted {
                                            call_id: call_id.clone(),
                                        });
                                    }
                                }
                                CallAction::Terminate { call_id, call_creator, .. } => {
                                    *incoming.lock().unwrap() = None;
                                    clear_outgoing_ringing(
                                        &outgoing_ringing,
                                        call_id,
                                        Some(call_creator),
                                        client.lid().as_ref(),
                                    );
                                }
                                _ => {}
                            }
                        }
                        if matches!(
                            event.as_ref(),
                            Event::MissedCall(_) | Event::CallEndedElsewhere(_)
                        ) {
                            *incoming.lock().unwrap() = None;
                        }
                        // F2: resolve the peer display before emitting — the
                        // offer's server-provided `caller_pn` when present,
                        // else a best-effort LID→PN query with the raw JID as
                        // fallback (also for a missed call's log line).
                        let resolved = peer_display_for(&client, &event).await;
                        if let Some(mapped) = map_event(&event, resolved.as_deref()) {
                            // Exactly one CallEnded per call id: the
                            // wait_ended watcher races the mapped
                            // Terminate/EndedElsewhere events to report the
                            // same end — first claim wins, the loser is
                            // dropped (Kotlin cannot dedupe: WaEvent has no
                            // call id on CallEnded, and the call log would
                            // double-write).
                            if gate_end(&ended_emitted, &event, &mapped) {
                                let _ = tx.send(mapped);
                            }
                        }
                    }
                })
                .build()
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })?;
            Ok(bot.spawn())
        })?;

        let restored = handle.client().pn().map(|jid| {
            *self.account.lock().unwrap() = Some(jid.to_string());
        });
        let wd_client = handle.client();
        // Arm the watchdog only when this bot was expected to be paired (a
        // restored session): a revoked session never sets the client's
        // logged-in flag, so client state alone can't distinguish "dead
        // paired session" from "unpaired and idle on the QR screen" — the
        // unpaired case is legitimate and covered by the user-driven
        // `restart_pairing`.
        let wd_expect_paired = restored.is_some();
        *self.handle.lock().unwrap() = Some(handle);

        // Watchdog: if a bot that was expected to be paired never connects
        // (or loses the connection for good), the surface must restart it —
        // otherwise the app displays a session that no longer exists.
        let wd_events = self.events.clone();
        let generation = Arc::clone(&self.generation);
        let wd_generation = generation.load(std::sync::atomic::Ordering::Relaxed);
        runtime.spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(30)).await;
                if generation.load(std::sync::atomic::Ordering::Relaxed) != wd_generation {
                    return; // superseded by a respawn — stand down
                }
                if wd_expect_paired && !wd_client.is_connected() {
                    // Same surface contract as a server-side unlink: restart.
                    let _ = wd_events.send(WaEvent::Disconnected);
                    break;
                }
            }
        });

        // A stored device means the session was restored; a fresh device gets
        // the pairing flow (its QR arrives as a PairingQr event).
        self.emit(if restored.is_some() {
            WaEvent::Paired { fresh: false }
        } else {
            WaEvent::NeedPairing
        });
        Ok(())
    }
}

#[uniffi::export]
impl WaBridge {
    /// Own account JID once known — fresh pairing via `PairSuccess`, restored
    /// session via the device snapshot. `None` before pairing.
    pub fn account(&self) -> Option<String> {
        if let Some(account) = self.account.lock().unwrap().clone() {
            return Some(account);
        }
        let handle = self.handle.lock().unwrap().as_ref()?.client();
        handle.pn().map(|jid| jid.to_string())
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }

    /// Places a 1:1 call. `phone_or_jid` is a bare phone number (an E.164
    /// `+`-prefix is fine) or a full JID. Returns the call id. The call rings
    /// until the peer answers: this reports `Ringing` (F1) and `CallStarted`
    /// only arrives when the peer's `<accept>` lands on the bot's event bus —
    /// unless the accept lands during the offer send itself, in which case
    /// this reports `CallStarted` directly (the ring mark is raised as
    /// `Pending` before the send so that window can never drop an answer).
    pub async fn place_call(&self, phone_or_jid: String) -> Result<String, WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            // Glare guard: a second concurrent call would orphan the first
            // one's audio channels and its end reporting.
            if ctx.call.lock().unwrap().is_some() {
                return Err(WaError::Protocol { msg: "a call is already active".to_string() });
            }
            let jid = Self::parse_jid(&phone_or_jid)?;
            let (mic, playout) = ctx.audio_endpoints();
            // Ring mark BEFORE the offer send: the call id is generated
            // inside `start()`, so the mark goes up as `Pending` and an
            // accept landing in the send window is claimed (stashed) rather
            // than dropped — `settle_outgoing_ring` reports whichever state
            // the call is actually in once `start()` returns.
            *ctx.outgoing_ringing.lock().unwrap() = Some(OutgoingRing::Pending);
            let handle = match client
                .voip()
                .call(&jid)
                .audio(mic, playout)
                .start()
                .await
            {
                Ok(handle) => handle,
                Err(e) => {
                    // No call came up: a stale mark must never answer.
                    *ctx.outgoing_ringing.lock().unwrap() = None;
                    return Err(WaError::Protocol { msg: e.to_string() });
                }
            };
            let call_id = ctx.attach_call(handle);
            // Ringing until the peer's <accept> claims it (one-shot) or a
            // terminate (ring timeout) clears it. If the peer never answers,
            // the library tears the registry entry down on the timeout
            // terminate, which wakes `wait_ended` — the watcher reports
            // `CallEnded` and the slot is freed exactly as for inbound.
            let early_answer = settle_outgoing_ring(&ctx.outgoing_ringing, &call_id);
            let _ = ctx.events.send(if early_answer {
                // The accept landed during the offer send: the call is
                // connected — never report a stale "Ringing".
                WaEvent::CallStarted { call_id: call_id.clone() }
            } else {
                WaEvent::Ringing { peer: phone_or_jid }
            });
            Ok(call_id)
        }))
        .await
    }

    /// Answers the most recent incoming call with the bridge's audio channels.
    pub async fn accept_call(&self) -> Result<(), WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            let incoming = ctx
                .incoming
                .lock()
                .unwrap()
                .take()
                .ok_or(WaError::NoActiveCall)?;
            let (mic, playout) = ctx.audio_endpoints();
            let handle = client
                .voip()
                .accept(&incoming)
                .audio(mic, playout)
                .start()
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })?;
            // Answered locally: the connected phase begins immediately (no
            // ringing phase exists for an accepted call).
            let call_id = ctx.attach_call(handle);
            let _ = ctx.events.send(WaEvent::CallStarted { call_id });
            Ok(())
        }))
        .await
    }

    /// Declines the most recent incoming call. Fire-and-forget: the library
    /// sends the `<reject>` and expects no server response.
    pub async fn reject_call(&self) -> Result<(), WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            let incoming = ctx
                .incoming
                .lock()
                .unwrap()
                .take()
                .ok_or(WaError::NoActiveCall)?;
            client
                .voip()
                .reject(&incoming)
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })
        }))
        .await
    }

    /// Hangs up the active call: signals the peer with `<terminate>` and tears
    /// the local media plane down. The `wait_ended` watcher reports
    /// `CallEnded` once the teardown completes.
    pub async fn terminate(&self) -> Result<(), WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            let handle = ctx
                .call
                .lock()
                .unwrap()
                .take()
                .ok_or(WaError::NoActiveCall)?;
            client
                .voip()
                .terminate(handle.call_id(), &handle.peer_jid(), &handle.call_creator())
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })
        }))
        .await
    }

    /// Mutes or unmutes the local microphone. The engine keeps the stream fed
    /// with DTX comfort noise, so the peer never renegotiates the transport.
    /// (`CallHandle::set_muted` is a pure atomic store and the body holds no
    /// `.await`, so — unlike the other call-control fns — it needs no tokio
    /// context and can stay polled on the uniffi caller thread.)
    pub async fn set_muted(&self, muted: bool) -> Result<(), WaError> {
        let handle = self
            .call
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::NoActiveCall)?;
        handle.set_muted(muted);
        Ok(())
    }

    /// Pushes one mic frame (960 samples = 60 ms at 16 kHz) from the Kotlin
    /// capture thread. The channel is bounded(4); when full the OLDEST frame
    /// is dropped so capture never blocks. The bridge holds a receiver clone
    /// for the process lifetime, so the channel never closes: without a live
    /// call the frame is simply buffered (drop-oldest) and flushed by the
    /// next `attach_call`, never an error.
    pub fn send_mic_pcm(&self, mut samples: Vec<i16>) -> Result<(), WaError> {
        match self.mic_tx.try_send(samples) {
            Ok(()) => return Ok(()),
            Err(chan::TrySendError::Full(returned)) => samples = returned,
            Err(chan::TrySendError::Closed(_)) => return Err(WaError::NoActiveCall),
        }
        // Full: remove the oldest frame ourselves (the engine's recv simply
        // waits for the next one), which frees a slot for this frame.
        match self.mic_rx.try_recv() {
            Ok(_) => {}
            // Drained between our try_send and now — a slot is already free.
            Err(chan::TryRecvError::Empty) => {}
            // The engine is gone (no call, or it just ended).
            Err(chan::TryRecvError::Closed) => return Err(WaError::NoActiveCall),
        }
        self.mic_tx
            .send_blocking(samples)
            .map_err(|_| WaError::NoActiveCall)
    }

    /// Pulls one playout frame (960 samples = 60 ms at 16 kHz) from the Kotlin
    /// playout thread, polling with a 5 ms sleep. An empty Vec means the
    /// engine starved us within `timeout_ms` — play silence for that frame.
    pub fn recv_playout_pcm(&self, timeout_ms: u32) -> Vec<i16> {
        let deadline = std::time::Instant::now()
            + std::time::Duration::from_millis(u64::from(timeout_ms));
        loop {
            match self.playout_rx.try_recv() {
                Ok(frame) => return frame,
                Err(chan::TryRecvError::Empty) => {}
                Err(chan::TryRecvError::Closed) => return Vec::new(),
            }
            if std::time::Instant::now() >= deadline {
                return Vec::new();
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
    }
}

/// Single-shot end marker: the call id whose end has already surfaced to
/// Kotlin, shared between the `wait_ended` watcher and the bot-event pump.
type EndedEmitted = Arc<Mutex<Option<String>>>;

/// The outgoing call's ring state (F1). The library generates the call id
/// inside `voip().call(..).start()`, so between the glare guard and the
/// offer send the id is not yet known — the mark is `Pending` there and a
/// peer accept landing in that window is stashed (`Answered`) for
/// `place_call` to confirm once `start()` returns, instead of being
/// dropped (which would strand the UI on "Ringing…" for a connected call).
enum OutgoingRing {
    /// The offer send is in flight; the call id is not known yet.
    Pending,
    /// Offer sent; rings until the peer's `<accept>` claims it or a
    /// terminate (ring timeout / peer cancel) clears it.
    Ringing(String),
    /// A peer accept arrived while `Pending`; `place_call` checks the id.
    Answered(String),
}

type OutgoingRinging = Arc<Mutex<Option<OutgoingRing>>>;

/// Whether the peer's `<accept>` answers OUR still-ringing outgoing call.
/// Consumes the mark: the first accept wins and later duplicates (a
/// re-delivered stanza) must not re-emit `CallStarted`. Returns whether the
/// accept itself may emit — a `Ringing` match emits directly; an accept in
/// the `Pending` window is stashed as `Answered` and `place_call` reports
/// the start once it knows its own call id.
fn claim_outgoing_answer(ringing: &OutgoingRinging, call_id: &str) -> bool {
    let mut guard = ringing.lock().unwrap();
    match guard.as_ref() {
        Some(OutgoingRing::Ringing(id)) if id == call_id => {
            *guard = None;
            true
        }
        // The offer send has not returned, so our own call id is unknown;
        // with the glare guard there is no other call we could be ringing.
        Some(OutgoingRing::Pending) => {
            *guard = Some(OutgoingRing::Answered(call_id.to_string()));
            false
        }
        _ => false,
    }
}

/// Drops the ring mark for `call_id` (ring timeout / peer cancel): a
/// never-answered call must not be answered by a stale late accept. The
/// `Pending` mark (id still unknown) is dropped only when the terminate's
/// `call_creator` is our own LID — an instant reject of our offer can land
/// before `start()` returns, but an incoming call's terminate must not
/// clear a concurrent outgoing ring.
fn clear_outgoing_ringing(
    ringing: &OutgoingRinging,
    call_id: &str,
    terminate_creator: Option<&Jid>,
    own_lid: Option<&Jid>,
) {
    let mut guard = ringing.lock().unwrap();
    match guard.as_ref() {
        Some(OutgoingRing::Ringing(id)) if id == call_id => *guard = None,
        Some(OutgoingRing::Pending)
            if terminate_creator.is_some() && terminate_creator == own_lid =>
        {
            *guard = None
        }
        _ => {}
    }
}

/// Folds a stashed early answer into the final ring state once
/// `place_call` knows its own call id. Returns whether that id matches the
/// stash — the peer accepted during the offer send, the mark is consumed
/// and `place_call` reports `CallStarted` directly. A mismatching stash is
/// a foreign accept (a call created by another of our devices): it is
/// ignored and the ring is (re-)armed for the real answer.
fn settle_outgoing_ring(ringing: &OutgoingRinging, call_id: &str) -> bool {
    let mut guard = ringing.lock().unwrap();
    let answered = matches!(&*guard, Some(OutgoingRing::Answered(id)) if id == call_id);
    *guard = if answered {
        None
    } else {
        Some(OutgoingRing::Ringing(call_id.to_string()))
    };
    answered
}

/// Claims the right to report `call_id`'s end. Exactly one of the racing
/// reporters (watcher vs mapped Terminate/EndedElsewhere event) wins; the
/// flag is never cleared — WhatsApp call ids are unique per call (`00` + 15
/// random bytes), so a stale entry can only suppress duplicates of an end
/// that already surfaced, and clearing it would reopen a late-watcher
/// double-emit window.
fn claim_end(ended: &EndedEmitted, call_id: &str) -> bool {
    let mut guard = ended.lock().unwrap();
    if guard.as_deref() == Some(call_id) {
        return false;
    }
    *guard = Some(call_id.to_string());
    true
}

/// The call id whose end a bot event reports, if any. (`MissedCall` is
/// deliberately absent: the library already dedupes it via its one-shot
/// ringing registry, and `WaEvent::MissedCall` isn't a `CallEnded`.)
fn end_call_id(event: &Event) -> Option<&str> {
    match event {
        Event::IncomingCall(call) if matches!(call.action, CallAction::Terminate { .. }) => {
            Some(call.action.call_id())
        }
        Event::CallEndedElsewhere(ended) => Some(&ended.call_id),
        _ => None,
    }
}

/// Whether `mapped` (from `map_event(source)`) may be emitted. `CallEnded`
/// emissions are single-shot per call id.
fn gate_end(ended: &EndedEmitted, source: &Event, mapped: &WaEvent) -> bool {
    match mapped {
        WaEvent::CallEnded { .. } => match end_call_id(source) {
            Some(call_id) => claim_end(ended, call_id),
            None => true,
        },
        _ => true,
    }
}

/// The peer display string a bot event should carry (F2): the offer's
/// server-provided `caller_pn` when present (no query needed), else a
/// best-effort LID→PN resolution for a LID peer. `None` keeps the raw JID.
/// Only the kinds whose mapped event carries a peer (1:1 offers, missed
/// calls) resolve: every other kind would pay a ≤5 s network await for a
/// discarded value — and the await lets a cancel-Terminate overtake the
/// offer's own `IncomingCall` event (stale incoming-call UI).
async fn peer_display_for(client: &Client, event: &Event) -> Option<String> {
    match event {
        Event::IncomingCall(call) if matches!(call.action, CallAction::Offer { group_jid: None, .. }) => {
            match offer_caller_pn_display(&call.action) {
                Some(pn) => Some(pn),
                None if call.from.is_lid() => Some(resolve_lid_display(client, &call.from).await),
                None => None,
            }
        }
        Event::MissedCall(missed) if missed.from.is_lid() => {
            Some(resolve_lid_display(client, &missed.from).await)
        }
        _ => None,
    }
}

/// The offer's `caller_pn` attribute as a display string, 1:1 offers only.
/// Group offers are out of scope (SPEC), so they resolve to nothing here.
fn offer_caller_pn_display(action: &CallAction) -> Option<String> {
    match action {
        CallAction::Offer {
            caller_pn: Some(pn),
            group_jid: None,
            ..
        } => Some(pn.to_string()),
        _ => None,
    }
}

/// Best-effort LID → phone-number resolution via a usync contacts query
/// (`contacts().is_on_whatsapp` on a LID returns the phone in `pn_jid`).
/// Network-bound, so bounded by a timeout; any failure — offline, unknown
/// LID, timeout — falls back to the raw JID. Never invents a number.
async fn resolve_lid_display(client: &Client, lid: &Jid) -> String {
    let raw = lid.to_string();
    let contacts = client.contacts();
    let query = contacts.is_on_whatsapp(std::slice::from_ref(lid));
    match tokio::time::timeout(std::time::Duration::from_secs(5), query).await {
        Ok(Ok(results)) => results
            .first()
            .and_then(|result| result.pn_jid.as_ref())
            .map(|pn| pn.to_string())
            .unwrap_or(raw),
        Ok(Err(_)) | Err(_) => raw,
    }
}

/// Bot → `WaEvent` mapping (first pass; Task 5 extends with call control).
/// Peer JIDs travel to the UI only — never logged. `resolved_peer`, when
/// present, replaces the raw JID on the peer-carrying events (F2).
fn map_event(event: &Event, resolved_peer: Option<&str>) -> Option<WaEvent> {
    match event {
        Event::PairingQrCode(qr) => Some(WaEvent::PairingQr {
            payload: qr.code.clone(),
        }),
        Event::Connected(_) => Some(WaEvent::Paired { fresh: false }),
        // The session is unusable: back to the pairing flow.
        Event::PairError(_) | Event::PairingQrCodesExhausted(_) => Some(WaEvent::NeedPairing),
        // Server-side unlink: the registration is revoked. A distinct event
        // (not NeedPairing) so the surface can restart the flow exactly once
        // — a respawn's own fresh-device `NeedPairing` must not loop it.
        Event::LoggedOut(_) => Some(WaEvent::Unlinked),
        Event::IncomingCall(incoming) => match &incoming.action {
            // 1:1 offers only (SPEC: group calls out of scope).
            CallAction::Offer {
                is_video,
                group_jid: None,
                ..
            } => Some(WaEvent::IncomingCall {
                peer: resolved_peer
                    .map(str::to_string)
                    .unwrap_or_else(|| incoming.from.to_string()),
                is_video: *is_video,
            }),
            // The peer ended the call; `terminate` also arrives for a call we
            // never answered (the peer gave up — a missed call, but the
            // dedicated MissedCall event covers the offline-replay case).
            CallAction::Terminate { reason, .. } => Some(WaEvent::CallEnded {
                reason: reason
                    .clone()
                    .unwrap_or_else(|| "ended".to_string()),
            }),
            _ => None,
        },
        Event::MissedCall(missed) => Some(WaEvent::MissedCall {
            peer: resolved_peer
                .map(str::to_string)
                .unwrap_or_else(|| missed.from.to_string()),
        }),
        // Ringing resolved on another of our devices.
        Event::CallEndedElsewhere(ended) => Some(WaEvent::CallEnded {
            reason: match ended.outcome {
                ElsewhereOutcome::Accepted => "accepted_elsewhere".to_string(),
                ElsewhereOutcome::Rejected => "rejected_elsewhere".to_string(),
                _ => "ended_elsewhere".to_string(),
            },
        }),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use whatsapp_rust::types::call::{CallAction, CallEndedElsewhere, MissedCall, MissedReason};

    struct Sink(Arc<Mutex<Vec<WaEvent>>>);

    impl WaListener for Sink {
        fn on_event(&self, event: WaEvent) {
            self.0.lock().unwrap().push(event);
        }
    }

    #[test]
    fn version_is_set() {
        assert!(!version().is_empty());
    }

    #[test]
    fn constructor_is_quiet() {
        // The Task 3 smoke signal is gone: nothing is emitted before the real
        // bot runs (`start`).
        let got: Arc<Mutex<Vec<WaEvent>>> = Arc::default();
        let _bridge = WaBridge::new(Box::new(Sink(got.clone())));
        std::thread::sleep(std::time::Duration::from_millis(100));
        assert!(got.lock().unwrap().is_empty());
        assert!(_bridge.account().is_none());
    }

    #[test]
    fn mic_channel_drops_oldest_when_full() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        for first in 0..6i16 {
            bridge.send_mic_pcm(vec![first]).expect("send never blocks");
        }
        let mut got = Vec::new();
        while let Ok(frame) = bridge.mic_rx.try_recv() {
            got.push(frame[0]);
        }
        // bounded(4): frames 0 and 1 were dropped, the newest four survive.
        assert_eq!(got, vec![2, 3, 4, 5]);
    }

    #[test]
    fn playout_poll_returns_frame_then_times_out_to_empty() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        bridge
            .playout_tx
            .send_blocking(vec![7i16; 960])
            .expect("bounded(8) is empty");
        assert_eq!(bridge.recv_playout_pcm(40), vec![7i16; 960]);

        let started = std::time::Instant::now();
        let frame = bridge.recv_playout_pcm(40);
        assert!(frame.is_empty());
        assert!(started.elapsed() >= std::time::Duration::from_millis(40));
    }

    #[tokio::test]
    async fn place_call_unpaired_is_not_paired() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        assert!(matches!(
            bridge.place_call("+15551234567".to_string()).await,
            Err(WaError::NotPaired)
        ));
    }

    /// Regression test for the live-call SIGABRT (round 2): uniffi polls
    /// exported async fns on the CALLER's thread — a plain thread with no
    /// tokio context — while the voip facade's internals `tokio::spawn`. The
    /// fix routes every call-control body through `runtime.spawn` + `join`.
    /// This drives the exact shape: a body that `tokio::spawn`s and awaits,
    /// spawned on a runtime handle and polled via `block_on` from a plain
    /// non-tokio thread. Before the fix this panicked with "must be called
    /// from the context of a Tokio 1.x runtime".
    #[test]
    fn spawned_bodies_get_a_tokio_context_from_plain_poll_threads() {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("runtime");
        let handle = runtime.handle().clone();
        // The shape of a call-control body: internals `tokio::spawn` + await.
        let body = async {
            tokio::spawn(async {
                tokio::time::sleep(std::time::Duration::from_millis(10)).await;
            })
            .await
            .expect("inner task");
            Ok(())
        };
        // Uniffi's poll thread: plain, non-tokio.
        let got = std::thread::spawn(move || {
            futures::executor::block_on(join(handle.spawn(body)))
        })
        .join()
        .expect("poll thread must not panic");
        got.expect("body result");
    }

    /// The double-end path: a peer-driven end reaches the bridge as BOTH a
    /// mapped bot event (Terminate / EndedElsewhere, from `map_event`) AND
    /// the `wait_ended` watcher (handlers/call.rs dispatches the stanza AND
    /// the voip registry teardown wakes `wait_ended`). Both race to
    /// `claim_end`; exactly one `CallEnded` per call id may pass `gate_end`.
    /// A live `CallHandle` can't exist in a unit test, so the race is driven
    /// through the same claim/gate functions the watcher and the event pump
    /// call.
    #[test]
    fn call_end_is_emitted_exactly_once_per_call_id() {
        let ts = whatsapp_rust::wacore::time::from_secs(1_766_847_151).expect("ts");
        let caller: Jid = "+15551234567@s.whatsapp.net".parse().expect("jid");
        let terminate = |call_id: &str| {
            Event::IncomingCall(IncomingCall::new_for_test(
                caller.clone(),
                "stanza-1".to_string(),
                ts,
                CallAction::Terminate {
                    call_id: call_id.to_string(),
                    call_creator: caller.clone(),
                    reason: None,
                    duration: Some(30),
                    audio_duration: Some(10),
                },
            ))
        };

        // Watcher wins: the mapped Terminate for the same call is suppressed.
        let ended: EndedEmitted = Arc::default();
        assert!(claim_end(&ended, "CALL-1"), "watcher claims first");
        let mapped = map_event(&terminate("CALL-1"), None).expect("Terminate maps to CallEnded");
        assert!(matches!(&mapped, WaEvent::CallEnded { .. }));
        assert!(!gate_end(&ended, &terminate("CALL-1"), &mapped));

        // Mapped event wins: a duplicate terminate stanza (the handler
        // re-dispatches every terminate) and the watcher's late claim are
        // both suppressed.
        let ended: EndedEmitted = Arc::default();
        let mapped = map_event(&terminate("CALL-1"), None).expect("Terminate maps to CallEnded");
        assert!(gate_end(&ended, &terminate("CALL-1"), &mapped));
        assert!(!claim_end(&ended, "CALL-1"), "watcher must not re-emit");
        let duplicate = map_event(&terminate("CALL-1"), None).expect("Terminate maps to CallEnded");
        assert!(!gate_end(&ended, &terminate("CALL-1"), &duplicate));

        // Unanswered call answered elsewhere: the EndedElsewhere mapping
        // claims the single CallEnded; the terminate stanza that carried it
        // must not add a second one. MissedCall semantics stay intact — a
        // plain missed call's MissedCall event is not gated, and its
        // terminate claims its own single CallEnded.
        let ended: EndedEmitted = Arc::default();
        let elsewhere = Event::CallEndedElsewhere(CallEndedElsewhere::new(
            caller.clone(),
            "CALL-2".to_string(),
            ts,
            ElsewhereOutcome::Accepted,
        ));
        let mapped = map_event(&elsewhere, None).expect("EndedElsewhere maps to CallEnded");
        assert!(matches!(
            &mapped,
            WaEvent::CallEnded { reason } if reason == "accepted_elsewhere"
        ));
        assert!(gate_end(&ended, &elsewhere, &mapped));
        let mapped = map_event(&terminate("CALL-2"), None).expect("Terminate maps to CallEnded");
        assert!(!gate_end(&ended, &terminate("CALL-2"), &mapped));

        let missed = Event::MissedCall(MissedCall::new(
            caller.clone(),
            "CALL-3".to_string(),
            ts,
            MissedReason::Remote,
        ));
        let mapped = map_event(&missed, None).expect("MissedCall maps");
        assert!(matches!(&mapped, WaEvent::MissedCall { .. }));
        assert!(gate_end(&ended, &missed, &mapped), "MissedCall is never gated");
        let mapped = map_event(&terminate("CALL-3"), None).expect("Terminate maps to CallEnded");
        assert!(gate_end(&ended, &terminate("CALL-3"), &mapped), "different call id claims its own end");
    }

    /// F1: the ringing→answered transition. The peer's `<accept>` claims the
    /// ringing mark exactly once — a duplicate accept stanza (re-delivery)
    /// and a stale accept after the ring timed out (terminate cleared the
    /// mark) must not re-emit `CallStarted`.
    #[test]
    fn outgoing_call_answers_exactly_once() {
        let ringing: OutgoingRinging = Arc::default();
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-1".to_string()));

        assert!(claim_outgoing_answer(&ringing, "CALL-1"), "the answer claims the ring");
        assert!(!claim_outgoing_answer(&ringing, "CALL-1"), "duplicate accept is dropped");
        assert!(!claim_outgoing_answer(&ringing, "CALL-2"), "a foreign call id never claims");

        // Ring timeout: the terminate clears the mark, a late accept is inert.
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-3".to_string()));
        clear_outgoing_ringing(&ringing, "CALL-3", None, None);
        assert!(!claim_outgoing_answer(&ringing, "CALL-3"), "no answer after the ring ended");

        // A terminate for a different call id leaves the ring untouched.
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-4".to_string()));
        clear_outgoing_ringing(&ringing, "CALL-9", None, None);
        assert!(claim_outgoing_answer(&ringing, "CALL-4"), "unrelated terminate must not clear");
    }

    /// F1: an accept landing in the offer-send window (the mark is still
    /// `Pending` — the call id is generated inside `start()`) is stashed,
    /// not dropped: `settle_outgoing_ring` confirms it against the id
    /// `place_call` learns and reports the start. A foreign early accept
    /// (a call created by another of our devices) mismatches and the ring
    /// is armed for the real answer.
    #[test]
    fn early_accept_during_offer_send_is_claimed() {
        let ringing: OutgoingRinging = Arc::default();
        *ringing.lock().unwrap() = Some(OutgoingRing::Pending);

        assert!(
            !claim_outgoing_answer(&ringing, "CALL-1"),
            "the pending accept is stashed, not emitted here",
        );
        assert!(
            settle_outgoing_ring(&ringing, "CALL-1"),
            "place_call confirms the stashed id and reports the start",
        );
        assert!(ringing.lock().unwrap().is_none(), "the answered mark is consumed");

        // A foreign early accept: the stash mismatches, the ring re-arms so
        // the real answer can still claim it.
        *ringing.lock().unwrap() = Some(OutgoingRing::Pending);
        let _ = claim_outgoing_answer(&ringing, "OTHER");
        assert!(
            !settle_outgoing_ring(&ringing, "CALL-2"),
            "a foreign stash must not report the start",
        );
        assert!(
            claim_outgoing_answer(&ringing, "CALL-2"),
            "the ring is armed for the real answer",
        );
    }

    /// A terminate for a call we did not create never clears the mark: an
    /// incoming call's cancel must not drop a concurrent outgoing ring.
    /// Our own terminate (instant reject of our offer) drops even a
    /// `Pending` mark, whose id cannot be matched yet.
    #[test]
    fn terminate_clears_only_own_pending_mark() {
        let ringing: OutgoingRinging = Arc::default();
        let own: Jid = "16994881892@lid".parse().expect("own lid");
        let peer: Jid = "13083225387035@lid".parse().expect("peer lid");

        *ringing.lock().unwrap() = Some(OutgoingRing::Pending);
        clear_outgoing_ringing(&ringing, "CALL-1", Some(&peer), Some(&own));
        assert!(
            matches!(*ringing.lock().unwrap(), Some(OutgoingRing::Pending)),
            "a foreign terminate leaves the pending mark",
        );
        clear_outgoing_ringing(&ringing, "CALL-1", Some(&own), Some(&own));
        assert!(ringing.lock().unwrap().is_none(), "our own terminate drops it");
    }

    /// F2: the offer's server-provided `caller_pn` is the display string for
    /// 1:1 offers (no query needed); group offers are out of scope, and the
    /// resolved string overrides the raw LID in the mapped events.
    #[test]
    fn peer_display_prefers_caller_pn_and_resolved_string() {
        let caller: Jid = "13083225387035@lid".parse().expect("lid jid");
        let pn: Jid = "15551234567@s.whatsapp.net".parse().expect("pn jid");
        let offer = |caller_pn: Option<Jid>, group_jid: Option<Jid>| {
            CallAction::Offer {
                call_id: "CALL-1".to_string(),
                call_creator: caller.clone(),
                caller_pn,
                caller_country_code: None,
                device_class: None,
                joinable: false,
                is_video: false,
                audio: Vec::new(),
                group_jid,
            }
        };

        assert_eq!(
            offer_caller_pn_display(&offer(Some(pn.clone()), None)).as_deref(),
            Some("15551234567@s.whatsapp.net"),
        );
        assert_eq!(offer_caller_pn_display(&offer(None, None)), None, "no attribute, no claim");
        assert_eq!(
            offer_caller_pn_display(&offer(Some(pn.clone()), Some(caller.clone()))),
            None,
            "group offers are out of scope",
        );

        let incoming = |action: CallAction| {
            let ts = whatsapp_rust::wacore::time::from_secs(1_766_847_151).expect("ts");
            Event::IncomingCall(IncomingCall::new_for_test(
                caller.clone(),
                "stanza-1".to_string(),
                ts,
                action,
            ))
        };
        // The resolved string replaces the raw LID; without one the raw
        // JID stays (never a fabricated number).
        let mapped = map_event(&incoming(offer(None, None)), Some("15551234567"))
            .expect("1:1 offer maps");
        assert!(matches!(&mapped, WaEvent::IncomingCall { peer, .. } if peer == "15551234567"));
        let mapped = map_event(&incoming(offer(None, None)), None).expect("1:1 offer maps");
        assert!(matches!(&mapped, WaEvent::IncomingCall { peer, .. } if peer == "13083225387035@lid"));
    }
}
