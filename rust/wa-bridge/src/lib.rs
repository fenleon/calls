uniffi::setup_scaffolding!();

use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use wacore::voip::rtcp::{RtcpFeedback, RTCP_PT_PSFB};
use whatsapp_rust::prelude::wa;
use whatsapp_rust::prelude::{Bot, BotHandle, Client, SqliteStore};
use whatsapp_rust::types::call::{CallAction, ElsewhereOutcome, IncomingCall};
use whatsapp_rust::types::events::Event;
use whatsapp_rust::voip::{
    CallEvent, CallHandle, VideoFrame, VideoSink, VideoSource, VideoUpgradeToken,
};
use whatsapp_rust::{async_channel as chan, Jid};

/// Event surface toward Kotlin. Task 3 + Task 5 surface; `Ringing` is the
/// one controller-authorized addition (F1): an outgoing call rings before
/// the peer answers, so `place_call` reports `Ringing` and `CallStarted`
/// waits for the peer's `<accept>`.
#[derive(uniffi::Enum)]
pub enum WaEvent {
    NeedPairing,
    PairingQr {
        payload: String,
    },
    /// `fresh` distinguishes a real pairing (`PairSuccess`) from a mere
    /// transport connect of an existing session (`Connected`) — only the
    /// former resets the surface's respawn cap.
    Paired {
        fresh: bool,
    },
    /// The server revoked this device's link (logged out from the primary).
    /// The stored registration is dead and reconnecting with it loops
    /// forever — the surface wipes the session store and restarts.
    Unlinked,
    /// The bot's run loop died (crash / unrecoverable disconnect) while the
    /// session was still valid: respawn without wiping the store.
    Disconnected,
    IncomingCall {
        peer: String,
        is_video: bool,
    },
    /// An outgoing call is ringing — the offer is out, the peer has not
    /// answered yet. `peer` is the display string (`place_call` input).
    Ringing {
        peer: String,
    },
    CallStarted {
        call_id: String,
    },
    CallEnded {
        reason: String,
    },
    MissedCall {
        peer: String,
    },
    /// Call-log backfill from the pairing history sync
    /// (`HistorySync.callLogRecords`) — the same history web.whatsapp shows.
    CallLogImport {
        entries: Vec<WaCallRecord>,
    },
    /// Names + numbers of people the account has 1:1 history with.
    /// `replace`: the pairing history-sync's authoritative peer set — swaps
    /// the whole list (purges stale entries). `renameOnly`: push-name
    /// updates — only rename contacts already in the list (pushnames alone
    /// never add a peer: they cover every group participant).
    ContactsImport {
        contacts: Vec<WaContact>,
        replace: bool,
        rename_only: bool,
    },
    /// The peer's `<video state=N>` signaling landed (V0): upgrade request /
    /// accept / stop, as the raw wire code (see the library's `VideoState`).
    /// `upgrade` carries the echo of the engine's upgrade token — Kotlin
    /// passes it verbatim to `acceptVideo`; the bridge resolves it against
    /// its own stash (see [`WaVideoUpgradeToken`]).
    VideoStateChanged {
        state: i32,
        orientation: Option<u8>,
        upgrade: Option<WaVideoUpgradeToken>,
    },
    /// Relay-send backpressure shed complete outbound video AUs before
    /// transmission (`CallEvent::OutboundMediaDropped`). `packets` is not
    /// surfaced: the bridge's frame cadence is what Kotlin can react to.
    OutboundVideoDropped {
        access_units: u32,
    },
    /// The peer demanded an outbound video keyframe (RTCP PLI/FIR on our video
    /// stream, from `CallEvent::RtcpReceived`). The engine sheds non-IDR AUs
    /// on its own but never signals the `VideoSource` — this lets the Kotlin
    /// encoder answer with `PARAMETER_KEY_REQUEST_SYNC_FRAME` instead of
    /// waiting out the GOP. Debounced to at most one event per 200 ms.
    KeyframePressure,
}

/// One imported call-log record; maps 1:1 onto the engine's `CallLogEntry`.
#[derive(Clone, uniffi::Record)]
pub struct WaCallRecord {
    pub peer: String,
    /// Start time, epoch millis.
    pub at: u64,
    pub outgoing: bool,
    pub missed: bool,
    /// Accepted on another linked device — no wire duration exists.
    pub answered_elsewhere: bool,
    /// Connected duration in seconds (0 for unanswered calls).
    pub duration: u64,
}

/// A peer JID and its push name (empty when unknown).
#[derive(uniffi::Record)]
pub struct WaContact {
    pub id: String,
    pub name: String,
}

/// Peer video keyframe demand inside one summarized RTCP compound packet: a
/// PLI (PSFB fmt 1) or FIR (PSFB fmt 4) naming a source the packet references
/// — `reports_video` already vouches the packet covers our outbound video.
/// Plain receiver/sender reports carry no payload feedback and never fire.
fn peer_video_keyframe_demand(
    reports_video: bool,
    feedback: &[RtcpFeedback],
    referenced_ssrcs: &[u32],
) -> bool {
    if !reports_video {
        return false;
    }
    feedback.iter().any(|item| {
        if item.packet_type != RTCP_PT_PSFB {
            return false;
        }
        let names_referenced = |ssrc: u32| ssrc != 0 && referenced_ssrcs.contains(&ssrc);
        match item.fmt {
            1 => names_referenced(item.media_ssrc),
            4 => {
                names_referenced(item.media_ssrc)
                    || item.fci.chunks_exact(8).any(|row| {
                        row.get(..4)
                            .and_then(|head| <[u8; 4]>::try_from(head).ok())
                            .is_some_and(|ssrc| names_referenced(u32::from_be_bytes(ssrc)))
                    })
            }
            _ => false,
        }
    })
}

/// One-event-per-window throttle for [`WaEvent::KeyframePressure`]: a PLI
/// storm must not spam the FFI.
#[derive(Default)]
struct KeyframePressureGate {
    last: Option<Instant>,
}

impl KeyframePressureGate {
    const WINDOW: Duration = Duration::from_millis(200);

    fn admit(&mut self, now: Instant) -> bool {
        if self.last.is_some_and(|last| now.duration_since(last) < Self::WINDOW) {
            return false;
        }
        self.last = Some(now);
        true
    }
}

/// One received peer video access unit (Annex-B, start codes included).
#[derive(uniffi::Record)]
pub struct WaVideoFrame {
    pub data: Vec<u8>,
    /// The AU carries an IDR/SPS/PPS NAL — safe point to (re)start a decoder.
    pub keyframe: bool,
    /// Peer device orientation in 90° steps (0..3).
    pub orientation: u8,
}

/// Echo of the engine's video-upgrade token. The library's `VideoUpgradeToken`
/// keeps `epoch` private (no getter/constructor at the fork's HEAD), so the
/// bridge cannot decompose/recompose it across the FFI boundary: the engine's
/// real token lives in the bridge's stash (set by `VideoStateChanged`, consumed
/// by `accept_video`, matched by `generation`), and this record's fields are the
/// opaque Kotlin-side echo — pass it back verbatim; `epoch` carries no
/// independent meaning (0). One upgrade request is live at a time, so the
/// generation match fully identifies the request.
#[derive(uniffi::Record)]
pub struct WaVideoUpgradeToken {
    pub generation: u64,
    pub epoch: u64,
}

