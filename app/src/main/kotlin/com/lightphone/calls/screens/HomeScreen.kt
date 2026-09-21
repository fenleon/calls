package com.lightphone.calls.screens

import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.CallLogEntry
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightGlobalRoute
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : LightViewModel<Unit>() {

    /** Own account once known ("Paired as …"). */
    val account = MutableStateFlow<String?>(null)

    /** Newest incoming offer — gates re-pushes while the panel is up (the
     *  routing itself goes through `LightGlobalRoute`). */
    val incomingPeer = MutableStateFlow<String?>(null)
    val incomingActive = MutableStateFlow(false)

    /** True from `Ringing`/`CallStarted` to `CallEnded` — extends the
     *  incoming gate: an offer arriving during a live call is not routed
     *  (answering it would put two calls on one mic/playout pair; the
     *  bridge-side guard is a follow-up). */
    val callActive = MutableStateFlow(false)

    /** The persisted call log, newest first. Distinct peers double as the
     *  contacts list until the bridge exposes a real contacts API. */
    val log = MutableStateFlow<List<CallLogEntry>>(emptyList())

    init {
        log.value = WaCallEngine.callLogEntries().sortedBy { it.at }
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
                        if (!callActive.value && !incomingActive.value) {
                            incomingPeer.value = event.peer
                            incomingActive.value = true
                            // Route through LightGlobalRoute, not a screen
                            // composition: hidden screens' collectors are
                            // dead, so only this always-alive VM can make the
                            // incoming panel appear over ANY screen.
                            LightGlobalRoute.push { IncomingCallScreen(it, event.peer) }
                        }
                    is WaEvent.Ringing ->
                        // Outgoing offer out (the dial tap already routed the
                        // panel — this is a no-op then): a no-answer timeout
                        // flash lives on the panel itself.
                        callActive.value = true
                    is WaEvent.CallStarted -> {
                        callActive.value = true
                        // clearToRoot: an incoming panel may sit on the stack
                        // below — the answered call's screen replaces it.
                        pushActive(WaCallEngine.currentCallPeer ?: "call", clearToRoot = true)
                    }
                    is WaEvent.CallEnded -> {
                        callActive.value = false
                        incomingActive.value = false
                        incomingPeer.value = null
                        log.value = WaCallEngine.callLogEntries().sortedBy { it.at }
                    }
                    is WaEvent.MissedCall -> {
                        // A caller cancel/expiry ends the offer (never a
                        // CallEnded): release the incoming gates so the panel
                        // closes and the next offer can route.
                        incomingActive.value = false
                        incomingPeer.value = null
                        log.value = WaCallEngine.callLogEntries().sortedBy { it.at }
                    }
                    else -> Unit
                }
            }
        }
    }

    /** One active-call push per call: the dial tap already routed the panel
     *  for outgoing (engine flag), and the ringing panel itself observes
     *  `CallStarted` — a later event must not stack a second screen. */
    private fun pushActive(peer: String, clearToRoot: Boolean = false) {
        if (WaCallEngine.activeScreenRouted) return
        WaCallEngine.activeScreenRouted = true
        LightGlobalRoute.push(clearToRoot = clearToRoot) { ActiveCallScreen(it, peer) }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // Backing out of IncomingCallScreen returns here: clear the gate so
        // the next event re-opens its route. Also re-read the log — entries
        // are written the moment they happen (instant outgoing logging), so
        // a return to home must not show a stale list.
        incomingActive.value = false
        log.value = WaCallEngine.callLogEntries().sortedBy { it.at }
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
        val log by viewModel.log.collectAsState()

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
                    // Thread anchored at the bottom (chat pattern): entries
                    // render newest-first inside a reverseLayout lazy list, so
                    // the latest sits at the bottom edge, older ones stack
                    // upward, and a new entry lands at the bottom even when
                    // the list is short. Rows are ~3.75gu tall (scrollbar
                    // estimate).
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f),
                        reverseLayout = true,
                        // Row pitch ≈ 5.9gu (2-line rows measured 3.75; the
                        // duration row adds ~2gu) — the scrollbar's thumb
                        // math uses this as the uniform height.
                        uniformItemHeightGridUnits = 6.45f,
                    ) {
                        items(log.asReversed()) { entry ->
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

/** One call-log row in the native Phone per-contact grammar (see
 *  reference/lp3/screens/phone/ + the live per-contact capture): date line
 *  above, a gap, the handset glyph with the peer's name/number
 *  bottom-aligned to it (direction shown by alignment — outgoing right,
 *  incoming/missed left; the missed glyph marks unanswered calls), and the
 *  connected duration ("0m 12s", "0s") under the glyph line. */
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
        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
        Row(verticalAlignment = Alignment.Bottom) {
            LightIcon(
                icon = if (entry.missed) LightIcons.CALL_MISSED else LightIcons.CALL,
                // The handset glyph ends ~10px above its box bottom (vector
                // padding) while digits end at the descent line — nudge the
                // icon down so the glyph's bottom sits on the text's bottom.
                modifier = Modifier.offset(y = 0.3f.gridUnitsAsDp()),
                contentDescription = if (entry.missed) "Missed call" else null,
            )
            Spacer(modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
            LightText(text = formatPeer(entry.peer), variant = LightTextVariant.Copy)
        }
        LightText(text = formatDuration(entry.duration), variant = LightTextVariant.Superfine)
    }
}

/** Native duration rendering: "0m 16s", "2m 05s", "0s" for unanswered. */
internal fun formatDuration(seconds: Long): String {
    if (seconds == 0L) return "0s"
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s.toString().padStart(2, '0')}s"
}

/** A stored peer JID ("49…@s.whatsapp.net" or bare number) as a number. */
internal fun peerLabel(peer: String): String = peer.substringBefore('@')

/** Render a peer the way the native Phone does — an international
 *  "+cc number" layout (PhoneNumberUtils; E.164 input needs no region) —
 *  falling back to "+" + digits for ids that aren't real numbers (LIDs). */
internal fun formatPeer(peer: String): String {
    val digits = peerLabel(peer).filter { it.isDigit() }
    if (digits.isEmpty()) return peer
    return PhoneNumberUtils.formatNumber("+$digits", Locale.getDefault().country)
        ?: "+$digits"
}

private fun formatAt(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d h:mm a"))
