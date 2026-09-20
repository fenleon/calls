package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

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

    fun toggleMute() {
        val next = !muted.value
        viewModelScope.launch {
            try {
                WaCallEngine.setMuted(next)
                muted.value = next
            } catch (e: Exception) {
                error.value = "Mute failed"
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

        LaunchedEffect(Unit) {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.CallStarted -> viewModel.onStarted()
                    is WaEvent.CallEnded -> goBack()
                    else -> Unit
                }
            }
        }

        // Native Phone call-screen grammar (reference/lp3/screens/phone/
        // call-*.png): lowercase status line above a large centered peer,
        // icon toggles with an underline marking the active one, single ×
        // hang-up centered at the bottom. No timer, no top bar.
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(
                            text = if (connected) "connected" else "connecting…",
                            variant = LightTextVariant.Superfine,
                        )
                        Spacer(modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                        LightText(text = peerLabel(peer), variant = LightTextVariant.Title)
                        Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
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
                        }
                        error?.let { message ->
                            Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                            LightText(
                                text = message,
                                variant = LightTextVariant.Superfine,
                                align = TextAlign.Center,
                            )
                        }
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { viewModel.end() },
                            contentDescription = "End call",
                        ),
                    ),
                )
            }
        }
    }
}

/** An in-call toggle: icon with a bar underneath when active — the native
 *  call screen's underline-means-on grammar. */
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
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightIcon(icon = icon, contentDescription = contentDescription)
        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
        Box(
            modifier = Modifier
                .width(2f.gridUnitsAsDp())
                .height(0.5f.gridUnitsAsDp())
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