/// One resolved identity: the raw LID a peer was first stored under and the
/// phone-number JID the session's persisted mapping table resolved it to.
#[derive(uniffi::Record)]
pub struct WaLidMapping {
    pub lid: String,
    pub pn: String,
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

/// Forwards the lib's `log` records to logcat (tag = record target): the
/// terminate investigation needs the stanza-level trace on-device. Call
/// signaling nodes carry JIDs — diagnostics builds only.
#[cfg(target_os = "android")]
fn install_android_logger() {
    use std::ffi::CString;
    struct Logcat;
    impl log::Log for Logcat {
        fn enabled(&self, meta: &log::Metadata) -> bool {
            meta.level() <= log::Level::Info
        }
        fn log(&self, record: &log::Record) {
            let prio = match record.level() {
                log::Level::Error => android_log_sys::LogPriority::ERROR,
                log::Level::Warn => android_log_sys::LogPriority::WARN,
                log::Level::Info => android_log_sys::LogPriority::INFO,
                _ => android_log_sys::LogPriority::DEBUG,
            };
            if let (Ok(tag), Ok(msg)) = (
                CString::new(record.target()),
                CString::new(format!("{}", record.args()).replace('\0', " ")),
            ) {
                unsafe {
                    android_log_sys::__android_log_write(
                        prio as android_log_sys::c_int,
                        tag.as_ptr(),
                        msg.as_ptr(),
                    );
                }
            }
        }
        fn flush(&self) {}
    }
    let _ = log::set_boxed_logger(Box::new(Logcat));
    log::set_max_level(log::LevelFilter::Info);
    log::info!("wa-bridge: android logger installed");
}

/// Host (tests, desktop): no logcat — leave the app's logger in control.
#[cfg(not(target_os = "android"))]
fn install_android_logger() {}

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
        if let (Ok(tag), Ok(text)) = (
            CString::new("wa-bridge"),
            CString::new(msg.replace('\0', " ")),
        ) {
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
    /// Set while a user-initiated `logout` is in flight: the deregistration
    /// triggers `LoggedOut`, which maps to `Unlinked` — but the surface drove
    /// the teardown itself (wipe + respawn), so that event must be suppressed
    /// (the engine's `Unlinked` recovery would double-respawn and burn an
    /// auto-restart).
    unlinking: Arc<std::sync::atomic::AtomicBool>,
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
    /// Outgoing encoded-video push channel (Kotlin camera/encoder → engine),
    /// bounded so a stalled engine can never block the Kotlin capture thread
    /// — `send_video_au` drops the OLDEST access unit instead of waiting.
    video_tx: chan::Sender<Vec<u8>>,
    /// Receiver side of the video-AU channel: a clone rides along on every
    /// call as the engine's `VideoSource`; the bridge's own copy drains the
    /// stale AU in the drop-oldest path and flushes leftover AUs when a new
    /// call attaches.
    video_rx: chan::Receiver<Vec<u8>>,
    /// Incoming-video pull channel (engine pushes reassembled peer AUs,
    /// Kotlin pulls via `recv_video_frame`). The engine drops a frame if the
    /// sink can't keep up; bounded(8) absorbs a decode hiccup.
    vframe_tx: chan::Sender<VideoFrame>,
    vframe_rx: chan::Receiver<VideoFrame>,
    /// The newest peer video-upgrade token from `CallEvent::VideoStateChanged`
    /// (the engine's `epoch` is private, so the real token is stashed here and
    /// `accept_video` resolves Kotlin's echo against it — see
    /// [`WaVideoUpgradeToken`]).
    pending_upgrade: PendingUpgrade,
    /// The live call, if any: stored by `place_call`/`accept_call`, taken by
    /// `terminate`, cleared by the `wait_ended` watcher, cloned for
    /// `set_muted`. Shared so the watcher task can reach it.
    call: Arc<Mutex<Option<CallHandle>>>,
    /// This device's orientation (90° steps 0..3) carried in outbound
    /// `<video>` stanzas. Stored bridge-side so a value set before a call
    /// attaches is in force for the call's first state-11 stanza
    /// (`attach_call` applies it to the new handle); `set_video_orientation`
    /// also applies it to a live handle immediately.
    video_orientation: Arc<std::sync::atomic::AtomicU8>,
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
    /// The 1:1 conversation peers seen in history-sync blobs (JID → unused)
    /// — the authoritative contact set. The blobs' pushname list covers every
    /// group participant, so it only ever names these peers, never adds.
    history_peers: Arc<Mutex<std::collections::HashMap<String, String>>>,
    /// Push names from history-sync blobs, kept separately because they
    /// typically arrive in later chunks than the conversations they name.
    history_pushnames: Arc<Mutex<std::collections::HashMap<String, String>>>,
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
        raw.parse().map_err(|_| WaError::Protocol {
            msg: "invalid phone number or JID".to_string(),
        })
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
            video_rx: self.video_rx.clone(),
            vframe_tx: self.vframe_tx.clone(),
            vframe_rx: self.vframe_rx.clone(),
            pending_upgrade: self.pending_upgrade.clone(),
            call: self.call.clone(),
            video_orientation: self.video_orientation.clone(),
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
    video_rx: chan::Receiver<Vec<u8>>,
    vframe_tx: chan::Sender<VideoFrame>,
    vframe_rx: chan::Receiver<VideoFrame>,
    pending_upgrade: PendingUpgrade,
    call: Arc<Mutex<Option<CallHandle>>>,
    video_orientation: Arc<std::sync::atomic::AtomicU8>,
    ended_emitted: EndedEmitted,
    incoming: Arc<Mutex<Option<IncomingCall>>>,
    outgoing_ringing: OutgoingRinging,
}

/// Joins a spawned call-control body back on the uniffi poll thread. A
/// `JoinError` means the spawned task panicked (the bodies return `Err`,
/// never unwind by design) — surfaced as a protocol error so Kotlin gets a
/// rejection instead of a lost future.
async fn join<T>(spawned: tokio::task::JoinHandle<Result<T, WaError>>) -> Result<T, WaError> {
    spawned.await.map_err(|_| WaError::Protocol {
        msg: "bridge task panicked".to_string(),
    })?
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
        // drains them (the engine's receiver clone shares the buffer). Same
        // for the video channels: leftover outbound AUs (the engine's source
        // clone shares the buffer) and peer frames (Kotlin must not decode a
        // previous call's bytes into this call).
        while self.mic_rx.try_recv().is_ok() {}
        while self.video_rx.try_recv().is_ok() {}
        while self.vframe_rx.try_recv().is_ok() {}
        // A pending upgrade belongs to the previous call's negotiation.
        *self.pending_upgrade.lock().unwrap() = None;
        // The orientation may have been set before the call attached (camera
        // opened during the consent flow) — put the stored value in force so
        // the call's first outbound `<video state=11>` stanza carries it.
        handle.set_device_orientation(
            self.video_orientation
                .load(std::sync::atomic::Ordering::Relaxed),
        );
        *self.call.lock().unwrap() = Some(handle.clone());
        let events = self.events.clone();
        let call_slot = self.call.clone();
        let ended = self.ended_emitted.clone();
        let watcher_call_id = call_id.clone();
        let relay_handle = handle.clone();
        self.runtime.spawn(async move {
            handle.wait_ended().await;
            // The call is dead: mute/terminate must stop referring to it.
            // Only clear OUR entry — a new call may have attached already.
            {
                let mut slot = call_slot.lock().unwrap();
                if slot
                    .as_ref()
                    .is_some_and(|h| h.call_id() == watcher_call_id)
                {
                    *slot = None;
                }
            }
            if claim_end(&ended, &watcher_call_id) {
                let _ = events.send(WaEvent::CallEnded {
                    reason: "ended".to_string(),
                });
            }
        });
        // Video/media diagnostics relay: `CallHandle::events()` is a single
        // competitive queue the bridge never consumed before — this loop is
        // its sole consumer, and it ends when the call tears down (the
        // registry drops the senders and the channel closes).
        let relay_events = self.events.clone();
        let relay_upgrade = self.pending_upgrade.clone();
        self.runtime.spawn(async move {
            let call_events = relay_handle.events();
            // Single sequential consumer: the debounce gate needs no lock.
            let mut pressure_gate = KeyframePressureGate::default();
            while let Ok(event) = call_events.recv().await {
                match event {
                    CallEvent::VideoStateChanged {
                        state,
                        orientation,
                        upgrade_token,
                    } => {
                        // Stash the real token for `accept_video`; a tokenless
                        // transition means the negotiation moved on (resolved,
                        // cancelled, enabled/stopped) — no request is pending.
                        *relay_upgrade.lock().unwrap() = upgrade_token;
                        let _ = relay_events.send(WaEvent::VideoStateChanged {
                            state: state.code(),
                            orientation,
                            upgrade: upgrade_token.map(|t| WaVideoUpgradeToken {
                                generation: t.generation(),
                                epoch: 0,
                            }),
                        });
                    }
                    CallEvent::OutboundMediaDropped {
                        video_access_units, ..
                    } => {
                        let _ = relay_events.send(WaEvent::OutboundVideoDropped {
                            access_units: video_access_units,
                        });
                    }
                    CallEvent::RtcpReceived {
                        reports_video,
                        referenced_ssrcs,
                        feedback,
                        ..
                    } if peer_video_keyframe_demand(
                        reports_video,
                        &feedback,
                        &referenced_ssrcs,
                    ) && pressure_gate.admit(Instant::now()) => {
                        let _ = relay_events.send(WaEvent::KeyframePressure);
                    }
                    _ => {}
                }
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
        install_android_logger();
        let (tx, rx) = mpsc::channel::<WaEvent>();
        std::thread::spawn(move || {
            while let Ok(event) = rx.recv() {
                listener.on_event(event);
            }
        });
        let (mic_tx, mic_rx) = chan::bounded(4);
        let (playout_tx, playout_rx) = chan::bounded(8);
        let (video_tx, video_rx) = chan::bounded(4);
        let (vframe_tx, vframe_rx) = chan::bounded(8);
        Self {
            events: tx,
            runtime: OnceLock::new(),
            handle: Mutex::new(None),
            db_path: Mutex::new(None),
            generation: Arc::new(std::sync::atomic::AtomicU64::new(0)),
            unlinking: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            account: Arc::new(Mutex::new(None)),
            mic_tx,
            mic_rx,
            playout_tx,
            playout_rx,
            video_tx,
            video_rx,
            vframe_tx,
            vframe_rx,
            pending_upgrade: Arc::new(Mutex::new(None)),
            call: Arc::new(Mutex::new(None)),
            video_orientation: Arc::new(std::sync::atomic::AtomicU8::new(0)),
            ended_emitted: Arc::new(Mutex::new(None)),
            incoming: Arc::new(Mutex::new(None)),
            outgoing_ringing: Arc::new(Mutex::new(None)),
            history_peers: Arc::new(Mutex::new(std::collections::HashMap::new())),
            history_pushnames: Arc::new(Mutex::new(std::collections::HashMap::new())),
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
        let db_path = self.db_path.lock().unwrap().clone().ok_or(WaError::Io {
            msg: "not started".to_string(),
        })?;
        // Dropping the handle aborts the old run loop; the pairing flow has
        // no state worth a graceful shutdown.
        let _ = self.handle.lock().unwrap().take();
        self.generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *self.account.lock().unwrap() = None;
        self.spawn_bot(&db_path)
    }

    /// After a server-side unlink the stored registration is revoked:
    /// reconnecting with it loops forever (connect → LoggedOut → ...). Drop
    /// the bot AND wipe the session store so the respawn registers as a
    /// brand-new device (fresh QRs, fresh device identity).
    pub fn reset_session(&self) -> Result<(), WaError> {
        let db_path = self.db_path.lock().unwrap().clone().ok_or(WaError::Io {
            msg: "not started".to_string(),
        })?;
        let _ = self.handle.lock().unwrap().take();
        self.generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *self.account.lock().unwrap() = None;
        if let Some(dir) = db_path.parent() {
            let _ = std::fs::remove_dir_all(dir);
        }
        self.spawn_bot(&db_path)
    }

    /// User-initiated unpair: deregister the device with the server
    /// (`Client::logout` sends the remove-companion IQ, so the entry
    /// disappears from the primary's "Linked devices" list) and then wipe
    /// the store + respawn exactly like `reset_session`. The IQ is
    /// best-effort — offline it degrades to the local wipe, same recovery
    /// behavior as an unpaired-timeout. The `LoggedOut` the deregistration
    /// fires is suppressed (`unlinking`): the caller parked the UI on
    /// `NeedPairing` and owns the teardown.
    pub fn logout(&self) -> Result<(), WaError> {
        let db_path = self.db_path.lock().unwrap().clone().ok_or(WaError::Io {
            msg: "not started".to_string(),
        })?;
        let handle = self.handle.lock().unwrap().take();
        self.generation
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        *self.account.lock().unwrap() = None;
        self.unlinking
            .store(true, std::sync::atomic::Ordering::Relaxed);
        // The IQ needs a live connection — keep the run loop alive while
        // `logout` runs; dropping the handle aborts it afterwards.
        if let Some(handle) = handle {
            let client = handle.client();
            let runtime = self.runtime.get_or_init(|| {
                tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .build()
                    .expect("tokio runtime")
            });
            runtime.block_on(client.logout());
            drop(handle);
        }
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
        let unlinking = self.unlinking.clone();
        let incoming = self.incoming.clone();
        let ended_emitted = self.ended_emitted.clone();
        let outgoing_ringing = self.outgoing_ringing.clone();
        let history_peers = self.history_peers.clone();
        let history_pushnames = self.history_pushnames.clone();
        let handle: BotHandle = runtime.block_on(async move {
            let store = SqliteStore::new(&db_path.to_string_lossy())
                .await
                .map_err(|e| WaError::Io { msg: e.to_string() })?;
            let bot = Bot::builder()
                .with_backend(store)
                // The companion name WhatsApp shows in "Linked devices" comes
                // from these props (they ride the registration payload only —
                // changing them needs an unlink + relink). With DESKTOP the
                // entry reads just the os string ("Light Phone 3"); browser
                // platform types (e.g. CHROME) prefix the os with the browser
                // name ("Google Chrome (Light Phone 3)"), and UNKNOWN renders
                // "Other device" and drops the os entirely.
                .with_device_props(
                    whatsapp_rust::wacore::store::DevicePropsOverride::new()
                        .with_os("Light Phone 3")
                        .with_platform_type(
                            whatsapp_rust::waproto::whatsapp::device_props::PlatformType::DESKTOP,
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
                    let unlinking = unlinking.clone();
                    let incoming = incoming.clone();
                    let ended_emitted = ended_emitted.clone();
                    let outgoing_ringing = outgoing_ringing.clone();
                    let history_peers = history_peers.clone();
                    let history_pushnames = history_pushnames.clone();
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
                        // dead — drop it so `account()` reports unpaired. A
                        // user-initiated logout fires the same event: there the
                        // surface drove the teardown itself, so `Unlinked` is
                        // suppressed (see the `unlinking` field).
                        if matches!(event.as_ref(), Event::LoggedOut(_)) {
                            *account.lock().unwrap() = None;
                            if unlinking.swap(false, std::sync::atomic::Ordering::Relaxed) {
                                return;
                            }
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
                                CallAction::Offer {
                                    group_jid: None, ..
                                } => {
                                    *incoming.lock().unwrap() = Some(call.clone());
                                }
                                CallAction::Accept { call_id, .. } => {
                                    if claim_outgoing_answer(&outgoing_ringing, call_id) {
                                        let _ = tx.send(WaEvent::CallStarted {
                                            call_id: call_id.clone(),
                                        });
                                    }
                                }
                                CallAction::Terminate {
                                    call_id,
                                    call_creator,
                                    ..
                                } => {
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
                        // Live call-log records + push-name updates (app-state
                        // patches) keep the log and contacts current after the
                        // pairing-time history-sync import.
                        if let Event::CallLogUpdate(u) = &*event {
                            let r = u.action.as_ref();
                            let own = own_jids(&client);
                            let raw = if one_to_one_record(r) {
                                record_peer_jid(r, &own)
                            } else {
                                None
                            };
                            if let Some(raw) = raw {
                                let peer = resolve_jid(&client, raw).await;
                                if let Some(record) = call_record_to_wa(r, peer) {
                                    let _ = tx.send(WaEvent::CallLogImport {
                                        entries: vec![record.clone()],
                                    });
                                    // The peer enters the contact list with the
                                    // call — no re-pair needed to grow it. Empty
                                    // name: a pushname update fills it later
                                    // (merge never lets "" overwrite a name).
                                    let _ = tx.send(WaEvent::ContactsImport {
                                        contacts: vec![WaContact {
                                            id: record.peer,
                                            name: String::new(),
                                        }],
                                        replace: false,
                                        rename_only: false,
                                    });
                                }
                            }
                            return;
                        }
                        if let Event::PushNameUpdate(u) = &*event {
                            let name = u.new_push_name.trim();
                            if !name.is_empty() {
                                let id = resolve_jid(&client, u.jid.to_string()).await;
                                let _ = tx.send(WaEvent::ContactsImport {
                                    contacts: vec![WaContact {
                                        id,
                                        name: name.to_string(),
                                    }],
                                    replace: false,
                                    rename_only: true,
                                });
                            }
                            return;
                        }
                        // A conversation started on any other device adds its
                        // peer on the first message — the history sync that
                        // seeds the contact list only runs at pairing, so
                        // this is the only path that grows the list live.
                        // 1:1 chats only; own outgoing messages add the
                        // recipient with an empty name (a later pushname
                        // fills it — merge never lets "" clobber a name).
                        if let Event::Messages(batch) = &*event {
                            for msg in batch {
                                let src = &msg.info.source;
                                if src.is_group {
                                    continue;
                                }
                                let (raw, name) = if src.is_from_me {
                                    let recipient = match &src.recipient {
                                        Some(r) => r.clone(),
                                        None => continue,
                                    };
                                    (recipient, String::new())
                                } else {
                                    (src.chat.clone(), msg.info.push_name.trim().to_string())
                                };
                                let jid = raw.to_string();
                                if jid.ends_with("@broadcast") || jid.starts_with("status@") {
                                    continue;
                                }
                                let id = resolve_jid(&client, jid).await;
                                let _ = tx.send(WaEvent::ContactsImport {
                                    contacts: vec![WaContact { id, name }],
                                    replace: false,
                                    rename_only: false,
                                });
                            }
                            return;
                        }
                        // Call-log + contacts backfill rides in on the pairing
                        // history sync. Parsed once (inflate + decode is
                        // heavy); nothing else in the blob is used.
                        if let Event::HistorySync(hs) = &*event {
                            // PUSH_NAME chunks (type 4) carry no call records,
                            // but they DO carry the pushnames the contacts
                            // import needs — decode them too (once, at pairing).
                            if let Some(parsed) = hs.get() {
                                let mut own = Vec::new();
                                if let Some(pn) = client.pn() {
                                    own.push(pn.to_string());
                                }
                                if let Some(lid) = client.lid() {
                                    own.push(lid.to_string());
                                }
                                import_history(
                                    &own,
                                    parsed,
                                    &tx,
                                    &history_peers,
                                    &history_pushnames,
                                );
                            }
                            return;
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
                                // Ring self-expiry: companion devices don't
                                // reliably receive the caller's terminate for an
                                // unanswered call (observed 2026-09-23: a
                                // primary-sent terminate reaches us, a companion
                                // caller's doesn't), so the ring expires past the
                                // native caller no-answer window instead of
                                // ringing forever. A terminate/accept/reject that
                                // landed first cleared the slot; the call-id
                                // guard drops a stale timer's claim on a newer
                                // ring.
                                if let (
                                    Event::IncomingCall(call),
                                    WaEvent::IncomingCall { peer, .. },
                                ) = (event.as_ref(), &mapped)
                                {
                                    if matches!(
                                        call.action,
                                        CallAction::Offer {
                                            group_jid: None,
                                            ..
                                        }
                                    ) {
                                        arm_ring_timeout(
                                            &tx,
                                            &incoming,
                                            call.action.call_id(),
                                            peer.clone(),
                                        );
                                    }
                                }
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

    /// Own WhatsApp profile name (push name) once known — the "Paired as"
    /// line. Empty before pairing (the caller falls back to the pn).
    pub fn account_name(&self) -> Option<String> {
        let name = self.client().ok()?.push_name();
        if name.is_empty() { None } else { Some(name) }
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }

    /// Resolves stored raw-LID JIDs against the session's persisted LID→phone
    /// mapping table (usync answers are learned there). Returns only the pairs
    /// that resolved — the engine rewrites its stores onto the phone JID, the
    /// identity that is stable across ingest paths (history-sync blob, live
    /// events), collapsing the duplicates the two paths produced.
    /// The linked account's own JID strings (PN + LID) — the caller purges
    /// self-rows from its stores (own calls leaked in as peers).
    pub fn own_jids(&self) -> Vec<String> {
        self.client().map(|c| own_jids(&c)).unwrap_or_default()
    }

    pub async fn resolve_lids(&self, lids: Vec<String>) -> Result<Vec<WaLidMapping>, WaError> {
        let client = self.client()?;
        let runtime = self
            .runtime
            .get()
            .ok_or(WaError::NotPaired)?
            .handle()
            .clone();
        // uniffi polls this body on the caller's plain thread, but the store's
        // lookups `spawn_blocking` — a runtime-less panic (seen on every
        // bridge start, silently killing the identity heal). Route through
        // our runtime: the same spawn + join pattern as the call-control
        // bodies.
        join(runtime.spawn(async move {
            let mut out = Vec::new();
            for lid in lids {
                let Ok(jid) = lid.parse::<Jid>() else {
                    continue;
                };
                if !jid.is_lid() {
                    continue;
                }
                if let Ok(Some(entry)) = client.get_lid_pn_entry(&jid).await {
                    out.push(WaLidMapping {
                        lid: jid.to_string(),
                        pn: format!("{}@s.whatsapp.net", entry.phone_number),
                    });
                }
            }
            Ok(out)
        }))
        .await
    }

    /// Places a 1:1 call. `phone_or_jid` is a bare phone number (an E.164
    /// `+`-prefix is fine) or a full JID. Returns the call id. The call rings
    /// until the peer answers: this reports `Ringing` (F1) and `CallStarted`
    /// only arrives when the peer's `<accept>` lands on the bot's event bus —
    /// unless the accept lands during the offer send itself, in which case
    /// this reports `CallStarted` directly (the ring mark is raised as
    /// `Pending` before the send so that window can never drop an answer).
    pub async fn place_call(
        &self,
        phone_or_jid: String,
        with_video: bool,
    ) -> Result<String, WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            // Glare guard: a second concurrent call would orphan the first
            // one's audio channels and its end reporting.
            if ctx.call.lock().unwrap().is_some() {
                return Err(WaError::Protocol {
                    msg: "a call is already active".to_string(),
                });
            }
            let jid = Self::parse_jid(&phone_or_jid)?;
            let (mic, playout) = ctx.audio_endpoints();
            // Video-from-start: the offer then advertises H.264 and the media
            // plane is wired before the peer answers. An outgoing offer
            // always carries what we ask for, so no VideoNotOffered here.
            // (`voip` is bound first: the builder borrows it, so it cannot
            // live in a temporary.)
            let voip = client.voip();
            let builder = voip.call(&jid).audio(mic, playout);
            let builder = if with_video {
                builder.video(
                    BridgeVideoSource {
                        rx: ctx.video_rx.clone(),
                        fps: DEFAULT_VIDEO_FPS,
                    },
                    BridgeVideoSink {
                        tx: ctx.vframe_tx.clone(),
                    },
                )
            } else {
                builder
            };
            // Ring mark BEFORE the offer send: the call id is generated
            // inside `start()`, so the mark goes up as `Pending` and an
            // accept landing in the send window is claimed (stashed) rather
            // than dropped — `settle_outgoing_ring` reports whichever state
            // the call is actually in once `start()` returns.
            *ctx.outgoing_ringing.lock().unwrap() = Some(OutgoingRing::Pending);
            let handle = match builder.start().await {
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
                WaEvent::CallStarted {
                    call_id: call_id.clone(),
                }
            } else {
                WaEvent::Ringing { peer: phone_or_jid }
            });
            Ok(call_id)
        }))
        .await
    }

    /// Answers the most recent incoming call with the bridge's audio channels.
    /// `with_video` attaches the video endpoints too, but only if the stored
    /// offer actually advertised video — an audio-only offer answered with
    /// video makes the library fail the accept (`CallError::VideoNotOffered`),
    /// so the gate here turns that into a silent audio-only accept.
    pub async fn accept_call(&self, with_video: bool) -> Result<(), WaError> {
        let client = self.client()?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            let incoming = ctx
                .incoming
                .lock()
                .unwrap()
                .take()
                .ok_or(WaError::NoActiveCall)?;
            let offer_is_video = matches!(
                &incoming.action,
                CallAction::Offer {
                    is_video: true,
                    group_jid: None,
                    ..
                }
            );
            let (mic, playout) = ctx.audio_endpoints();
            let voip = client.voip();
            let builder = voip.accept(&incoming).audio(mic, playout);
            let builder = if with_video && offer_is_video {
                builder.video(
                    BridgeVideoSource {
                        rx: ctx.video_rx.clone(),
                        fps: DEFAULT_VIDEO_FPS,
                    },
                    BridgeVideoSink {
                        tx: ctx.vframe_tx.clone(),
                    },
                )
            } else {
                builder
            };
            let handle = builder
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

    /// Sets this device's orientation (90° steps 0..3) carried in outbound
    /// `<video>` stanzas. The sensor rotation is what the receiver uses to
    /// rotate the decoded video; the value must be set before/with the video
    /// upgrade so the state-11/4 stanzas carry it. Applies immediately to a
    /// live call and is remembered for the next one.
    pub fn set_video_orientation(&self, orientation: u8) -> Result<(), WaError> {
        self.video_orientation
            .store(orientation % 4, std::sync::atomic::Ordering::Relaxed);
        if let Some(handle) = self.call.lock().unwrap().as_ref() {
            handle.set_device_orientation(orientation);
        }
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
        let deadline =
            std::time::Instant::now() + std::time::Duration::from_millis(u64::from(timeout_ms));
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

    /// Pushes one encoded H.264 access unit (Annex-B, start codes included)
    /// from the Kotlin encoder thread. The channel is bounded(4); when full
    /// the OLDEST AU is dropped so capture never blocks, and the `Ok(dropped)`
    /// return tells Kotlin to request a sync frame from the encoder (the peer
    /// lost reference data). Like `send_mic_pcm`, the channel never closes:
    /// without a live call the AU is buffered (drop-oldest) and flushed by
    /// the next `attach_call`.
    pub fn send_video_au(&self, mut au: Vec<u8>) -> Result<bool, WaError> {
        match self.video_tx.try_send(au) {
            Ok(()) => return Ok(false),
            Err(chan::TrySendError::Full(returned)) => au = returned,
            Err(chan::TrySendError::Closed(_)) => return Err(WaError::NoActiveCall),
        }
        // Full: remove the oldest AU ourselves (the engine's recv simply
        // waits for the next one), which frees a slot for this one.
        match self.video_rx.try_recv() {
            Ok(_) => {}
            // Drained between our try_send and now — a slot is already free.
            Err(chan::TryRecvError::Empty) => {}
            // The engine is gone (no call, or it just ended).
            Err(chan::TryRecvError::Closed) => return Err(WaError::NoActiveCall),
        }
        self.video_tx
            .send_blocking(au)
            .map(|_| true)
            .map_err(|_| WaError::NoActiveCall)
    }

    /// Pulls one received peer video AU from the Kotlin decode thread,
    /// polling with a 5 ms sleep. `None` means the engine sent nothing within
    /// `timeout_ms` — render the previous frame / wait.
    pub fn recv_video_frame(&self, timeout_ms: u32) -> Option<WaVideoFrame> {
        let deadline =
            std::time::Instant::now() + std::time::Duration::from_millis(u64::from(timeout_ms));
        loop {
            match self.vframe_rx.try_recv() {
                Ok(frame) => {
                    return Some(WaVideoFrame {
                        data: frame.data,
                        keyframe: frame.keyframe,
                        orientation: frame.orientation,
                    })
                }
                Err(chan::TryRecvError::Empty) => {}
                Err(chan::TryRecvError::Closed) => return None,
            }
            if std::time::Instant::now() >= deadline {
                return None;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
    }

    /// Starts OUR video direction on the active call (audio→video upgrade we
    /// initiate, or media for a `.video()` call started without endpoints).
    /// `fps` sets the source's RTP timestamp stride (`90_000 / fps`); it must
    /// match the pacing of the AUs Kotlin pushes via `send_video_au`.
    pub async fn start_video(&self, fps: u32) -> Result<(), WaError> {
        let handle = self
            .call
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::NoActiveCall)?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            handle
                .start_video(
                    BridgeVideoSource {
                        rx: ctx.video_rx.clone(),
                        fps,
                    },
                    BridgeVideoSink {
                        tx: ctx.vframe_tx.clone(),
                    },
                )
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })
        }))
        .await
    }

    /// Accepts the peer's video-upgrade request (a `VideoStateChanged` event
    /// whose `state` is an upgrade request): `token` is the echo the event
    /// carried, resolved against the bridge's stash of the engine's real
    /// token (the library's token is opaque across the FFI boundary — see
    /// [`WaVideoUpgradeToken`]). An unknown/stale echo (request superseded or
    /// cancelled) errors without touching the call.
    pub async fn accept_video(&self, token: WaVideoUpgradeToken, fps: u32) -> Result<(), WaError> {
        let handle = self
            .call
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::NoActiveCall)?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            let stashed =
                take_upgrade(&ctx.pending_upgrade, token.generation).ok_or(WaError::Protocol {
                    msg: "unknown video upgrade token".to_string(),
                })?;
            handle
                .accept_video(
                    stashed,
                    BridgeVideoSource {
                        rx: ctx.video_rx.clone(),
                        fps,
                    },
                    BridgeVideoSink {
                        tx: ctx.vframe_tx.clone(),
                    },
                )
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })
        }))
        .await
    }

    /// Stops OUR video direction (sends `<video state=6>`, tears the local
    /// media plane down). Idempotent per the library, so a duplicate stop is
    /// a no-op, never an error for the call UI.
    pub async fn stop_video(&self) -> Result<(), WaError> {
        let handle = self
            .call
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::NoActiveCall)?;
        let ctx = self.call_ctx();
        join(ctx.runtime.clone().spawn(async move {
            handle
                .stop_video()
                .await
                .map_err(|e| WaError::Protocol { msg: e.to_string() })
        }))
        .await
    }

    /// Sends the standalone `<video state=1>` (Enabled) the protocol wants
    /// after a mid-call video upgrade completes. Synchronous like `logout`:
    /// the stanza send is awaited via `block_on` on the caller's (non-tokio)
    /// thread — uniffi polls this fn there.
    pub fn announce_video_enabled(&self) -> Result<(), WaError> {
        let handle = self
            .call
            .lock()
            .unwrap()
            .clone()
            .ok_or(WaError::NoActiveCall)?;
        let runtime = self.runtime.get().ok_or(WaError::NotPaired)?;
        runtime
            .block_on(handle.announce_video_enabled())
            .map_err(|e| WaError::Protocol { msg: e.to_string() })
    }
}

