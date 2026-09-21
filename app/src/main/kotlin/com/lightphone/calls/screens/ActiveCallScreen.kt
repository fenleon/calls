package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightBluetooth
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ActiveCallViewModel : LightViewModel<Unit>() {
    /** True once `CallStarted` arrived — false shows "connecting…" (F1). An
     *  accepted incoming call replays its `CallStarted` through the engine's
     *  StateFlow and connects immediately. */
    val connected = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** The peer answered (outgoing) or the replayed event confirmed the
     *  accepted incoming call. */
    fun onStarted() {
        connected.value = true
    }

    /** Outgoing placement, run by the call panel the dial tap opened (the
     *  native shows the panel immediately, not after the offer lands). The
     *  bridge's `placeCall` never returns for an unreachable peer — the
     *  ring wait is raced against a 10 s timeout, then the panel flashes
     *  "call failed" and returns to the dialer. */
    fun place(peer: String) {
        viewModelScope.launch {
            try {
                val routed = withTimeoutOrNull(10_000) {
                    WaCallEngine.placeCall(peer)
                    WaCallEngine.events.first {
                        it is WaEvent.Ringing || it is WaEvent.CallStarted
                    }
                }
                if (routed == null) {
                    WaCallEngine.dropCurrentCall()
                    error.value = "call failed"
                }
            } catch (e: Exception) {
                WaCallEngine.dropCurrentCall()
                error.value = "call failed"
            }
        }
    }

    fun toggleMute() {
        val next = !muted.value
        viewModelScope.launch {
            try {
                WaCallEngine.setMuted(next)
                muted.value = next
            } catch (e: Exception) {
                error.value = "mute failed"
            }
        }
    }

    fun toggleSpeaker() {
        speaker.value = !speaker.value
        WaCallEngine.setSpeaker(speaker.value)
    }

    fun end() = WaCallEngine.endCall()
}

/** The live call: peer, "connecting…" until `CallStarted`, mute and speaker
 *  toggles (underline = on), × to end. `CallEnded` (exactly-once,
 *  bridge-deduped) returns to the call log — the engine has already torn
 *  down the audio plane, the service and the log. */
class ActiveCallScreen(
    sealedActivity: SealedLightActivity,
    private val peer: String,
    /** Outgoing: the dial tap already routed here — place the offer now. */
    private val placeOutgoing: Boolean = false,
) : LightScreen<Unit, ActiveCallViewModel>(sealedActivity) {

    override val viewModelClass: Class<ActiveCallViewModel>
        get() = ActiveCallViewModel::class.java

    override fun createViewModel(): ActiveCallViewModel = ActiveCallViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val connected by viewModel.connected.collectAsState()
        val muted by viewModel.muted.collectAsState()
        val speaker by viewModel.speaker.collectAsState()
        val error by viewModel.error.collectAsState()
        val bluetoothConnected by LightBluetooth.connected.collectAsState()
        // Native "call ended" panel (PHONE-PANELS.md §5.3: the bundle's
        // {title:'call ended', lightenTitle:true} DisplayArea overlay):
        // flashes before the log returns, for hang-up and peer-ended alike.
        var ended by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            if (placeOutgoing) viewModel.place(peer)
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.CallStarted -> viewModel.onStarted()
                    is WaEvent.CallEnded -> ended = true
                    else -> Unit
                }
            }
        }

        // Native Phone call-screen grammar (calls/PHONE-PANELS.md §1/§5):
        // lowercase status line (copy, lighten) above the peer (subtitle,
        // shrinking to heading past the native 22-character-unit fit check),
        // icon toggle row with an underline marking the active one (native
        // order mute · speaker · bluetooth; no dialpad toggle — the bridge
        // has no DTMF), single × hang-up centered at the bottom. No timer,
        // no top bar.
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
                    // Native call-screen geometry (call-connected.xml): the
                    // status line's top lands at 384 px — pinned from the top
                    // rather than centered so the peer and toggles sit where
                    // the native puts them (peer 547, toggles 735).
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier.padding(top = 12.2f.gridUnitsAsDp()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = if (connected) "connected" else "connecting...",
                            variant = LightTextVariant.Copy,
                        )
                        Spacer(modifier = Modifier.padding(top = 2.73f.gridUnitsAsDp()))
                        LightText(
                            text = peerDisplay(peer),
                            variant = if (formatPeer(peer).length <= PEER_FIT_CHARS) {
                                LightTextVariant.Subtitle
                            } else {
                                LightTextVariant.Heading
                            },
                        )
                        Spacer(modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToggleIcon(
                                icon = LightIcons.MUTE,
                                active = muted,
                                contentDescription = if (muted) "Unmute" else "Mute",
                                onClick = viewModel::toggleMute,
                            )
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            ToggleIcon(
                                icon = LightIcons.SPEAKER,
                                active = speaker,
                                contentDescription = if (speaker) "Earpiece" else "Speaker",
                                onClick = viewModel::toggleSpeaker,
                            )
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            ToggleIcon(
                                icon = if (bluetoothConnected) {
                                    LightIcons.BLUETOOTH_CONNECTED
                                } else {
                                    LightIcons.BLUETOOTH
                                },
                                active = bluetoothConnected,
                                contentDescription = if (bluetoothConnected) {
                                    "Bluetooth connected"
                                } else {
                                    "Bluetooth settings"
                                },
                                onClick = { openBluetoothSettings() },
                            )
                        }
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            // Native grammar: the hang-up shows "call ended"
                            // immediately — the terminate runs fire-and-forget
                            // and must not gate the panel on the bridge's
                            // `CallEnded` (which never arrives while the offer
                            // is still placing).
                            onClick = {
                                ended = true
                                viewModel.end()
                            },
                            contentDescription = "End call",
                        ),
                    ),
                )
            }
                // Sibling of the screen Column (not a child): the flash is a
                // true fullscreen overlay — no chrome visible around it.
                ErrorFlash(
                    message = if (ended) "call ended" else error,
                    onDismiss = {
                        viewModel.error.value = null
                        // A failed call returns to the dialer; an ended one
                        // to the log.
                        if (ended || error != null) goBack()
                    },
                )
            }
        }
    }
}

/** An in-call toggle: icon with a bar underneath when active — the native
 *  call screen's underline-means-on grammar. Bar measured off the native
 *  capture (reference/lp3/screens/phone/call-connected.png): ≈80 px wide
 *  (icon width) and ≈10 px thick — 1.8gu × 0.2gu. */
@Composable
private fun ToggleIcon(
    icon: LightIconConfiguration,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightIcon(icon = icon, contentDescription = contentDescription)
        Box(
            modifier = Modifier
                .width(2.35f.gridUnitsAsDp())
                .height(0.3f.gridUnitsAsDp())
                .background(
                    if (active) {
                        LightThemeTokens.colors.content
                    } else {
                        Color.Transparent
                    },
                ),
        )
    }
}

/** The native call screen's peer fit check: `Subtitle` while the formatted
 *  number fits the 22-character budget, `Heading` past it (the native §5.3
 *  rule). ActiveCall tail-truncates; incoming shows the full label. Status
 *  lines stay `Copy` (smaller than the peer), flashes ride `Subtitle`. */
internal const val PEER_FIT_CHARS = 22

internal fun peerDisplay(peer: String): String {
    val label = formatPeer(peer)
    return if (label.length > PEER_FIT_CHARS) {
        label.take(PEER_FIT_CHARS) + "…"
    } else {
        label
    }
}
