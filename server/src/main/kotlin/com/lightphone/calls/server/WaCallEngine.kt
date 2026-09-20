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

    fun attach(context: Context) {
        if (bridge != null) return
        synchronized(this) {
            if (bridge != null) return
            val app = context.applicationContext
            appContext = app
            callLog = CallLogStore(app.filesDir)
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

    // Call control — thin wrappers over the bridge's async surface, called
    // from the screens' ViewModels. The bridge throws WaException on failure;
    // the caller renders it, nothing is logged (peer data / reasons).

    /** Places a 1:1 call to a bare phone number (E.164 `+` ok) or full JID. */
    suspend fun placeCall(phoneOrJid: String) {
        currentCallPeer = phoneOrJid
        currentCallOutgoing = true
        try {
            bridgeOrThrow().placeCall(phoneOrJid)
        } catch (e: Exception) {
            // No call came up — drop the peer so the next `CallEnded` (or a
            // glare-suppressed offer) isn't logged against this attempt.
            currentCallPeer = null
            throw e
        }
    }

    suspend fun acceptCall() = bridgeOrThrow().acceptCall()

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

    suspend fun setMuted(muted: Boolean) = bridgeOrThrow().setMuted(muted)

    /** Fire-and-forget decline of the incoming offer — runs on the engine's
     *  own scope so the decline is sent even if the screen leaves first. */
    fun reject() {
        scope.launch { runCatching { bridgeOrThrow().rejectCall() } }
    }

    /** Fire-and-forget hangup of the active call — the `CallEnded` event
     *  routes the UI back and tears the audio plane down. */
    fun endCall() {
        scope.launch { runCatching { bridgeOrThrow().terminate() } }
    }

    fun setSpeaker(speaker: Boolean) {
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
                }
            }
            is WaEvent.CallStarted -> scope.launch {
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
            }
            is WaEvent.CallEnded -> scope.launch {
                // Exactly-once per call (bridge-side dedup) — this is the
                // single place the audio plane and the log are torn down.
                audioLink?.stop()
                audioLink = null
                appContext?.let { WaCallService.tryStop(it) }
                // A call-surface warning (e.g. the backgrounded mic FGS
                // start) is obsolete once the call is over.
                _error.value = null
                currentCallPeer?.let { peer ->
                    callLog?.add(
                        CallLogEntry(
                            peer = peer,
                            outgoing = currentCallOutgoing,
                            missed = false,
                            at = System.currentTimeMillis(),
                        ),
                    )
                }
                currentCallPeer = null
            }
            is WaEvent.MissedCall -> {
                // An expired offer ends in MissedCall, never CallEnded — drop
                // its peer so the field can't go stale across calls.
                if (currentCallPeer == event.peer) currentCallPeer = null
                callLog?.add(
                    CallLogEntry(
                        peer = event.peer,
                        outgoing = false,
                        missed = true,
                        at = System.currentTimeMillis(),
                    ),
                )
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
