package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
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

class SettingsViewModel : LightViewModel<Unit>() {
    /** Own account once known — null while unpaired. */
    val account = MutableStateFlow<String?>(WaCallEngine.account())
    val engineError = WaCallEngine.error
    val version = MutableStateFlow(WaCallEngine.version())

    init {
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.Paired -> account.value = WaCallEngine.account()
                    is WaEvent.Unlinked -> account.value = null
                    // Unpair parks the flow here until the respawn — already
                    // unpaired as far as the UI is concerned.
                    is WaEvent.NeedPairing -> account.value = null
                    else -> Unit
                }
            }
        }
    }

    /** Connect: restart the pairing flow for a fresh QR set, then let the
     *  pairing screen observe the new `PairingQr` rotations. */
    fun connect() = WaCallEngine.restartPairing()

    /** Unpair: deregister with WhatsApp, wipe the session, respawn — back
     *  to the same state as never having paired. */
    fun unpair() = WaCallEngine.unpair()

    /** Dev design aid: fake incoming call (see [WaCallEngine.debugIncomingCall]). */
    fun debugIncomingCall() = WaCallEngine.debugIncomingCall()
}

/** Account status. The pairing itself stays event-driven (the engine emits
 *  `NeedPairing`/`PairingQr` on start and home routes to the QR screen) —
 *  this screen is where the connected account is visible. */
class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val account by viewModel.account.collectAsState()
        val engineError by viewModel.engineError.collectAsState()
        val version by viewModel.version.collectAsState()
        var versionTaps by remember { mutableStateOf(0) }

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
                    center = LightTopBarCenter.Text("Settings"),
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                ) {
                    Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(
                                enabled = account == null,
                                onClick = {
                                    // Connect: fresh QR set via a pairing
                                    // restart; the pairing screen (pushed
                                    // now) observes the new QR rotations.
                                    viewModel.connect()
                                    navigateTo(screenFactory = { activity ->
                                        PairingScreen(activity, null)
                                    })
                                },
                            ),
                    ) {
                        LightText(text = "Link WhatsApp", variant = LightTextVariant.Heading)
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(
                            text = account?.let { "Paired as $it" } ?: "not connected",
                            variant = LightTextVariant.Detail,
                        )
                    }
                    engineError?.let { message ->
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(text = message, variant = LightTextVariant.Detail)
                    }
                    account?.let { paired ->
                        // Two-tap confirm (no long-press on the LP3): the row
                        // arms on the first tap and acts on the second.
                        var confirmUnpair by remember { mutableStateOf(false) }
                        LaunchedEffect(account) { confirmUnpair = false }
                        Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(
                                    onClick = {
                                        if (confirmUnpair) {
                                            viewModel.unpair()
                                        } else {
                                            confirmUnpair = true
                                        }
                                    },
                                ),
                        ) {
                            LightText(
                                text = if (confirmUnpair) "Tap again to unpair" else "Unpair",
                                variant = LightTextVariant.Heading,
                            )
                            Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                            LightText(
                                text = if (confirmUnpair) "$paired will be unlinked from your phone"
                                else "Unlink this device and erase its session",
                                variant = LightTextVariant.Detail,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    LightText(
                        text = "wa-bridge $version",
                        variant = LightTextVariant.Detail,
                        // Dev design aid: five taps fire a fake incoming
                        // call — ring/active panels render with no bridge,
                        // no network, no audio, no log entries.
                        modifier = Modifier.lightClickable {
                            versionTaps += 1
                            if (versionTaps >= 5) {
                                versionTaps = 0
                                viewModel.debugIncomingCall()
                            }
                        },
                    )
                    Spacer(modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()))
                }
            }
        }
    }
}
