package com.lightphone.calls.server

import android.content.Context
import android.util.Log
import android.view.Surface
import com.lightphone.calls.server.bridge.WaBridge
import com.lightphone.calls.server.bridge.WaEvent
import com.lightphone.calls.server.bridge.WaListener
import com.lightphone.calls.server.bridge.WaVideoUpgradeToken
import com.lightphone.calls.server.bridge.WaVideoFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** UI reduction of the wire's `<video state=N>` ints (the library's
 *  `VideoState`: 0 Disabled, 1 Enabled, 2 Paused, 3 UpgradeRequest,
 *  4 UpgradeAccept, 5 UpgradeReject, 6 Stopped, 7 UpgradeRejectByTimeout,
 *  8 UpgradeCancel, 9 UpgradeCancelByTimeout, 10 UnknownPeer,
 *  11 UpgradeRequestV2, 20 Error). The upgrade prompt gates on the
 *  request states (3/11 — the library's `is_upgrade_request()`); Enabled
 *  and an accepted upgrade are On; everything else is Off. */
sealed interface VideoUiState {
    data object Off : VideoUiState
    data object On : VideoUiState
    /** Live peer upgrade request — the token echoes back to `acceptVideo`. */
    data class UpgradeRequested(val token: WaVideoUpgradeToken) : VideoUiState
}

object WaCallEngine : WaListener {
    private const val TAG = "WaCallEngine"

    private val _events = MutableStateFlow<WaEvent?>(null)
    val events: StateFlow<WaEvent?> = _events

    /** Replay-free mirror of [events] for screens that must never act on a
     *  stale delivery. The StateFlow instantly replays its last value to a
     *  fresh collector — after a missed call that last value is `CallEnded`,
     *  so the next app entry flashed "call ended" on a panel that had just
     *  been routed from the replayed `IncomingCall` (2026-09-24 LP3 report:
     *  the flash then never left). Screens that only react to live events
     *  subscribe here; consumers that NEED the replay stay on [events] —
     *  the placement wait (`ActiveCallViewModel.place`, which parks the
     *  flow first), the pairing screen's QR rotations, and home's re-entry
     *  resync of a call that is still live. */
    private val _liveEvents = MutableSharedFlow<WaEvent>(extraBufferCapacity = 64)
    val liveEvents: SharedFlow<WaEvent> = _liveEvents

    /** Publishes to both surfaces — the StateFlow for replay consumers, the
     *  shared flow for live-only ones. */
    private fun publish(event: WaEvent) {
        _events.value = event
        _liveEvents.tryEmit(event)
    }

    /** The live incoming offer home routes from — engine STATE, not an
     *  event. A cold-start offer lands mid-launch (the bot can receive it
     *  before the activity is even up), and home's event collector
     *  subscribes late: the events StateFlow only replays its LAST value,
     *  which a following history-sync import or the caller's terminate has
     *  often already replaced — the offer was lost and the panel never
     *  routed (2026-09-25 live regression: incoming call during a cold
     *  start was never shown). This StateFlow holds the offer for exactly
     *  as long as it lives, so a late-subscribing collector still routes
     *  it. Cleared on accept (→ `CallStarted`), reject, `MissedCall`,
     *  `CallEnded`, [dropCurrentCall]. */
    private val _pendingOffer = MutableStateFlow<WaEvent.IncomingCall?>(null)
    val pendingOffer: StateFlow<WaEvent.IncomingCall?> = _pendingOffer

    /** Engine-level failure the UI renders as a small error line (the bridge
     *  process store failed to open, a call connected with the mic FGS start
     *  blocked while backgrounded, …). Cleared on a successful start and
     *  when a call ends. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** The video plane's UI state, reduced from `VideoStateChanged` (see
     *  [VideoUiState]). */
    private val _videoState = MutableStateFlow<VideoUiState>(VideoUiState.Off)
    val videoState: StateFlow<VideoUiState> = _videoState

    /** Peer camera orientation in 90° steps (0..3), updated by the decode
     *  half — the UI rotates the surface with it. */
    val peerOrientation = MutableStateFlow(0)

    /** The call's video direction encode tier (SPEC §3.3). Its fps feeds the
     *  bridge's RTP timestamp stride on `startVideo`/`acceptVideo`. */
    private val videoTier = VideoTier.DEFAULT

    /** The incoming offer advertised video — the answer defaults to
     *  `acceptCall(withVideo = true)` for it, so the answer flow routes the
     *  offer's video through without the screen needing to know. Read by the
     *  incoming screen to gate the answer tap on the CAMERA grant. */
    @Volatile
    var incomingVideoOffer = false
        private set

    /** Explicit user video consent for the live call: answering a video
     *  offer, an outgoing with-video placement, an accepted upgrade, or the
     *  toggle. Only this — never a raw `VideoStateChanged(1)` — lets
     *  `CallStarted` start the camera (state 1 can arrive at offer time,
     *  before the user opted in). */
    @Volatile
    private var videoConsent = false

    /** Auto-restarts since the last successful pairing — caps the watchdog /
     *  unlink respawn loop when recovery is hopeless. */
    private var autoRestarts = 0

    @Volatile
    private var bridge: WaBridge? = null
    private var appContext: Context? = null
    private var audioLink: AudioLink? = null
    private var callLog: CallLogStore? = null
    private var contacts: ContactStore? = null
    /** Buffered import entries — coalesced so a full-sync replay (one event
     *  per record) doesn't rewrite the log file per event. */
    private val pendingImports = mutableListOf<CallLogEntry>()
    @Volatile
    private var importFlushScheduled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The live call's peer, for the call log (`CallEnded` carries no peer).
     *  Set by [placeCall] and `IncomingCall`; cleared when the log entry for
     *  the call is written. */
    @Volatile
    var currentCallPeer: String? = null
        private set
    @Volatile
    var currentCallOutgoing: Boolean = false
        private set
    /** True from `CallStarted` to the call's end — gates the `CallEnded`
     *  log entry so an offer that died before accept (MissedCall side)
     *  isn't double-entered. */
    @Volatile
    private var currentCallStarted = false

    /** The `at` stamp of the live call's log entry (placement for outgoing,
     *  connect for incoming) — the key the `CallEnded` duration update. */
    @Volatile
    private var currentCallLogAt = 0L

    /** Connect epoch (CallStarted) — `CallEnded` computes the log entry's
     *  connected seconds from it; 0 when the call never connected. */
    @Volatile
    private var currentCallConnectedAt = 0L

    /** Resolved display peer of the currently ringing incoming offer —
     *  survives the offer's not-started `CallEnded` so a following
     *  `MissedCall` (which carries the caller's raw LID) still logs the
     *  real number. */
    @Volatile
    private var pendingIncomingPeer: String? = null

    /** Whether the active-call screen has been routed for the current call —
     *  the dial screen routes it immediately (native: tap call → call
     *  panel), later Ringing/CallStarted must not stack a second one. */
    @Volatile
    var activeScreenRouted: Boolean = false

    /** Drops a dead call attempt (timeout / unreachable peer): the parked
     *  `placeCall` bridge call never returns, so its state is cleared here
     *  by the caller. */
    fun dropCurrentCall() {
        currentCallPeer = null
        currentCallOutgoing = false
        currentCallStarted = false
        currentCallConnectedAt = 0
        currentCallLogAt = 0
        incomingVideoOffer = false
        activeScreenRouted = false
        _pendingOffer.value = null
    }

    fun attach(context: Context) {
        if (bridge != null) return
        synchronized(this) {
            if (bridge != null) return
            val app = context.applicationContext
            appContext = app
            Log.i(TAG, "attach: dir=${app.filesDir}")
            callLog = CallLogStore(app.filesDir)
            contacts = ContactStore(app.filesDir)
            val created = WaBridge(this)
            // start() opens the session store and spawns the bot (~1.6 s);
            // attach runs on the bootstrap provider's main thread, so the
            // call must land off it. The bridge is published only once
            // start() succeeds — a failed start leaves the field null so
            // the next attach retries instead of early-returning forever
            // (START_STICKY keeps the process alive to allow that).
            Thread {
                try {
                    created.start(app.filesDir.resolve("wa-session").absolutePath)
                    synchronized(this) { bridge = created }
                    _error.value = null
                    healIdentities(created)
                } catch (e: Exception) {
                    // A failed start means no bot is running; the sync service
                    // keeps the process alive so a relaunch can retry. Failure
                    // details carry no session material.
                    _error.value = "WhatsApp failed to start — reopen the tool"
                    Log.w(TAG, "wa bridge start failed: ${e.javaClass.simpleName}")
                }
            }.start()
        }
    }

    /** One-shot identity heal after the bridge starts: peers first stored
     *  under a raw LID (the history-sync blob's mapping table can be empty)
     *  are rewritten onto their resolved phone JID, collapsing the duplicate
     *  rows the two ingest paths produced. */
    private fun healIdentities(bridge: WaBridge) {
        scope.launch {
            val lids = (callLog?.all() ?: emptyList()).map { it.peer }
                .plus((contacts?.all() ?: emptyList()).map { it.id })
                .filter { it.endsWith("@lid") }
                .distinct()
            if (lids.isEmpty()) return@launch
            val mapping = runCatching { bridge.resolveLids(lids) }
                .getOrDefault(emptyList())
                .associate { it.lid to it.pn }
            if (mapping.isNotEmpty()) {
                callLog?.remap(mapping)
                contacts?.remap(mapping)
            }
            // Self purge: own calls leaked in as peer rows (the wire carries
            // our own JID in several forms) — drop them from log + contacts.
            val own = runCatching { bridge.ownJids() }
                .getOrDefault(emptyList())
                .map { it.jidUserPart() }
                .toSet()
            if (own.isNotEmpty()) {
                callLog?.removePeers(own)
                contacts?.removePeers(own)
            }
        }
    }

    /** Parks the flow at null so a previous call's terminal event can't be
     *  replayed into the next placement's wait — a stale `CallEnded` made the
     *  panel flash "call failed" while the real offer rang the peer (orphan). */
    fun parkEvents() {
        _events.value = null
    }

    fun version(): String = bridge?.version() ?: "not-started"

    /** The identity-bearing user part of a JID string: everything before the
     *  `@`, minus any device (`:9`) or agent (`.9`) suffixes. */
    private fun String.jidUserPart(): String =
        substringBefore('@').substringBefore('.').substringBefore(':')

    /** Own account, once known — a restored session reads it off the session
     *  store at attach time; a fresh pairing gets it with `Paired`. */
    fun account(): String? = bridge?.account()

    /** Own WhatsApp profile name once known — the Settings "Paired as" line
     *  (the pn is the fallback). */
    fun accountName(): String? = bridge?.accountName()

    /** The persisted call log, newest last — rendered by the home screen. */
    fun callLogEntries(): List<CallLogEntry> = callLog?.all() ?: emptyList()

    /** Imported peer contacts (history sync) — rendered by the contacts
     *  screen alongside call-log peers. */
    fun contactEntries(): List<ContactEntry> = contacts?.all() ?: emptyList()

    // Call control — thin wrappers over the bridge's async surface, called
    // from the screens' ViewModels. The bridge throws WaException on failure;
    // the caller renders it, nothing is logged (peer data / reasons).

    /** Places a 1:1 call to a bare phone number (E.164 `+` ok) or full JID.
     *  `withVideo` offers H.264 from the start (V0 bridge surface; the
     *  encode/render UI is a later task — callers pass false for now).
     *  The attempt is logged instantly (the user sees the call in the log
     *  while it still connects) — `CallEnded` never logs. */
    suspend fun placeCall(phoneOrJid: String, withVideo: Boolean = false) {
        Log.i(TAG, "placeCall: callLog=${callLog != null} peerLen=${phoneOrJid.length}")
        currentCallPeer = phoneOrJid
        currentCallOutgoing = true
        currentCallLogAt = System.currentTimeMillis()
        callLog?.add(
            CallLogEntry(
                peer = phoneOrJid,
                outgoing = true,
                missed = false,
                at = currentCallLogAt,
            ),
        )
        try {
            bridgeOrThrow().placeCall(phoneOrJid, withVideo)
            if (withVideo) videoConsent = true
        } catch (e: Exception) {
            // No call came up — drop the peer so the next `CallEnded` (or a
            // glare-suppressed offer) isn't logged against this attempt.
            videoConsent = false
            currentCallPeer = null
            currentCallStarted = false
            throw e
        }
    }

    suspend fun acceptCall(withVideo: Boolean = incomingVideoOffer) {
        // Fake call (dev design aid): no bridge to accept — the CallStarted
        // lands directly on the event flow and the screens run for real.
        // The demo answer still runs the real call FGS so the emulator
        // exercises the notification + FGS type from the answer tap (the
        // fake end paths below stop it).
        if (fakeCall) {
            Ringer.stop()
            appContext?.let { WaCallService.tryStart(it) }
            // Direct publish — the reducer's CallStarted branch doesn't run,
            // so the offer must be cleared here.
            _pendingOffer.value = null
            publish(WaEvent.CallStarted(callId = "fake"))
            return
        }
        // CAMERA ungranted (denied in the answer flow, or a server-side race
        // behind the UI's request): answer audio-only — never advertise a
        // video direction we cannot open a camera for. The UI requests the
        // grant before this call; this is the honest fallback.
        val video = withVideo && cameraGranted()
        if (video) {
            videoConsent = true
            // The camera opens below, but the sensor value is a static
            // characteristic — signal it BEFORE the accept so the state-4/1
            // stanzas carry it.
            runCatching { bridgeOrThrow().setVideoOrientation(videoOrientationSignal()) }
            startVideoEncoding()
        }
        try {
            bridgeOrThrow().acceptCall(video)
        } catch (e: Exception) {
            // The accept failed — never leave a consented camera running.
            if (video) {
                videoConsent = false
                scope.launch { stopVideoEncoding() }
            }
            throw e
        }
    }

    suspend fun rejectCall() = bridgeOrThrow().rejectCall()

    suspend fun terminate() = bridgeOrThrow().terminate()

    /** Fresh pairing flow: the QR refs expire after ~3 minutes and
     *  whatsapp-rust disconnects a QR-only session (WA Web's UNPAIRED_IDLE —
     *  the "reload" is the surface's job). Aborts the old bot and respawns
     *  one on the same store; the new `PairingQr` rotations land on
     *  [events]. Runs on the engine scope (bot start is slow). */
    fun restartPairing() {
        // Park the flow on NeedPairing so a subscribing pairing screen shows
        // blank instead of replaying the dead pre-restart QR.
        _events.value = WaEvent.NeedPairing
        scope.launch { runCatching { bridgeOrThrow().restartPairing() } }
    }

    /** User-initiated unpair: deregister the device with WhatsApp (best
     *  effort — offline it degrades to a local session wipe) and respawn
     *  into the pairing flow. The bridge suppresses the deregistration's
     *  own `Unlinked` event, so the engine's server-side-unlink recovery
     *  (which would wipe + respawn again) never fires for this path. */
    fun unpair() {
        _events.value = WaEvent.NeedPairing
        scope.launch { runCatching { bridgeOrThrow().logout() } }
    }

    suspend fun setMuted(muted: Boolean) {
        // Fake call: no bridge — the toggle is pure UI state.
        if (fakeCall) return
        bridgeOrThrow().setMuted(muted)
    }

    /** Dev-only fake call (emulator design aid; trigger: five taps on the
     *  version line in Settings): synthesizes the full call event flow —
     *  ring panel, connect, toggles, hang-up — with no bridge traffic, no
     *  audio plane (no mic FGS), and no log entries. Every call-control
     *  entry point below checks [fakeCall] and takes the local branch. */
    private var fakeCall = false

    fun debugIncomingCall(peer: String = "15550102030") {
        fakeCall = true
        onEvent(WaEvent.IncomingCall(peer = peer, isVideo = false))
    }

    /** Fire-and-forget decline of the incoming offer — runs on the engine's
     *  own scope so the decline is sent even if the screen leaves first. */
    fun reject() {
        // The offer is being declined — nothing left to route (the bridge's
        // own end event may never come).
        _pendingOffer.value = null
        // Fake call: the screen closes itself; just release the call state.
        if (fakeCall) {
            dropCurrentCall()
            fakeCall = false
            Ringer.stop()
            appContext?.let { WaCallService.tryStop(it) }
            return
        }
        Ringer.stop()
        scope.launch { runCatching { bridgeOrThrow().rejectCall() } }
    }

    /** Dev design aid: fake incoming *video* offer (the emulator path for
     *  the answer flow's CAMERA request — a real video offer needs a real
     *  caller; the audio demo covers the plain answer). */
    fun debugIncomingVideoCall() {
        fakeCall = true
        onEvent(WaEvent.IncomingCall(peer = "15550102030", isVideo = true))
    }

    /** Fire-and-forget hangup of the active call — the `CallEnded` event
     *  routes the UI back and tears the audio plane down. */
    fun endCall() {
        // Fake call: nothing to terminate — end the flow locally.
        if (fakeCall) {
            dropCurrentCall()
            fakeCall = false
            CallTone.playDisconnect()
            appContext?.let { WaCallService.tryStop(it) }
            // Direct publish — the reducer's CallEnded branch doesn't run.
            _pendingOffer.value = null
            publish(WaEvent.CallEnded(reason = "ended"))
            return
        }
        scope.launch {
            runCatching { bridgeOrThrow().terminate() }
                .onFailure { Log.w(TAG, "terminate failed: ${it.message}") }
                .onSuccess { Log.i(TAG, "terminate ok") }
        }
    }

    fun setSpeaker(speaker: Boolean) {
        if (fakeCall) return
        val context = appContext ?: return
        WaCallService.setSpeaker(context, speaker)
    }

    private fun bridgeOrThrow(): WaBridge =
        bridge ?: throw IllegalStateException("WhatsApp is not started")

    /** CAMERA runtime grant, read off the app context. The screens request
     *  it through the SDK launcher (mirroring the mic flow); this is the
     *  server-side backstop that keeps a camera open from ever happening
     *  without the grant. */
    private fun cameraGranted(): Boolean =
        appContext?.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onEvent(event: WaEvent) {
        when (event) {
            is WaEvent.IncomingCall -> {
                // Glare: with a call already up (peer set), a second offer
                // must not adopt its peer — the UI declines to route it too
                // (HomeViewModel); the bridge-side guard is a follow-up.
                Log.i(TAG, "incoming offer: glare=${currentCallPeer != null}")
                if (currentCallPeer == null) {
                    currentCallPeer = event.peer
                    currentCallOutgoing = false
                    incomingVideoOffer = event.isVideo
                    // The offer home routes from — state, not the event
                    // stream (see [_pendingOffer]).
                    _pendingOffer.value = event
                    // Resolved display peer of the ringing offer — survives
                    // the offer's CallEnded (not-started) so a following
                    // MissedCall logs the real number, not the raw LID.
                    pendingIncomingPeer = event.peer
                    // The phone's ringer (default ringtone on the ring stream;
                    // vibrate-only in vibrate mode, silent in silent mode).
                    appContext?.let { Ringer.start(it) }
                }
            }
            is WaEvent.CallStarted -> {
                // Ghost guard: a CallStarted for a call the engine already
                // dropped (timeout-terminated) must not start audio or route
                // UI as a nameless "call" — the bridge can win an
                // accept-vs-terminate race.
                if (currentCallPeer == null) return
                // Set synchronously (not inside the launch): the CallEnded
                // branch below reads it, and the two launches must not race.
                currentCallStarted = true
                pendingIncomingPeer = null
                // The offer was taken — nothing left to route.
                _pendingOffer.value = null
                currentCallConnectedAt = System.currentTimeMillis()
                Ringer.stop()
                scope.launch {
                    // The answer tap (or, for an outgoing call, the still-open
                    // active-call screen) keeps the app foreground-visible, so
                    // the mic FGS start is foreground-visible (SPEC §3.1). If
                    // the app was backgrounded while ringing, Android blocks
                    // the start: the call connects without a mic FGS (degraded
                    // audio once the screen sleeps) — surface it, never bypass
                    // the restriction.
                    if (appContext?.let { WaCallService.tryStart(it) } != true) {
                        _error.value = "Call connected but microphone unavailable — reopen the app"
                    }
                    runCatching { audioLink().start() }
                    // Video consented before the call started (a video offer
                    // the user answered with video, or an outgoing with-video
                    // placement): the encoder joins the audio plane and the
                    // decode half starts. A raw `VideoStateChanged(1)` alone
                    // never starts the camera — state 1 can arrive at offer
                    // time, before the user opted in.
                    if (videoConsent) {
                        _videoState.value = VideoUiState.On
                        startVideoEncoding()
                        startVideoDecoding()
                    }
                    // Outgoing attempts log at placement; an answered incoming
                    // call logs the moment it comes up. `CallEnded` never logs.
                    if (!currentCallOutgoing) {
                        currentCallPeer?.let { peer ->
                            currentCallLogAt = currentCallConnectedAt
                            callLog?.add(
                                CallLogEntry(
                                    peer = peer,
                                    outgoing = false,
                                    missed = false,
                                    at = currentCallLogAt,
                                ),
                            )
                        }
                    }
                }
            }
            is WaEvent.CallEnded -> {
                // Captured synchronously for the same race reason.
                val started = currentCallStarted
                val connectedFor = if (started) currentCallConnectedAt else 0L
                val logPeer = currentCallPeer
                val logAt = currentCallLogAt
                val logOutgoing = currentCallOutgoing
                currentCallStarted = false
                currentCallConnectedAt = 0
                activeScreenRouted = false
                // The offer (if this ends one) is gone — stop routing it.
                _pendingOffer.value = null
                Ringer.stop()
                scope.launch {
                    // Exactly-once per call (bridge-side dedup) — this is the
                    // single place the audio plane and the log are torn down.
                    // Logging happens at placement / start / missed — the
                    // duration is filled in here, at the connected end.
                    CallTone.playDisconnect()
                    audioLink?.stop()
                    audioLink = null
                    // Release the diagnostics video exercise too, if running.
                    videoLink?.let { videoLink = null; diagnostics = false; _videoExerciseRunning.value = false; it.stop() }
                    // The call's video plane: encode + decode halves, surface,
                    // consent + reduced state — back to a fresh baseline.
                    encodeLink?.let { encodeLink = null; it.stop() }
                    stopVideoDecoding()
                    peerSurface = null
                    videoConsent = false
                    incomingVideoOffer = false
                    _videoState.value = VideoUiState.Off
                    peerOrientation.value = 0
                    appContext?.let { WaCallService.tryStop(it) }
                    if (started && logPeer != null && logAt != 0L) {
                        callLog?.updateDuration(
                            peer = logPeer,
                            at = logAt,
                            outgoing = logOutgoing,
                            duration = (System.currentTimeMillis() - connectedFor) / 1000,
                        )
                    }
                    // A call-surface warning (e.g. the backgrounded mic FGS
                    // start) is obsolete once the call is over.
                    _error.value = null
                    if (!started) pendingIncomingPeer = null
                    currentCallPeer = null
                }
            }
            is WaEvent.MissedCall -> {
                // A caller cancel/expiry ends the offer (never a CallEnded).
                // While a call is live, stray missed notices are ignored —
                // they belong to no ringing offer. The missed event carries
                // the caller's raw LID; log the resolved display peer of the
                // offer it expired (the one the panel showed) when held.
                if (!currentCallStarted) {
                    callLog?.add(
                        CallLogEntry(
                            peer = pendingIncomingPeer ?: event.peer,
                            outgoing = false,
                            missed = true,
                            at = System.currentTimeMillis(),
                        ),
                    )
                    pendingIncomingPeer = null
                    currentCallPeer = null
                    incomingVideoOffer = false
                    activeScreenRouted = false
                    _pendingOffer.value = null
                    Ringer.stop()
                }
            }
            is WaEvent.Paired -> {
                // Only a real pairing (PairSuccess) clears the respawn cap —
                // a mere transport connect fires inside every respawn round.
                if (event.fresh) autoRestarts = 0
            }
            is WaEvent.Unlinked -> {
                // Server-side unlink: the stored registration is revoked and
                // reconnecting loops forever — wipe the store and register
                // fresh. Capped so a hopeless environment can't spin forever.
                if (autoRestarts >= 3) {
                    _error.value = "WhatsApp disconnected — reopen the tool"
                } else {
                    autoRestarts++
                    scope.launch { runCatching { bridgeOrThrow().resetSession() } }
                }
            }
            is WaEvent.Disconnected -> {
                // Run loop died while the session was still valid: respawn
                // on the intact store (same cap).
                if (autoRestarts >= 3) {
                    _error.value = "WhatsApp disconnected — reopen the tool"
                } else {
                    autoRestarts++
                    scope.launch { runCatching { restartPairing() } }
                }
            }
            is WaEvent.CallLogImport -> {
                // Pairing history sync + live app-state records — the same
                // call history web.whatsapp shows. A full-sync replay emits
                // one event per record, so buffer briefly and flush once.
                synchronized(pendingImports) {
                    pendingImports += event.entries.map {
                        CallLogEntry(
                            peer = it.peer,
                            outgoing = it.outgoing,
                            missed = it.missed,
                            at = it.at.toLong(),
                            duration = it.duration.toLong(),
                            answeredElsewhere = it.answeredElsewhere,
                        )
                    }
                }
                if (!importFlushScheduled) {
                    importFlushScheduled = true
                    scope.launch {
                        kotlinx.coroutines.delay(500)
                        val batch: List<CallLogEntry>
                        synchronized(pendingImports) {
                            batch = pendingImports.toList()
                            pendingImports.clear()
                            importFlushScheduled = false
                        }
                        callLog?.addAll(batch)
                    }
                }
            }
            is WaEvent.ContactsImport -> {
                val list = event.contacts.map { ContactEntry(id = it.id, name = it.name) }
                when {
                    event.replace -> contacts?.replaceAll(list)
                    event.renameOnly -> contacts?.rename(list)
                    else -> contacts?.merge(list)
                }
            }
            is WaEvent.VideoStateChanged -> {
                Log.i(TAG, "video state: ${event.state}")
                val next = reduceVideoState(event.state, event.upgrade)
                _videoState.value = next
                scope.launch {
                    if (next is VideoUiState.Off) {
                        // The video plane went down (peer stop, upgrade
                        // reject/cancel/timeout, our stop): consent falls
                        // with it — the glare-cancel contract says no video
                        // signaling outlives the negotiation, and a later
                        // enable is always an explicit user action.
                        videoConsent = false
                        stopVideoEncoding()
                        stopVideoDecoding()
                    } else {
                        // Video On with the UI's surface already attached —
                        // the decode half starts as soon as both hold.
                        startVideoDecoding()
                    }
                }
            }
            is WaEvent.OutboundVideoDropped -> {
                Log.i(TAG, "outbound video dropped: ${event.accessUnits}")
                // The relay shed OUR outbound AUs before transmission — the
                // peer lost reference data, so the encoder's next frame must
                // be an IDR (same signal the local overflow path carries).
                encodeLink?.requestSyncFrame()
                videoLink?.requestSyncFrame()
            }
            is WaEvent.KeyframePressure -> {
                // Peer PLI/FIR (relayed): the engine sheds non-IDR AUs on its
                // own; the encoder can answer with a sync frame ahead of the
                // 1 s GOP.
                Log.i(TAG, "keyframe pressure")
                encodeLink?.requestSyncFrame()
                videoLink?.requestSyncFrame()
            }
            else -> Unit
        }
        publish(event)
    }

    private fun audioLink(): AudioLink {
        audioLink?.let { return it }
        synchronized(this) {
            audioLink?.let { return it }
            return AudioLink(bridgeOrThrow()).also { audioLink = it }
        }
    }

    /** [VideoLink]'s camera-manager lookup — the engine owns the context. */
    fun appContextForVideoLink(): Context =
        appContext ?: throw IllegalStateException("engine not attached")

    // Video plane (V2). The encode half ([VideoLink]) and decode half
    // ([VideoDecodeLink]) run per-call; their lifecycle hangs off the video
    // state: the encode side starts on `startVideo`/`acceptVideo` (and on
    // `CallStarted` when video was negotiated up front) and stops on
    // Off-reducing `VideoStateChanged`/`stopVideo`/`CallEnded`; the decode
    // side starts when the UI attaches its surface while video is On.

    /** The call's outbound encoder — distinct from the diagnostics exercise
     *  [videoLink] (which only ever runs call-free). */
    @Volatile
    private var encodeLink: VideoLink? = null

    /** The peer-video decoder rendering into the UI's surface. */
    @Volatile
    private var decodeLink: VideoDecodeLink? = null

    /** The UI's peer-video surface while the active-call screen shows it. */
    @Volatile
    private var peerSurface: Surface? = null

    /** The front camera's `SENSOR_ORIENTATION` in 90° steps, as last reported
     *  by a live [VideoLink] — fallback for [videoOrientationSignal] when the
     *  camera-manager read there fails. */
    @Volatile
    private var reportedSensorSteps: Int? = null

    /** The signaled-orientation mapping: the front sensor's 90° steps as-is.
     *  CALIBRATION (live-call retest pending): if the peer's video comes back
     *  180° off (upside-down), the inverse convention applies — change the
     *  body to `(4 - front) % 4` here; nothing else moves. */
    private fun signaledOrientationSteps(front: Int): UByte = front.mod(4).toUByte()

    /** The `device_orientation` (90° steps 0..3) signaled to the peer on
     *  outbound `<video>` stanzas: the front camera's `SENSOR_ORIENTATION`
     *  read statically (no camera open, not CAMERA-gated), so it is known
     *  before any stanza goes out; the camera report is only a fallback. */
    private fun videoOrientationSignal(): UByte {
        val front = VideoLink.frontSensorOrientationSteps() ?: reportedSensorSteps ?: 0
        return signaledOrientationSteps(front)
    }

    /** User-level video actions (the UI toggle / upgrade prompt). All
     *  fire-and-forget on the engine scope; failures surface through
     *  [_error] and the reduced video state. */

    /** Starts our video direction (mid-call upgrade we initiate): the
     *  bridge's fps-paced media plane plus the local encoder. */
    fun startVideo() {
        scope.launch {
            try {
                bridgeOrThrow().setVideoOrientation(videoOrientationSignal())
                bridgeOrThrow().startVideo(videoTier.fps.toUInt())
            } catch (e: Exception) {
                Log.w(TAG, "startVideo failed: ${e.message}")
                _videoState.value = VideoUiState.Off
                _error.value = "video failed"
                return@launch
            }
            startVideoEncoding()
            videoConsent = true
            _videoState.value = VideoUiState.On
        }
    }

    /** Accepts the peer's upgrade request (the prompt's accept tap): the
     *  echoed token, then the standalone `<video state=1>` the protocol
     *  wants after a mid-call upgrade completes. */
    fun acceptVideo() {
        val token = (_videoState.value as? VideoUiState.UpgradeRequested)?.token ?: return
        scope.launch {
            val ok = runCatching {
                bridgeOrThrow().setVideoOrientation(videoOrientationSignal())
                bridgeOrThrow().acceptVideo(token, videoTier.fps.toUInt())
                bridgeOrThrow().announceVideoEnabled()
            }.onFailure { Log.w(TAG, "acceptVideo failed: ${it.message}") }.isSuccess
            if (ok) {
                startVideoEncoding()
                videoConsent = true
                _videoState.value = VideoUiState.On
            } else {
                _videoState.value = VideoUiState.Off
                _error.value = "video failed"
            }
        }
    }

    /** Declines the peer's upgrade request (the prompt's decline tap):
     *  signals video-off — the bridge has no separate reject, and
     *  `stopVideo` (state 6) is the wire's "not doing video" answer. */
    fun declineVideo() {
        _videoState.value = VideoUiState.Off
        scope.launch { runCatching { bridge?.stopVideo() } }
    }

    /** Stops our video direction (toggle off): encoder and decoder down
     *  first (the decoder is idempotent — a relayed local state change would
     *  also stop it, but don't rely on the relay), then the bridge
     *  signaling. Idempotent per the library. */
    fun stopVideo() {
        videoConsent = false
        _videoState.value = VideoUiState.Off
        scope.launch {
            runCatching { bridge?.stopVideo() }
            stopVideoEncoding()
            stopVideoDecoding()
        }
    }

    /** The UI's peer-video surface. `null` detaches (the SurfaceView went
     *  away — screen left / surface destroyed): the decode link stops
     *  synchronously so no in-flight `releaseOutputBuffer(render)` can race
     *  the framework's surface teardown. A non-null attach stays async. */
    fun attachPeerSurface(surface: Surface?) {
        if (surface == null) {
            peerSurface = null
            stopVideoDecoding()
            return
        }
        peerSurface = surface
        if (_videoState.value is VideoUiState.On) startVideoDecoding()
    }

    private fun startVideoEncoding() {
        if (encodeLink != null || fakeCall) return
        val bridge = bridge ?: return
        // Backstop behind the screens' CAMERA request flows: no grant, no
        // camera open — video degrades honestly (Off + error line), audio
        // unaffected. The mid-call upgrade caller sees the same flash.
        if (!cameraGranted()) {
            _videoState.value = VideoUiState.Off
            _error.value = "camera permission needed"
            return
        }
        // Camera use is about to start: the call FGS claims its camera type
        // now, while the grant is in hand (Android 14 while-in-use rule).
        appContext?.let { WaCallService.elevateToCamera(it) }
        val link = VideoLink(
            sink = { au -> runCatching { bridge.sendVideoAu(au) }.getOrDefault(false) },
            onLost = { reason -> onVideoEncodeLost(reason) },
            onSensorOrientation = { steps ->
                reportedSensorSteps = steps
                // The live handle is already attached here — keep it in sync
                // so any outbound stanza from now on carries the value.
                runCatching { bridge.setVideoOrientation(videoOrientationSignal()) }
            },
        )
        link.start()
        encodeLink = link
    }

    /** Camera loss / late start failure (V1-review carry-over): surface it
     *  honestly — video state off (the UI's toggle un-underlines), an error
     *  line, and the peer told we stopped. The decoder stops too (idempotent;
     *  don't rely on the relayed local state change). */
    private fun onVideoEncodeLost(reason: String) {
        Log.w(TAG, "video encode lost: $reason")
        encodeLink = null
        _videoState.value = VideoUiState.Off
        _error.value = "camera unavailable — video stopped"
        stopVideoDecoding()
        scope.launch { runCatching { bridge?.stopVideo() } }
    }

    private fun stopVideoEncoding() {
        encodeLink?.let { link -> encodeLink = null; link.stop() }
    }

    private fun stopVideoDecoding() {
        decodeLink?.let { link -> decodeLink = null; link.stop() }
    }

    private fun startVideoDecoding() {
        synchronized(this) {
            if (decodeLink != null || fakeCall) return
            val surface = peerSurface ?: return
            val bridge = bridge ?: return
            val link = VideoDecodeLink(
                output = surface,
                pull = { bridge.recvVideoFrame(50u) },
                onOrientation = { peerOrientation.value = it },
                onEnded = { reason ->
                    Log.w(TAG, "video decode ended: $reason")
                    decodeLink = null
                },
            )
            link.start()
            decodeLink = link
        }
    }

    /** Maps a raw `VideoState` wire int onto [VideoUiState] (see the enum's
     *  doc for the table). An upgrade request without a token can't be
     *  answered — it degrades to Off instead of an unanswerable prompt. */
    private fun reduceVideoState(state: Int, upgrade: WaVideoUpgradeToken?): VideoUiState = when (state) {
        1, 4 -> VideoUiState.On // Enabled, UpgradeAccept
        3, 11 -> upgrade?.let { VideoUiState.UpgradeRequested(it) } ?: VideoUiState.Off
        else -> VideoUiState.Off
    }

    // Diagnostics exercises (no call, no traffic): the encoder exercise runs
    // the H.264 encoder standalone behind the runtime opt-in toggle
    // (Settings); the decode exercise replays its archived AU capture
    // through the real decode half into a test screen's SurfaceView. The
    // bridge consumes nothing without a call, so the encoder's AU stream
    // flows into its drop-oldest queue only; the capture file beside it
    // (`video-exercise.h264`) holds the pushed AUs.

    /** Runtime opt-in verbose diagnostics — OFF by default (battery rule; the
     *  LP3 runs DEBUGGABLE APKs so BuildConfig.DEBUG is useless). */
    @Volatile
    var diagnostics: Boolean = false
        private set

    @Volatile
    private var videoLink: VideoLink? = null

    private val _videoExerciseRunning = MutableStateFlow(false)
    val videoExerciseRunning: StateFlow<Boolean> = _videoExerciseRunning

    /** Diagnostic note line (camera loss, capture result) — Settings and the
     *  decode-test screen render it instead of a stale "Recording". */
    val exerciseNote = MutableStateFlow<String?>(null)

    /** Toggles the encoder exercise. Refused while a call is live — the
     *  encoder is released on `CallEnded` too (the reducer's teardown branch). */
    fun toggleVideoExercise(): Boolean {
        val link = videoLink
        if (link != null) {
            videoLink = null
            diagnostics = false
            _videoExerciseRunning.value = false
            link.stop()
            return false
        }
        if (currentCallPeer != null || currentCallStarted) return videoExerciseRunning.value
        val bridge = bridge ?: return videoExerciseRunning.value
        val dump = appContextForVideoLink().filesDir.resolve("video-exercise.h264")
        dump.delete()
        val started = VideoLink(
            sink = { au ->
                val dropped = runCatching { bridge.sendVideoAu(au) }.getOrDefault(false)
                // Without a live call the bridge's queue has no consumer, so
                // its drop-oldest overflow is not a lost reference chain —
                // don't ask the encoder for sync frames over it. (A real call
                // passes the same signal through verbatim.)
                dropped && currentCallStarted
            },
            dumpFile = dump,
            onLost = { reason ->
                // V1-review carry-over: camera loss must not leave Settings
                // claiming "Recording" — stop and surface the note.
                videoLink = null
                diagnostics = false
                _videoExerciseRunning.value = false
                exerciseNote.value = "encoder test stopped: $reason"
            },
        )
        return runCatching {
            started.start()
            videoLink = started
            diagnostics = true
            _videoExerciseRunning.value = true
            exerciseNote.value = null
        }
            .map { true }
            .getOrElse {
                Log.w(TAG, "video exercise failed to start: ${it.message}")
                false
            }
    }

    @Volatile
    private var decodeExercise: VideoDecodeLink? = null

    val decodeExerciseRunning: Boolean
        get() = decodeExercise != null

    /** Starts the decode exercise: replays the encoder test's archived AU
     *  capture at 15 fps through the real decode half into [surface]. */
    fun startDecodeExercise(surface: Surface) {
        synchronized(this) {
            Log.i(TAG, "decode exercise requested: running=${decodeExercise != null} peer=${currentCallPeer != null}")
            if (decodeExercise != null) return
            if (currentCallPeer != null || currentCallStarted) {
                exerciseNote.value = "refused — a call is live"
                return
            }
            val file = appContextForVideoLink().filesDir.resolve("video-exercise.h264")
            if (!file.exists()) {
                exerciseNote.value = "no capture — run the encoder test first"
                return
            }
            val aus = runCatching { parseAnnexBAus(file.readBytes()) }
                .getOrElse {
                    Log.w(TAG, "decode exercise: capture unreadable: ${it.message}")
                    exerciseNote.value = "capture unreadable"
                    return
                }
            if (aus.isEmpty()) {
                exerciseNote.value = "capture is empty"
                return
            }
            exerciseNote.value = null
            val total = aus.size
            val index = AtomicInteger(0)
            val startedAt = System.nanoTime()
            val frameNs = 1_000_000_000L / VideoTier.DEFAULT.fps
            val link = VideoDecodeLink(
                output = surface,
                pull = {
                    val i = index.getAndIncrement()
                    if (i >= total) {
                        if (i == total) exerciseNote.value = "capture finished — $total AUs"
                        null
                    } else {
                        // Pace at the capture's fps so the render looks like
                        // the recorded stream instead of a burst.
                        val waitNs = startedAt + i * frameNs - System.nanoTime()
                        if (waitNs > 0) Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
                        val au = aus[i]
                        WaVideoFrame(au.data, au.keyframe, 0u)
                    }
                },
                onEnded = { reason ->
                    decodeExercise = null
                    exerciseNote.value = reason
                },
                logHeartbeat = true,
            )
            link.start()
            decodeExercise = link
            Log.i(TAG, "decode exercise started: $total AUs")
        }
    }

    fun stopDecodeExercise() {
        decodeExercise?.let { decodeExercise = null; it.stop() }
    }
}
