package com.lightphone.calls.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class IncomingCallViewModel : LightViewModel<Unit>() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    // Increments on every tap that finds the mic ungranted, so each tap
    // re-arms the permission request even after a denial.
    val permissionRequestTick = MutableStateFlow(0)
    // Same re-arm pattern for CAMERA on a video offer (a video answer opens
    // our camera too, so the grant must be in hand before the accept).
    val cameraRequestTick = MutableStateFlow(0)

    fun onAnswerTapped() {
        if (busy.value) return
        viewModelScope.launch {
            when {
                !micPermissionGranted() -> permissionRequestTick.value += 1
                // Video offer: the CAMERA grant rides the answer flow (same
                // tap-twice grammar as the mic). A denial keeps asking on
                // the next tap — the accept only proceeds with the grant;
                // the engine answers audio-only if it ever sees it ungranted.
                WaCallEngine.incomingVideoOffer && !cameraPermissionGranted() ->
                    cameraRequestTick.value += 1
                else -> {
                    busy.value = true
                    try {
                        // On success `CallStarted` routes to the active-call
                        // screen (observed below); on failure stay and say so.
                        WaCallEngine.acceptCall()
                    } catch (e: Exception) {
                        error.value = "could not answer"
                        busy.value = false
                    }
                }
            }
        }
    }
}

/** An incoming 1:1 WhatsApp offer. Answer asks for the mic, starts nothing
 *  itself — the engine starts the call service and the audio link on
 *  `CallStarted` — and routes there through home once the call is up. */
class IncomingCallScreen(
    sealedActivity: SealedLightActivity,
    private val peer: String,
) : LightScreen<Unit, IncomingCallViewModel>(sealedActivity) {

    override val viewModelClass: Class<IncomingCallViewModel>
        get() = IncomingCallViewModel::class.java

    override fun createViewModel(): IncomingCallViewModel = IncomingCallViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val error by viewModel.error.collectAsState()
        val permissionRequestTick by viewModel.permissionRequestTick.collectAsState()
        val cameraRequestTick by viewModel.cameraRequestTick.collectAsState()
        // Peer gave up (CallEnded): flash the native "call ended" panel
        // before the log returns (PHONE-PANELS.md §5.3).
        var ended by remember { mutableStateOf(false) }
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)
        val cameraLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)

        // The call coming up (accept → `CallStarted`) is routed by home's
        // always-alive ViewModel via `LightGlobalRoute` (clearToRoot — this
        // panel is replaced by the active-call screen); a caller cancel or
        // expiry arrives as `MissedCall`, never `CallEnded` — close quietly.
        // Live events only: the `events` StateFlow replays its last value to
        // a fresh collector — a stale `CallEnded` from a call that died while
        // the app was closed flashed "call ended" here and never left
        // (2026-09-24 LP3 report).
        LaunchedEffect(Unit) {
            WaCallEngine.liveEvents.collect { event ->
                when (event) {
                    is WaEvent.MissedCall -> if (event.peer == peer) goBack()
                    is WaEvent.CallEnded -> ended = true
                    else -> Unit
                }
            }
        }
        // The panel's liveness anchor is the pending offer itself, not an
        // event: the live stream has no replay, so an offer that died in the
        // route→composition gap delivers nothing to close with. When the
        // engine no longer holds this offer AND no accepted call is replacing
        // this panel (accept → `CallStarted` clears the offer while the route
        // to the active screen replaces us — a blind goBack there could pop
        // the call panel instead), the panel is stale and closes.
        val pendingOffer by WaCallEngine.pendingOffer.collectAsState()
        LaunchedEffect(pendingOffer) {
            if (pendingOffer == null && WaCallEngine.currentCallPeer == null) goBack()
        }
        LaunchedEffect(permissionRequestTick) {
            if (permissionRequestTick > 0) permissionLauncher?.launch()
        }
        LaunchedEffect(cameraRequestTick) {
            if (cameraRequestTick > 0) cameraLauncher?.launch()
        }
        // Leaving the ringing panel without answering (back out) declines —
        // otherwise the ringer keeps looping with no UI left to stop it.
        // Also fires on accept (the panel is replaced): by then the call has
        // started, so [WaCallEngine.ringing] is false and this is a no-op.
        DisposableEffect(Unit) {
            onDispose { if (WaCallEngine.ringing) WaCallEngine.reject() }
        }

        // Native Phone incoming grammar (calls/PHONE-PANELS.md §1/§5):
        // "incoming..." status line (copy, lighten) above the peer
        // (subtitle/heading adaptive; the native passes truncateTitle:false —
        // no truncation here); × decline bottom-left, △ accept bottom-right
        // (the SDK's ACCEPT glyph is the native's own drawable).
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    // Feedback 2026-09-22: the peer sits ~vertical center with
                    // "incoming..." above it (was pinned to the native 384px
                    // top offset).
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = "incoming...",
                            variant = LightTextVariant.Copy,
                        )
                        Spacer(modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()))
                        val label = callPeerLabel(peer)
                        LightText(
                            text = label,
                            variant = if (label.length <= PEER_FIT_CHARS) {
                                LightTextVariant.Subtitle
                            } else {
                                LightTextVariant.Heading
                            },
                        )
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = {
                                WaCallEngine.reject()
                                goBack()
                            },
                            contentDescription = "Decline",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.ACCEPT,
                            onClick = { viewModel.onAnswerTapped() },
                            contentDescription = if (busy) "Answering" else "Answer",
                        ),
                    ),
                )
            }
                // Sibling of the screen Column (not a child): the flash is a
                // true fullscreen overlay — no chrome visible around it.
                ErrorFlash(
                    message = if (ended) "call ended" else error,
                    onDismiss = {
                        if (ended) {
                            goBack()
                        } else {
                            viewModel.error.value = null
                        }
                    },
                )
            }
        }
    }
}