/// Single-shot end marker: the call id whose end has already surfaced to
/// Kotlin, shared between the `wait_ended` watcher and the bot-event pump.
type EndedEmitted = Arc<Mutex<Option<String>>>;

/// Stash of the engine's real video-upgrade token (see [`WaVideoUpgradeToken`]).
type PendingUpgrade = Arc<Mutex<Option<VideoUpgradeToken>>>;

/// Consumes the stashed upgrade token whose `generation` matches Kotlin's
/// echo. The stash holds at most one live peer request, so the generation
/// identifies it; a miss (stale echo, superseded or cancelled request) leaves
/// the stash untouched and returns `None`.
fn take_upgrade(stash: &PendingUpgrade, generation: u64) -> Option<VideoUpgradeToken> {
    let mut guard = stash.lock().unwrap();
    match guard.as_ref() {
        Some(t) if t.generation() == generation => guard.take(),
        _ => None,
    }
}

/// Default source frame rate when Kotlin doesn't pick one (video-from-start):
/// the library's compatibility cadence (15 fps, `VIDEO_TS_STRIDE_15FPS`).
const DEFAULT_VIDEO_FPS: u32 = 15;

/// The bridge's `VideoSource`: hands the engine the Kotlin-pushed Annex-B AUs
/// (`send_video_au`) and reports the RTP timestamp stride for `fps`.
struct BridgeVideoSource {
    rx: chan::Receiver<Vec<u8>>,
    fps: u32,
}

