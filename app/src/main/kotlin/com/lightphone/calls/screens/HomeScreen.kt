package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.thelightphone.sdk.ui.LightIcon
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

    /** The persisted call log, newest first. Distinct peers double as the
     *  contacts list until the bridge exposes a real contacts API. */
    val log = MutableStateFlow<List<CallLogEntry>>(emptyList())

    init {
        log.value = WaCallEngine.callLogEntries().asReversed()
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.NeedPairing -> Unit
                    // Pairing is user-driven from Settings (which restarts the
                    // flow for fresh QRs) — the pairing screen observes the
                    // QR rotations itself; home never auto-routes to it, or
                    // backing out just gets re-pushed on every rotation.
                    is WaEvent.PairingQr -> Unit
                    is WaEvent.Paired -> {
                        account.value = WaCallEngine.account()
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
        // Backing out of IncomingCallScreen returns here: clear the gate so
        // the next event re-opens its route.
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
        val incomingPeer by viewModel.incomingPeer.collectAsState()
        val routeToCall by viewModel.routeToCall.collectAsState()
        val log by viewModel.log.collectAsState()

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
                if (log.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
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
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = { navigateTo(screenFactory = { SettingsScreen(it) }) },
                            contentDescription = "Settings",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.DIALPAD,
                            onClick = { navigateTo(screenFactory = { DialScreen(it, null) }) },
                            contentDescription = "Dial",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.CONTACTS,
                            onClick = { navigateTo(screenFactory = { ContactsScreen(it) }) },
                            contentDescription = "Contacts",
                        ),
                    ),
                )
            }
        }
    }
}

/** One call-log row in the native Phone activity grammar (see
 *  reference/lp3/screens/phone/recents-logged.png): date line above, the
 *  caller's name/number under it, direction shown by alignment — outgoing
 *  right, incoming left, missed left with the missed-call handset icon. */
@Composable
private fun CallLogRow(entry: CallLogEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
        horizontalAlignment = if (entry.outgoing && !entry.missed) {
            Alignment.End
        } else {
            Alignment.Start
        },
    ) {
        LightText(text = formatAt(entry.at), variant = LightTextVariant.Superfine)
        if (entry.missed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LightIcon(icon = LightIcons.CALL_MISSED, contentDescription = "Missed call")
                Spacer(modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
                LightText(text = peerLabel(entry.peer), variant = LightTextVariant.Copy)
            }
        } else {
            LightText(text = peerLabel(entry.peer), variant = LightTextVariant.Copy)
        }
    }
}

/** A stored peer JID ("49…@s.whatsapp.net" or bare number) as a number. */
internal fun peerLabel(peer: String): String = peer.substringBefore('@')

private fun formatAt(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d h:mm a"))
