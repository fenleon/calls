package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.R
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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

    /** Own WhatsApp profile name once known — the "Paired as" line; the pn
     *  (from [account]) is the fallback. */
    val accountName = MutableStateFlow<String?>(WaCallEngine.accountName())
    val engineError = WaCallEngine.error

    init {
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.Paired -> {
                        account.value = WaCallEngine.account()
                        accountName.value = WaCallEngine.accountName()
                    }
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
    fun demoCall() = WaCallEngine.debugIncomingCall()

    /** Diagnostics video-encoder exercise (see [WaCallEngine.toggleVideoExercise]). */
    val videoExercise = WaCallEngine.videoExerciseRunning
    val exerciseNote = WaCallEngine.exerciseNote

    fun toggleVideoExercise() {
        WaCallEngine.toggleVideoExercise()
    }
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
        val accountName by viewModel.accountName.collectAsState()
        val engineError by viewModel.engineError.collectAsState()
        val videoExercise by viewModel.videoExercise.collectAsState()
        val exerciseNote by viewModel.exerciseNote.collectAsState()

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
                    if (account == null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(
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
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(
                                    onClick = {
                                        navigateTo(screenFactory = { activity ->
                                            UnlinkConfirmPanel(activity)
                                        }) { confirmed ->
                                            if (confirmed) viewModel.unpair()
                                        }
                                    },
                                ),
                        ) {
                            LightText(text = "Unlink WhatsApp", variant = LightTextVariant.Heading)
                            Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                            LightText(
                                text = "Paired as ${
                                    accountName ?: account?.substringBefore('@').orEmpty()
                                }",
                                variant = LightTextVariant.Detail,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(onClick = { viewModel.demoCall() }),
                    ) {
                        LightText(text = "Demo Call", variant = LightTextVariant.Heading)
                    }
                    Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(onClick = { viewModel.toggleVideoExercise() }),
                    ) {
                        LightText(text = "Video encoder test", variant = LightTextVariant.Heading)
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(
                            text = when {
                                videoExercise -> "Recording — tap to stop"
                                else -> exerciseNote ?: "Diagnostics"
                            },
                            variant = LightTextVariant.Detail,
                        )
                    }
                    Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable(
                                onClick = {
                                    navigateTo(screenFactory = { activity ->
                                        VideoDecodeTestScreen(activity)
                                    })
                                },
                            ),
                    ) {
                        LightText(text = "Video decode test", variant = LightTextVariant.Heading)
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(text = "Diagnostics", variant = LightTextVariant.Detail)
                    }
                    engineError?.let { message ->
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(text = message, variant = LightTextVariant.Detail)
                    }
                }
            }
        }
    }
}

/** The unlink confirmation panel: centered
 *  "Are you sure you want to unlink this device and erase its session?" with
 *  the LP3 X (dismiss) and triangle (confirm) — the same panel grammar as the
 *  chats tool's logout confirm. Result: true = unlink. */
class UnlinkConfirmPanel(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Boolean>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val confirmX = painterResource(R.drawable.ic_lp3_confirm_x)
        val confirmTriangle = painterResource(R.drawable.ic_lp3_confirm_triangle)

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Are you sure you want to unlink this device and erase its session?",
                        variant = LightTextVariant.Copy,
                        align = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 3f.gridUnitsAsDp()),
                    )
                }
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.Icon(
                            painter = confirmX,
                            onClick = { goBack(false) },
                            contentDescription = "Cancel",
                        ),
                        null,
                        LightBarButton.Icon(
                            painter = confirmTriangle,
                            onClick = { goBack(true) },
                            contentDescription = "Unlink",
                        ),
                    ),
                )
            }
        }
    }
}