impl VideoSource for BridgeVideoSource {
    fn frames(&self) -> chan::Receiver<Vec<u8>> {
        self.rx.clone()
    }

    fn rtp_timestamp_stride(&self) -> u32 {
        90_000 / self.fps.max(1)
    }
}

/// The bridge's `VideoSink`: the engine writes reassembled peer AUs Kotlin
/// pulls via `recv_video_frame`.
struct BridgeVideoSink {
    tx: chan::Sender<VideoFrame>,
}

impl VideoSink for BridgeVideoSink {
    fn playout(&self) -> chan::Sender<VideoFrame> {
        self.tx.clone()
    }
}

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
        Event::IncomingCall(call)
            if matches!(
                call.action,
                CallAction::Offer {
                    group_jid: None,
                    ..
                }
            ) =>
        {
            match offer_caller_pn_display(&call.action) {
                Some(pn) => Some(pn),
                None if call.from.is_lid() => {
                    // Query the bare user LID: a device-suffixed Jid fails the
                    // usync lookup and the raw fallback leaks the `:device`
                    // suffix into the call-log display.
                    let mut bare = call.from.clone();
                    bare.device = 0;
                    Some(resolve_lid_display(client, &bare).await)
                }
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
    // The local history-sync LID→PN mapping answers offline; the usync query
    // is only the fallback (it can time out or be privacy-refused).
    if let Ok(Some(entry)) = client.get_lid_pn_entry(lid).await {
        return format!("{}@s.whatsapp.net", entry.phone_number);
    }
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

/// Arms the callee-side ring expiry for a 1:1 offer: after the native caller
/// no-answer window the slot is cleared (if still this call) and a
/// `MissedCall` is emitted with the same resolved peer the panel was opened
/// with, so the incoming screen closes and the log writes the missed row.
fn arm_ring_timeout(
    tx: &mpsc::Sender<WaEvent>,
    incoming: &Arc<Mutex<Option<IncomingCall>>>,
    ring_call_id: &str,
    peer: String,
) {
    let ring_call_id = ring_call_id.to_string();
    let tx = tx.clone();
    let incoming = incoming.clone();
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(45)).await;
        let expired = incoming
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|c| c.action.call_id() == ring_call_id);
        if expired {
            *incoming.lock().unwrap() = None;
            log::info!("voip: ring timed out, missed");
            let _ = tx.send(WaEvent::MissedCall { peer });
        }
    });
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
                reason: reason.clone().unwrap_or_else(|| "ended".to_string()),
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

