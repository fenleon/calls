package com.lightphone.calls.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DialViewModel(initialNumber: String?) : LightViewModel<Unit>() {
    val number = MutableStateFlow(initialNumber ?: "")
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    // Increments on every tap that finds the mic ungranted, so each tap
    // re-arms the permission request even after a denial.
    val permissionRequestTick = MutableStateFlow(0)

    fun addDigit(digit: Char) {
        if (number.value.length < 20) number.value = number.value + digit
    }

    fun deleteDigit() {
        number.value = number.value.dropLast(1)
    }

    fun onCallTapped() {
        if (busy.value || number.value.isEmpty()) return
        viewModelScope.launch {
            if (micPermissionGranted()) {
                busy.value = true
                error.value = null
                try {
                    // On success `Ringing` — or `CallStarted` directly, if
                    // the peer answered during the offer send — routes to
                    // the active-call screen (observed below); the glare
                    // guard rejects a second call while one is up.
                    WaCallEngine.placeCall(number.value)
                } catch (e: Exception) {
                    error.value = "Call failed"
                    busy.value = false
                }
            } else {
                permissionRequestTick.value += 1
            }
        }
    }
}

/** Dial pad: digits, delete, call. The call routes to the active-call screen
 *  through home once the bridge reports a state (`Ringing`, or `CallStarted`
 *  directly for an accept that landed during the offer send). */
class DialScreen(
    sealedActivity: SealedLightActivity,
    private val initialNumber: String?,
) : LightScreen<Unit, DialViewModel>(sealedActivity) {

    override val viewModelClass: Class<DialViewModel>
        get() = DialViewModel::class.java

    override fun createViewModel(): DialViewModel = DialViewModel(initialNumber)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val number by viewModel.number.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val error by viewModel.error.collectAsState()
        val permissionRequestTick by viewModel.permissionRequestTick.collectAsState()
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)

        LaunchedEffect(Unit) {
            // The outgoing call leaves the dial pad as soon as the bridge
            // reports a state (home routes to the active-call screen):
            // `Ringing` when the peer has not answered yet, `CallStarted`
            // directly when the accept landed during the offer send (no
            // ringing phase ever reported). Once-only: a second event must
            // not pop past the active-call screen.
            var left = false
            WaCallEngine.events.collect { event ->
                if (!left && (event is WaEvent.Ringing || event is WaEvent.CallStarted)) {
                    left = true
                    goBack()
                }
            }
        }
        LaunchedEffect(permissionRequestTick) {
            if (permissionRequestTick > 0) permissionLauncher?.launch()
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Dial"),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = number.ifEmpty { "Enter a number" },
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (number.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .lightClickable(onClick = viewModel::deleteDigit),
                        ) {
                            LightIcon(icon = LightIcons.DELETE, contentDescription = "Delete")
                        }
                    }
                }
                error?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Superfine,
                        align = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        listOf(
                            listOf('1', '2', '3'),
                            listOf('4', '5', '6'),
                            listOf('7', '8', '9'),
                            listOf(null, '0', null),
                        ).forEach { rowDigits ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rowDigits.forEach { digit ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        when (digit) {
                                            null -> Unit
                                            else -> DigitKey(digit) { viewModel.addDigit(digit) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = if (busy) "…" else "CALL",
                            onClick = { viewModel.onCallTapped() },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(0.5f.gridUnitsAsDp()),
    ) {
        LightText(text = digit.toString(), variant = LightTextVariant.Title)
    }
}
