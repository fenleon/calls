package com.lightphone.calls.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    fun onAnswerTapped() {
        if (busy.value) return
        viewModelScope.launch {
            if (micPermissionGranted()) {
                busy.value = true
                try {
                    // On success `CallStarted` routes to the active-call
                    // screen (observed below); on failure stay and say so.
                    WaCallEngine.acceptCall()
                } catch (e: Exception) {
                    error.value = "Could not answer"
                    busy.value = false
                }
            } else {
                permissionRequestTick.value += 1
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
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)

        // The peer gave up (CallEnded) or the call came up elsewhere: back to
        // the call log. CallStarted routes to the active call via home.
        LaunchedEffect(Unit) {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.CallStarted, is WaEvent.CallEnded -> goBack()
                    else -> Unit
                }
            }
        }
        LaunchedEffect(permissionRequestTick) {
            if (permissionRequestTick > 0) permissionLauncher?.launch()
        }

        // Native Phone incoming grammar (reference/lp3/screens/phone/
        // call-incoming.png): "incoming…" status line above a large centered
        // peer; × decline bottom-left, answer bottom-right. (Native uses a
        // triangle for answer; the SDK icon set has the handset instead.)
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LightText(
                            text = "incoming…",
                            variant = LightTextVariant.Superfine,
                        )
                        Spacer(modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                        LightText(text = peerLabel(peer), variant = LightTextVariant.Title)
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
                            onClick = {
                                WaCallEngine.reject()
                                goBack()
                            },
                            contentDescription = "Decline",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.CALL,
                            onClick = { viewModel.onAnswerTapped() },
                            contentDescription = if (busy) "Answering" else "Answer",
                        ),
                    ),
                )
            }
        }
    }
}