/// The account's own JID strings (pn + lid) — records list us among
/// participants for outgoing calls.
fn own_jids(client: &Client) -> Vec<String> {
    [client.pn(), client.lid()]
        .into_iter()
        .flatten()
        .map(|j| j.to_string())
        .collect()
}

/// True for a 1:1 regular call record — group calls and call links are out
/// of scope (SPEC). An absent call_type is the proto default = REGULAR.
fn one_to_one_record(r: &wa::CallLogRecord) -> bool {
    !r.call_type.is_some_and(|t| {
        !matches!(
            t,
            whatsapp_rust::waproto::whatsapp::call_log_record::CallType::REGULAR
        )
    }) && r.group_jid.is_none()
        && !r.is_call_link.unwrap_or(false)
}

/// Peer = creator (incoming) or the non-us participant (outgoing); either
/// way: the first candidate that isn't us.
fn record_peer_jid(r: &wa::CallLogRecord, own: &[String]) -> Option<String> {
    let candidate = if r.is_incoming.unwrap_or(false) {
        r.call_creator_jid.clone()
    } else {
        None
    };
    let mut candidates = candidate.into_iter().collect::<Vec<_>>();
    candidates.extend(r.participants.iter().filter_map(|p| p.user_jid.clone()));
    candidates.into_iter().find(|j| !is_own_jid(j, own))
}

