package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import com.lightphone.calls.server.CallLogEntry
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : LightViewModel<Unit>() {

    /** Latest pairing QR from the engine — non-null routes to PairingScreen
     *  (cleared once consumed so back-navigation doesn't re-push it).
     *  [pairingActive] gates this: while the pairing screen is up, further
     *  PairingQr rotations are ignored here (the pairing screen observes the
     *  events itself) so navigateTo can't stack duplicate screens. */
    val pairingQr = MutableStateFlow<String?>(null)
    val pairingActive = MutableStateFlow(false)

    /** Own account once known ("Paired as …"). */
    val account = MutableStateFlow<String?>(null)

    /** Newest incoming offer — non-null routes to IncomingCallScreen.
     *  [incomingActive] gates re-pushes while that screen is up. */
    val incomingPeer = MutableStateFlow<String?>(null)
    val incomingActive = MutableStateFlow(false)

    /** The call id that started — non-null routes to ActiveCallScreen
     *  (dial, answer and the ringing outgoing call converge here; cleared
     *  on CallEnded so back out of the call screen doesn't re-push it). */
    val routeToCall = MutableStateFlow<String?>(null)

    /** True from `Ringing`/`CallStarted` to `CallEnded` — extends the
     *  incoming gate: an offer arriving during a live call is not routed
     *  (answering it would put two calls on one mic/playout pair; the
     *  bridge-side guard is a follow-up). */
    val callActive = MutableStateFlow(false)

    /** The persisted call log, newest first. */
    val log = MutableStateFlow<List<CallLogEntry>>(emptyList())

    init {
        account.value = WaCallEngine.account()
        log.value = WaCallEngine.callLogEntries().asReversed()
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.PairingQr ->
                        if (!pairingActive.value) pairingQr.value = event.payload
                    is WaEvent.NeedPairing -> Unit
                    is WaEvent.Paired -> {
                        account.value = WaCallEngine.account()
                        pairingActive.value = false
                        pairingQr.value = null
                    }
                    is WaEvent.IncomingCall ->
                        if (routeToCall.value == null && !incomingActive.value &&
                            !callActive.value
                        ) {
                            incomingPeer.value = event.peer
                        }
                    is WaEvent.Ringing -> {
                        // Outgoing call accepted by the bridge (offer out,
                        // peer not answering yet): route to the active-call
                        // screen now — it shows "Ringing…" until the peer's
                        // answer lands as `CallStarted`.
                        callActive.value = true
                        routeToCall.value = WaCallEngine.currentCallPeer ?: event.peer
                    }
                    is WaEvent.CallStarted -> {
                        callActive.value = true
                        routeToCall.value = WaCallEngine.currentCallPeer ?: "call"
                    }
                    is WaEvent.CallEnded -> {
                        callActive.value = false
                        routeToCall.value = null
                        log.value = WaCallEngine.callLogEntries().asReversed()
                    }
                    is WaEvent.MissedCall ->
                        log.value = WaCallEngine.callLogEntries().asReversed()
                    else -> Unit
                }
            }
        }
    }

    fun consumeQr(): String? {
        val payload = pairingQr.value
        pairingQr.value = null
        pairingActive.value = true
        return payload
    }

    fun consumeIncoming(): String? {
        val peer = incomingPeer.value
        incomingPeer.value = null
        incomingActive.value = true
        return peer
    }

    fun consumeRoute(): String? {
        val peer = routeToCall.value
        routeToCall.value = null
        return peer
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // Backing out of PairingScreen / IncomingCallScreen returns here:
        // clear the gates so the next event re-opens its route.
        pairingActive.value = false
        incomingActive.value = false
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val pairingQr by viewModel.pairingQr.collectAsState()
        val account by viewModel.account.collectAsState()
        val incomingPeer by viewModel.incomingPeer.collectAsState()
        val routeToCall by viewModel.routeToCall.collectAsState()
        val log by viewModel.log.collectAsState()
        val engineError by WaCallEngine.error.collectAsState()

        LaunchedEffect(pairingQr) {
            if (pairingQr != null) {
                viewModel.consumeQr()?.let { payload ->
                    navigateTo(screenFactory = { PairingScreen(it, payload) })
                }
            }
        }
        LaunchedEffect(incomingPeer) {
            if (incomingPeer != null) {
                viewModel.consumeIncoming()?.let { peer ->
                    navigateTo(screenFactory = { IncomingCallScreen(it, peer) })
                }
            }
        }
        LaunchedEffect(routeToCall) {
            if (routeToCall != null) {
                viewModel.consumeRoute()?.let { peer ->
                    navigateTo(screenFactory = { ActiveCallScreen(it, peer) })
                }
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                        LightText(text = "WhatsApp", variant = LightTextVariant.Heading)
                        account?.let { name ->
                            Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                            LightText(text = "Paired as $name", variant = LightTextVariant.Superfine, lighten = true)
                        }
                        engineError?.let { message ->
                            Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                            LightText(text = message, variant = LightTextVariant.Superfine)
                        }
                        Spacer(modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                        if (log.isEmpty()) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                LightText(
                                    text = "No calls yet",
                                    variant = LightTextVariant.Copy,
                                    align = TextAlign.Center,
                                )
                            }
                        } else {
                            LightScrollView(modifier = Modifier.weight(1f)) {
                                log.forEach { entry ->
                                    CallLogRow(
                                        entry = entry,
                                        onClick = {
                                            navigateTo(screenFactory = { activity ->
                                                DialScreen(activity, peerLabel(entry.peer))
                                            })
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.DIALPAD,
                            onClick = { navigateTo(screenFactory = { DialScreen(it, null) }) },
                            contentDescription = "Dial",
                        ),
                    ),
                )
            }
        }
    }
}

/** One call-log line: the peer number, with a "Missed ·" / direction marker
 *  and the time as the detail line. Tapping opens the dial screen prefilled
 *  with the number (the Call button there owns the mic permission). */
@Composable
private fun CallLogRow(entry: CallLogEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    ) {
        LightText(text = peerLabel(entry.peer), variant = LightTextVariant.Copy)
        val detail = buildString {
            if (entry.missed) {
                append("Missed")
            } else if (entry.outgoing) {
                append("Outgoing")
            } else {
                append("Incoming")
            }
            append(" · ")
            append(formatAt(entry.at))
        }
        LightText(text = detail, variant = LightTextVariant.Superfine)
    }
}

/** A stored peer JID ("49…@s.whatsapp.net" or bare number) as a number. */
internal fun peerLabel(peer: String): String = peer.substringBefore('@')

private fun formatAt(at: Long): String {
    val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    val pattern = if (time.toLocalDate() == java.time.LocalDate.now()) "HH:mm" else "MMM d"
    return time.format(DateTimeFormatter.ofPattern(pattern))
}
