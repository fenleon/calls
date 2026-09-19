package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ActiveCallViewModel : LightViewModel<Unit>() {
    /** Seconds since the call connected — reset when `CallStarted` lands
     *  (an outgoing call first sits in the ringing phase). */
    val elapsedSeconds = MutableStateFlow(0L)
    /** True once `CallStarted` arrived — false shows "Ringing…" (F1). An
     *  accepted incoming call replays its `CallStarted` through the engine's
     *  StateFlow and connects immediately. */
    val connected = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private var startedAt = Instant.now()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                elapsedSeconds.value = Duration.between(startedAt, Instant.now()).seconds
            }
        }
    }

    /** The peer answered (outgoing) or the replayed event confirmed the
     *  accepted incoming call: the ticking duration starts from now. */
    fun onStarted() {
        if (connected.value) return
        connected.value = true
        startedAt = Instant.now()
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

/** The live call: peer, ringing phase (outgoing, until `CallStarted`), ticking
 *  duration once connected, mute and speaker toggles, end. `CallEnded`
 *  (exactly-once, bridge-deduped) returns to the call log — the engine has
 *  already torn down the audio plane, the service and the log. */
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
        val elapsed by viewModel.elapsedSeconds.collectAsState()
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

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("Call"))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(text = peerLabel(peer), variant = LightTextVariant.Heading)
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        if (connected) {
                            LightText(text = formatElapsed(elapsed), variant = LightTextVariant.Copy)
                        } else {
                            LightText(text = "Ringing…", variant = LightTextVariant.Copy)
                        }
                        Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Toggle(label = if (muted) "UNMUTE" else "MUTE", onClick = viewModel::toggleMute)
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            Toggle(
                                label = if (speaker) "EARPIECE" else "SPEAKER",
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
                        LightBarButton.Text(
                            text = "END",
                            onClick = { viewModel.end() },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun Toggle(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Button)
    }
}

private fun formatElapsed(seconds: Long): String =
    "%02d:%02d".format(seconds / 60, seconds % 60)