/// Identity-only own check: user + server, ignoring device/agent components.
/// An exact string match missed our own participant in its other wire form
/// (bare LID vs `:device`-suffixed), so our own calls logged us as the peer
/// (feedback 2026-09-23: duplicate "FENN"/"+13083225387035" rows).
fn is_own_jid(candidate: &str, own: &[String]) -> bool {
    let Ok(c) = candidate.parse::<Jid>() else {
        return false;
    };
    own.iter().any(|o| {
        o.parse::<Jid>()
            .map(|o| o.user == c.user && o.server == c.server)
            .unwrap_or(false)
    })
}

fn call_record_to_wa(r: &wa::CallLogRecord, peer: String) -> Option<WaCallRecord> {
    use whatsapp_rust::waproto::whatsapp::call_log_record::CallResult;
    let start = *r.start_time.as_ref()? as u64;
    Some(WaCallRecord {
        peer,
        at: start * 1000,
        outgoing: !r.is_incoming.unwrap_or(false),
        // CONNECTED is the proto3 default (0), so the server omits it for
        // connected calls — an absent result IS a connected call (verified:
        // known-connected records all arrive without the field). Only an
        // explicit non-connected, non-answered-elsewhere result is missed.
        missed: !matches!(
            r.call_result,
            None | Some(CallResult::CONNECTED) | Some(CallResult::ACCEPTEDELSEWHERE)
        ),
        // Accepted on another linked device: the wire omits `duration` for
        // these — our device wasn't a party to the connected call.
        answered_elsewhere: matches!(r.call_result, Some(CallResult::ACCEPTEDELSEWHERE)),
        duration: r.duration.unwrap_or(0).max(0) as u64,
    })
}

/// LID → phone JID through the client's local mapping cache (no network);
/// anything that isn't a LID passes through unchanged.
async fn resolve_jid(client: &Client, jid: String) -> String {
    let Ok(parsed) = jid.parse::<Jid>() else {
        return jid;
    };
    if !parsed.is_lid() {
        return jid;
    }
    match client.get_lid_pn_entry(&parsed).await {
        Ok(Some(entry)) => format!("{}@s.whatsapp.net", entry.phone_number),
        _ => jid,
    }
}

