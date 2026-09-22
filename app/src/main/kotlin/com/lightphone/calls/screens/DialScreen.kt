package com.lightphone.calls.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.thelightphone.sdk.LightGlobalRoute
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DialViewModel(initialNumber: String?) : LightViewModel<Unit>() {
    val number = MutableStateFlow(initialNumber ?: "")
    // Increments on every tap that finds the mic ungranted, so each tap
    // re-arms the permission request even after a denial.
    val permissionRequestTick = MutableStateFlow(0)

    fun addDigit(digit: Char) {
        if (number.value.length < 20) number.value = number.value + digit
    }

    fun deleteDigit() {
        number.value = number.value.dropLast(1)
    }

    fun clearNumber() {
        number.value = ""
    }

    /** Native grammar: the call panel opens immediately on the call tap —
     *  it shows "connecting..." while the offer goes out, and owns the
     *  placement, the ring wait and the "call failed" path (it routes back
     *  here when the attempt dies). */
    fun onCallTapped(route: () -> Unit) {
        if (number.value.isEmpty()) return
        viewModelScope.launch {
            if (micPermissionGranted()) {
                WaCallEngine.activeScreenRouted = true
                route()
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
        val permissionRequestTick by viewModel.permissionRequestTick.collectAsState()
        val permissionLauncher = rememberPermissionRequestLauncher(Manifest.permission.RECORD_AUDIO)

        LaunchedEffect(permissionRequestTick) {
            if (permissionRequestTick > 0) permissionLauncher?.launch()
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Dialer layout: the keypad is bottom-anchored (a one-buffer
                // gap above the bottom bar) and the typed header overlays the
                // top — so typing never shifts the pad. `×` dismisses the
                // dialpad; the `<` glyph deletes, long-press clears.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (number.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                // Native dialer: the number row starts ~41px
                                // below the status inset (number center ≈114px)
                                // — a ~1.6gu buffer above the row; ours sat
                                // flush under the inset.
                                .padding(top = 1.6f.gridUnitsAsDp())
                                .height(3f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = number,
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    // Native dialer: the delete box sits 85px
                                    // (~2.1gu) off the edge — that margin is the
                                    // buffer between the number and the glyph.
                                    .padding(end = 2.1f.gridUnitsAsDp())
                                    .lightClickable(
                                        onClick = viewModel::deleteDigit,
                                        onLongClick = viewModel::clearNumber,
                                        onLongClickLabel = "Clear",
                                    ),
                            ) {
                                LightIcon(icon = LightIcons.BACK, contentDescription = "Delete digit, hold to clear")
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        listOf(
                            listOf('1', '2', '3'),
                            listOf('4', '5', '6'),
                            listOf('7', '8', '9'),
                            listOf(null, '0', null),
                        ).forEach { rowDigits ->
                            Row(
                                modifier = Modifier.height(5f.verticalGridUnitsAsDp()),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                rowDigits.forEach { digit ->
                                    Box(
                                        modifier = Modifier.width(6f.gridUnitsAsDp()),
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
                        // Native grammar: call glyph bottom-LEFT, × center
                        // (dialer-typed capture; right slot is compose there —
                        // unused here).
                        if (number.isEmpty()) {
                            null
                        } else {
                            LightBarButton.LightIcon(
                                icon = LightIcons.CALL,
                                onClick = {
                                    // clearToRoot: the call panel replaces the
                                    // dialer — hang-up then returns to the log
                                    // (native recents), not a stale pad.
                                    viewModel.onCallTapped {
                                        LightGlobalRoute.push(clearToRoot = true) {
                                            ActiveCallScreen(it, number, placeOutgoing = true)
                                        }
                                    }
                                },
                                contentDescription = "Call",
                            )
                        },
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { goBack() },
                            contentDescription = "Dismiss dialpad",
                        ),
                        null,
                    ),
                )
            }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .lightClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = digit.toString(), variant = LightTextVariant.Subtitle)
    }
}
