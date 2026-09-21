package com.lightphone.calls.server

import android.content.Context
import android.util.Log
import com.lightphone.calls.server.bridge.WaBridge
import com.lightphone.calls.server.bridge.WaEvent
import com.lightphone.calls.server.bridge.WaListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object WaCallEngine : WaListener {
    private const val TAG = "WaCallEngine"

    private val _events = MutableStateFlow<WaEvent?>(null)
    val events: StateFlow<WaEvent?> = _events

    /** Engine-level failure the UI renders as a small error line (the bridge
     *  process store failed to open, a call connected with the mic FGS start
     *  blocked while backgrounded, …). Cleared on a successful start and
     *  when a call ends. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

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
        activeScreenRouted = false
    }

    fun attach(context: Context) {
        if (bridge != null) return
        synchronized(this) {
            if (bridge != null) return
            val app = context.applicationContext
            appContext = app
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

    fun version(): String = bridge?.version() ?: "not-started"

    /** Own account, once known — a restored session reads it off the session
     *  store at attach time; a fresh pairing gets it with `Paired`. */
    fun account(): String? = bridge?.account()

    /** The persisted call log, newest last — rendered by the home screen. */
    fun callLogEntries(): List<CallLogEntry> = callLog?.all() ?: emptyList()

    /** Imported peer contacts (history sync) — rendered by the contacts
     *  screen alongside call-log peers. */
    fun contactEntries(): List<ContactEntry> = contacts?.all() ?: emptyList()

    // Call control — thin wrappers over the bridge's async surface, called
    // from the screens' ViewModels. The bridge throws WaException on failure;
    // the caller renders it, nothing is logged (peer data / reasons).

    /** Places a 1:1 call to a bare phone number (E.164 `+` ok) or full JID.
     *  The attempt is logged instantly (the user sees the call in the log
     *  while it still connects) — `CallEnded` never logs. */
    suspend fun placeCall(phoneOrJid: String) {
        currentCallPeer = phoneOrJid
        currentCallOutgoing = true
        callLog?.add(
            CallLogEntry(
                peer = phoneOrJid,
                outgoing = true,
                missed = false,
                at = System.currentTimeMillis(),
            ),
        )
        try {
            bridgeOrThrow().placeCall(phoneOrJid)
        } catch (e: Exception) {
            // No call came up — drop the peer so the next `CallEnded` (or a
            // glare-suppressed offer) isn't logged against this attempt.
            currentCallPeer = null
            currentCallStarted = false
            throw e
        }
    }

    suspend fun acceptCall() {
        // Fake call (dev design aid): no bridge to accept — the CallStarted
        // lands directly on the event flow and the screens run for real.
        if (fakeCall) {
            _events.value = WaEvent.CallStarted(callId = "fake")
            return
        }
        bridgeOrThrow().acceptCall()
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
        // Fake call: the screen closes itself; just release the call state.
        if (fakeCall) {
            dropCurrentCall()
            fakeCall = false
            return
        }
        scope.launch { runCatching { bridgeOrThrow().rejectCall() } }
    }

    /** Fire-and-forget hangup of the active call — the `CallEnded` event
     *  routes the UI back and tears the audio plane down. */
    fun endCall() {
        // Fake call: nothing to terminate — end the flow locally.
        if (fakeCall) {
            dropCurrentCall()
            fakeCall = false
            CallTone.playDisconnect()
            _events.value = WaEvent.CallEnded(reason = "ended")
            return
        }
        scope.launch { runCatching { bridgeOrThrow().terminate() } }
    }

    fun setSpeaker(speaker: Boolean) {
        if (fakeCall) return
        val context = appContext ?: return
        WaCallService.setSpeaker(context, speaker)
    }

    private fun bridgeOrThrow(): WaBridge =
        bridge ?: throw IllegalStateException("WhatsApp is not started")

    override fun onEvent(event: WaEvent) {
        when (event) {
            is WaEvent.IncomingCall -> {
                // Glare: with a call already up (peer set), a second offer
                // must not adopt its peer — the UI declines to route it too
                // (HomeViewModel); the bridge-side guard is a follow-up.
                if (currentCallPeer == null) {
                    currentCallPeer = event.peer
                    currentCallOutgoing = false
                    // Resolved display peer of the ringing offer — survives
                    // the offer's CallEnded (not-started) so a following
                    // MissedCall logs the real number, not the raw LID.
                    pendingIncomingPeer = event.peer
                }
            }
            is WaEvent.CallStarted -> {
                // Set synchronously (not inside the launch): the CallEnded
                // branch below reads it, and the two launches must not race.
                currentCallStarted = true
                pendingIncomingPeer = null
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
                    // Outgoing attempts log at placement; an answered incoming
                    // call logs the moment it comes up. `CallEnded` never logs.
                    if (!currentCallOutgoing) {
                        currentCallPeer?.let { peer ->
                            callLog?.add(
                                CallLogEntry(
                                    peer = peer,
                                    outgoing = false,
                                    missed = false,
                                    at = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
            }
            is WaEvent.CallEnded -> {
                // Captured synchronously for the same race reason.
                val started = currentCallStarted
                currentCallStarted = false
                activeScreenRouted = false
                scope.launch {
                    // Exactly-once per call (bridge-side dedup) — this is the
                    // single place the audio plane and the log are torn down.
                    // Logging happens at placement / start / missed — never
                    // here.
                    CallTone.playDisconnect()
                    audioLink?.stop()
                    audioLink = null
                    appContext?.let { WaCallService.tryStop(it) }
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
                    activeScreenRouted = false
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
                contacts?.merge(event.contacts.map { ContactEntry(id = it.id, name = it.name) })
            }
            else -> Unit
        }
        _events.value = event
    }

    private fun audioLink(): AudioLink {
        audioLink?.let { return it }
        synchronized(this) {
            audioLink?.let { return it }
            return AudioLink(bridgeOrThrow()).also { audioLink = it }
        }
    }
}