/// Extracts call-log records + peer contacts from a decoded history-sync blob
/// and emits them as import events. `own` lists our own JID strings so a
/// record's participant list can drop us (outgoing calls list us as creator).
/// Only peer JIDs, names, and call metadata leave the blob — never messages.
/// Contacts accumulate across the pairing blobs in `peers`/`pushnames`
/// (conversations typically land in an earlier chunk than the pushnames that
/// name them); every blob emits the full accumulated set with `replace: true`
/// so the stored list always equals the 1:1 conversation set.
fn import_history(
    own: &[String],
    hs: &whatsapp_rust::waproto::whatsapp::HistorySync,
    tx: &mpsc::Sender<WaEvent>,
    peers: &Mutex<std::collections::HashMap<String, String>>,
    pushnames: &Mutex<std::collections::HashMap<String, String>>,
) {
    // LID → phone JID: real numbers beat bare LIDs in the log.
    let pn_of: std::collections::HashMap<&str, &str> = hs
        .phone_number_to_lid_mappings
        .iter()
        .filter_map(|m| Some((m.lid_jid.as_deref()?, m.pn_jid.as_deref()?)))
        .collect();
    let resolve = |jid: String| -> String {
        pn_of
            .get(jid.as_str())
            .map(|pn| (*pn).to_string())
            .unwrap_or(jid)
    };

    let entries: Vec<WaCallRecord> = hs
        .call_log_records
        .iter()
        .filter(|r| one_to_one_record(r))
        .filter_map(|r| {
            let peer = record_peer_jid(r, own)?;
            call_record_to_wa(r, resolve(peer))
        })
        .collect();
    if !entries.is_empty() {
        let _ = tx.send(WaEvent::CallLogImport { entries });
    }

    // Contacts: 1:1 conversation peers only. The blob's pushname list covers
    // every group participant, so it only ever NAMES a peer — never adds one.
    let is_peer = |id: &str| {
        id.contains('@')
            && !id.starts_with("0@")
            && !id.ends_with("@g.us")
            && !id.starts_with("status@")
            && !id.ends_with("@broadcast")
    };
    {
        let mut pn = pushnames.lock().unwrap();
        for p in &hs.pushnames {
            if let (Some(id), Some(name)) = (p.id.as_ref(), p.pushname.as_ref()) {
                if is_peer(id) {
                    pn.insert(id.clone(), name.clone());
                }
            }
        }
    }
    {
        let mut set = peers.lock().unwrap();
        for c in &hs.conversations {
            let id = &c.id;
            if is_peer(id) {
                set.entry(id.clone()).or_default();
            }
        }
        let contacts: Vec<WaContact> = {
            let pn = pushnames.lock().unwrap();
            set.keys()
                .map(|id| WaContact {
                    id: resolve(id.clone()),
                    name: pn.get(id).cloned().unwrap_or_default(),
                })
                .collect()
        };
        let _ = tx.send(WaEvent::ContactsImport {
            contacts,
            replace: true,
            rename_only: false,
        });
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
    fn history_sync_import_maps_records_and_contacts() {
        use whatsapp_rust::waproto::whatsapp::{
            call_log_record::{CallResult, CallType, ParticipantInfo},
            CallLogRecord, Conversation, HistorySync, PhoneNumberToLIDMapping, Pushname,
        };
        let (tx, rx) = mpsc::channel();
        let hs = HistorySync {
            phone_number_to_lid_mappings: vec![PhoneNumberToLIDMapping {
                pn_jid: Some("15550000000@s.whatsapp.net".into()),
                lid_jid: Some("1000000000@lid".into()),
            }],
            call_log_records: vec![
                CallLogRecord {
                    call_creator_jid: Some("1000000000@lid".into()),
                    start_time: Some(1_700_000_000),
                    duration: Some(16),
                    is_incoming: Some(true),
                    call_result: Some(CallResult::CONNECTED),
                    call_type: Some(CallType::REGULAR),
                    participants: vec![ParticipantInfo {
                        user_jid: Some("1000000000@lid".into()),
                        call_result: None,
                    }],
                    ..Default::default()
                },
                // The connected-call wire shape: the server omits callResult
                // (proto3 default 0 = CONNECTED) — must not read as missed.
                CallLogRecord {
                    call_creator_jid: Some("1000000000@lid".into()),
                    start_time: Some(1_700_050_000),
                    is_incoming: Some(true),
                    call_type: Some(CallType::REGULAR),
                    participants: vec![ParticipantInfo {
                        user_jid: Some("1000000000@lid".into()),
                        call_result: None,
                    }],
                    ..Default::default()
                },
                CallLogRecord {
                    call_creator_jid: Some("15551234567@s.whatsapp.net".into()), // us
                    start_time: Some(1_700_100_000),
                    is_incoming: Some(false),
                    call_result: Some(CallResult::MISSED),
                    participants: vec![ParticipantInfo {
                        user_jid: Some("4915112345678@s.whatsapp.net".into()),
                        call_result: None,
                    }],
                    ..Default::default()
                },
                // Group call — must be dropped.
                CallLogRecord {
                    call_creator_jid: Some("1000000000@lid".into()),
                    start_time: Some(1_700_200_000),
                    group_jid: Some("1203...@g.us".into()),
                    ..Default::default()
                },
            ],
            pushnames: vec![
                Pushname {
                    id: Some("4915112345678@s.whatsapp.net".into()),
                    pushname: Some("Fenn".into()),
                },
                // A group participant we never had a 1:1 chat with — the
                // pushname alone must not enter the contact list.
                Pushname {
                    id: Some("4915199988877@s.whatsapp.net".into()),
                    pushname: Some("Stranger".into()),
                },
            ],
            conversations: vec![
                Conversation {
                    id: "4915112345678@s.whatsapp.net".into(),
                    ..Default::default()
                },
                Conversation {
                    id: "status@broadcast".into(),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        let own = ["15551234567@s.whatsapp.net".to_string()];
        import_history(
            &own,
            &hs,
            &tx,
            &Mutex::new(std::collections::HashMap::new()),
            &Mutex::new(std::collections::HashMap::new()),
        );
        drop(tx);
        let got: Vec<WaEvent> = rx.iter().collect();
        let log = got
            .iter()
            .find_map(|e| match e {
                WaEvent::CallLogImport { entries } => Some(entries.clone()),
                _ => None,
            })
            .expect("call log import emitted");
        assert_eq!(log.len(), 3);
        assert_eq!(log[0].peer, "15550000000@s.whatsapp.net"); // LID resolved to PN
        assert_eq!(log[0].at, 1_700_000_000_000);
        assert_eq!(log[0].duration, 16);
        assert!(!log[0].outgoing && !log[0].missed);
        // Absent callResult (proto3 default = CONNECTED) → not missed.
        assert!(!log[1].missed);
        assert_eq!(log[2].peer, "4915112345678@s.whatsapp.net"); // us dropped from participants
        assert!(log[2].outgoing && log[2].missed);
        let contacts = got
            .iter()
            .find_map(|e| match e {
                WaEvent::ContactsImport { contacts, .. } => Some(contacts.clone()),
                _ => None,
            })
            .expect("contacts import emitted");
        assert_eq!(contacts.len(), 1);
        assert_eq!(contacts[0].id, "4915112345678@s.whatsapp.net");
        assert_eq!(contacts[0].name, "Fenn");
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

    /// V0: the video-AU channel mirrors the mic queue — bounded(4), and once
    /// full the OLDEST AU is dropped while `send_video_au` reports the shed
    /// (Kotlin's cue to request a sync frame from the encoder).
    #[test]
    fn video_channel_drops_oldest_when_full_and_reports() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        for first in 0..6u8 {
            let dropped = bridge
                .send_video_au(vec![first])
                .expect("send never blocks");
            assert_eq!(dropped, first >= 4, "AU {first}: shed only once full");
        }
        let mut got = Vec::new();
        while let Ok(au) = bridge.video_rx.try_recv() {
            got.push(au[0]);
        }
        // bounded(4): AUs 0 and 1 were dropped, the newest four survive.
        assert_eq!(got, vec![2, 3, 4, 5]);
    }

    /// V0: what the engine receives on a call — the `BridgeVideoSource` hands
    /// out the Kotlin-pushed AUs in order, at the requested RTP stride.
    #[test]
    fn video_au_roundtrips_to_the_source_receiver() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        for seq in 0..3u8 {
            bridge
                .send_video_au(vec![seq])
                .expect("bounded(4) is empty");
        }
        let source = BridgeVideoSource {
            rx: bridge.video_rx.clone(),
            fps: 20,
        };
        let rx = source.frames();
        assert_eq!(source.rtp_timestamp_stride(), 90_000 / 20);
        for seq in 0..3u8 {
            let au = rx.try_recv().expect("pushed AU");
            assert_eq!(au, vec![seq]);
        }
        assert!(rx.try_recv().is_err(), "no phantom AUs");
    }

    /// V0: the sink channel carries the engine's reassembled peer AUs to
    /// `recv_video_frame` (keyframe/orientation preserved), which times out
    /// to `None` exactly like `recv_playout_pcm` times out to empty.
    #[test]
    fn recv_video_frame_returns_frame_then_times_out_to_none() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        let mut frame = VideoFrame::new(vec![0, 0, 0, 1, 0x65]); // IDR NAL → keyframe
        frame.orientation = 2;
        bridge
            .vframe_tx
            .send_blocking(frame)
            .expect("bounded(8) is empty");

        let got = bridge.recv_video_frame(40).expect("frame within timeout");
        assert!(got.keyframe);
        assert_eq!(got.orientation, 2);
        assert_eq!(got.data, vec![0, 0, 0, 1, 0x65]);

        let started = std::time::Instant::now();
        assert!(bridge.recv_video_frame(40).is_none());
        assert!(started.elapsed() >= std::time::Duration::from_millis(40));
    }

    /// V0: the upgrade stash resolves only a matching generation. The hit
    /// path needs a real engine-issued token (`VideoUpgradeToken` has no
    /// external constructor — the very reason the stash exists), so the miss
    /// paths are what a unit test can drive; the stash is left untouched so
    /// a still-live request survives a stale echo.
    #[test]
    fn upgrade_stash_misses_leave_it_untouched() {
        let stash: PendingUpgrade = Arc::default();
        assert!(take_upgrade(&stash, 7).is_none(), "empty stash misses");
        assert!(stash.lock().unwrap().is_none());
    }

    /// V0: every video-control entry point on a call-less bridge errors
    /// `NoActiveCall` instead of panicking or silently succeeding.
    #[tokio::test]
    async fn video_control_without_a_call_is_no_active_call() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        assert!(matches!(
            bridge.start_video(15).await,
            Err(WaError::NoActiveCall)
        ));
        assert!(matches!(
            bridge
                .accept_video(
                    WaVideoUpgradeToken {
                        generation: 1,
                        epoch: 0
                    },
                    15
                )
                .await,
            Err(WaError::NoActiveCall)
        ));
        assert!(matches!(
            bridge.stop_video().await,
            Err(WaError::NoActiveCall)
        ));
        assert!(matches!(
            bridge.announce_video_enabled(),
            Err(WaError::NoActiveCall)
        ));
    }

    #[tokio::test]
    async fn place_call_unpaired_is_not_paired() {
        let bridge = WaBridge::new(Box::new(Sink(Arc::default())));
        assert!(matches!(
            bridge.place_call("+15551234567".to_string(), false).await,
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
        let got = std::thread::spawn(move || futures::executor::block_on(join(handle.spawn(body))))
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
        assert!(
            gate_end(&ended, &missed, &mapped),
            "MissedCall is never gated"
        );
        let mapped = map_event(&terminate("CALL-3"), None).expect("Terminate maps to CallEnded");
        assert!(
            gate_end(&ended, &terminate("CALL-3"), &mapped),
            "different call id claims its own end"
        );
    }

    /// F1: the ringing→answered transition. The peer's `<accept>` claims the
    /// ringing mark exactly once — a duplicate accept stanza (re-delivery)
    /// and a stale accept after the ring timed out (terminate cleared the
    /// mark) must not re-emit `CallStarted`.
    #[test]
    fn outgoing_call_answers_exactly_once() {
        let ringing: OutgoingRinging = Arc::default();
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-1".to_string()));

        assert!(
            claim_outgoing_answer(&ringing, "CALL-1"),
            "the answer claims the ring"
        );
        assert!(
            !claim_outgoing_answer(&ringing, "CALL-1"),
            "duplicate accept is dropped"
        );
        assert!(
            !claim_outgoing_answer(&ringing, "CALL-2"),
            "a foreign call id never claims"
        );

        // Ring timeout: the terminate clears the mark, a late accept is inert.
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-3".to_string()));
        clear_outgoing_ringing(&ringing, "CALL-3", None, None);
        assert!(
            !claim_outgoing_answer(&ringing, "CALL-3"),
            "no answer after the ring ended"
        );

        // A terminate for a different call id leaves the ring untouched.
        *ringing.lock().unwrap() = Some(OutgoingRing::Ringing("CALL-4".to_string()));
        clear_outgoing_ringing(&ringing, "CALL-9", None, None);
        assert!(
            claim_outgoing_answer(&ringing, "CALL-4"),
            "unrelated terminate must not clear"
        );
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
        assert!(
            ringing.lock().unwrap().is_none(),
            "the answered mark is consumed"
        );

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
        assert!(
            ringing.lock().unwrap().is_none(),
            "our own terminate drops it"
        );
    }

    /// F2: the offer's server-provided `caller_pn` is the display string for
    /// 1:1 offers (no query needed); group offers are out of scope, and the
    /// resolved string overrides the raw LID in the mapped events.
    #[test]
    fn peer_display_prefers_caller_pn_and_resolved_string() {
        let caller: Jid = "13083225387035@lid".parse().expect("lid jid");
        let pn: Jid = "15551234567@s.whatsapp.net".parse().expect("pn jid");
        let offer = |caller_pn: Option<Jid>, group_jid: Option<Jid>| CallAction::Offer {
            call_id: "CALL-1".to_string(),
            call_creator: caller.clone(),
            caller_pn,
            caller_country_code: None,
            device_class: None,
            joinable: false,
            is_video: false,
            audio: Vec::new(),
            group_jid,
        };

        assert_eq!(
            offer_caller_pn_display(&offer(Some(pn.clone()), None)).as_deref(),
            Some("15551234567@s.whatsapp.net"),
        );
        assert_eq!(
            offer_caller_pn_display(&offer(None, None)),
            None,
            "no attribute, no claim"
        );
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
        let mapped =
            map_event(&incoming(offer(None, None)), Some("15551234567")).expect("1:1 offer maps");
        assert!(matches!(&mapped, WaEvent::IncomingCall { peer, .. } if peer == "15551234567"));
        let mapped = map_event(&incoming(offer(None, None)), None).expect("1:1 offer maps");
        assert!(
            matches!(&mapped, WaEvent::IncomingCall { peer, .. } if peer == "13083225387035@lid")
        );
    }
}

#[cfg(test)]
mod lid_tests {
    use whatsapp_rust::Jid;

    #[test]
    fn lid_roundtrip() {
        let jid: Jid = "243885506854938@lid".parse().unwrap();
        println!("PARSED: {:?} -> {}", jid, jid);
        assert_eq!(jid.to_string(), "243885506854938@lid");
    }
}

#[cfg(test)]
mod keyframe_pressure_tests {
    use super::{KeyframePressureGate, peer_video_keyframe_demand};
    use std::time::{Duration, Instant};
    use wacore::voip::rtcp::{RtcpFeedback, RTCP_PT_PSFB};

    const VIDEO_SSRC: u32 = 0x1234_5678;

    fn psfb(fmt: u8, media_ssrc: u32, fci: Vec<u8>) -> RtcpFeedback {
        RtcpFeedback {
            packet_type: RTCP_PT_PSFB,
            fmt,
            sender_ssrc: 0xdead_beef,
            media_ssrc,
            fci,
        }
    }

    #[test]
    fn pli_on_referenced_video_ssrc_fires() {
        assert!(peer_video_keyframe_demand(
            true,
            &[psfb(1, VIDEO_SSRC, vec![])],
            &[VIDEO_SSRC],
        ));
    }

    #[test]
    fn pli_on_unreferenced_or_zero_ssrc_does_not_fire() {
        // reports_video vouches the packet covers our video stream, but the
        // PLI itself must name a source the packet references.
        assert!(!peer_video_keyframe_demand(
            true,
            &[psfb(1, 0x9999, vec![])],
            &[VIDEO_SSRC],
        ));
        assert!(!peer_video_keyframe_demand(
            true,
            &[psfb(1, 0, vec![])],
            &[VIDEO_SSRC],
        ));
    }

    #[test]
    fn plain_receiver_report_does_not_fire() {
        assert!(!peer_video_keyframe_demand(true, &[], &[VIDEO_SSRC]));
    }

    #[test]
    fn non_payload_feedback_does_not_fire() {
        // RTCP_PT_RTPFB (205) — e.g. NACK — is not keyframe demand.
        let nack = RtcpFeedback {
            packet_type: 205,
            fmt: 1,
            sender_ssrc: 0xdead_beef,
            media_ssrc: VIDEO_SSRC,
            fci: vec![],
        };
        assert!(!peer_video_keyframe_demand(true, &[nack], &[VIDEO_SSRC]));
    }

    #[test]
    fn fir_without_media_ssrc_names_target_in_fci() {
        // FIR commonly leaves the media-source field zero and names targets
        // in 8-byte FCI rows (mirrors summarize_rtcp's parse, rtcp.rs:211).
        let fci = VIDEO_SSRC.to_be_bytes()
            .iter()
            .chain(0u32.to_be_bytes().iter())
            .copied()
            .collect();
        assert!(peer_video_keyframe_demand(
            true,
            &[psfb(4, 0, fci)],
            &[VIDEO_SSRC],
        ));
    }

    #[test]
    fn no_video_in_the_packet_does_not_fire() {
        assert!(!peer_video_keyframe_demand(
            false,
            &[psfb(1, VIDEO_SSRC, vec![])],
            &[VIDEO_SSRC],
        ));
    }

    #[test]
    fn gate_admits_one_event_per_window() {
        let mut gate = KeyframePressureGate::default();
        let t0 = Instant::now();
        assert!(gate.admit(t0));
        assert!(!gate.admit(t0 + Duration::from_millis(199)));
        assert!(!gate.admit(t0 + Duration::from_millis(200) - Duration::from_nanos(1)));
        assert!(gate.admit(t0 + Duration::from_millis(200)));
    }
}
