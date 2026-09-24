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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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

    /** Contact names by peer JID and bare number — the log shows a name
     *  when the contact list knows one. */
    val names = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        log.value = WaCallEngine.callLogEntries().sortedBy { it.at }
        names.value = contactNames()
        // Incoming offers route from engine STATE, not the event stream: a
        // cold-start offer lands mid-launch (the bot receives it before the
        // activity is up) and the events StateFlow only replays its LAST
        // value — often already a later event (history-sync import, the
        // caller's terminate) — so late-subscribing collectors lost the
        // offer and the panel never appeared (2026-09-25 live regression).
        // `pendingOffer` holds the offer for exactly as long as it lives;
        // collecting it here re-routes it however late this VM subscribes.
        viewModelScope.launch {
            WaCallEngine.pendingOffer.collect { offer ->
                if (offer != null && !callActive.value && !incomingActive.value) {
                    incomingPeer.value = offer.peer
                    incomingActive.value = true
                    // Route through LightGlobalRoute, not a screen
                    // composition: hidden screens' collectors are
                    // dead, so only this always-alive VM can make the
                    // incoming panel appear over ANY screen.
                    LightGlobalRoute.push { IncomingCallScreen(it, offer.peer) }
                }
            }
        }
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.ContactsImport -> names.value = contactNames()
                    is WaEvent.NeedPairing -> Unit
                    // Pairing is user-driven from Settings (which restarts the
                    // flow for fresh QRs) — the pairing screen observes the
                    // QR rotations itself; home never auto-routes to it, or
                    // backing out just gets re-pushed on every rotation.
                    is WaEvent.PairingQr -> Unit
                    is WaEvent.Paired -> {
                        account.value = WaCallEngine.account()
                    }
                    is WaEvent.Ringing ->
                        // Outgoing offer out (the dial tap already routed the
                        // panel — this is a no-op then): a no-answer timeout
                        // flash lives on the panel itself. Stale-replay
                        // guard as above: only a call the engine still holds.
                        if (WaCallEngine.currentCallPeer != null) {
                            callActive.value = true
                        }
                    is WaEvent.CallStarted -> {
                        // Stale-replay guard: a replayed CallStarted for a
                        // call the engine already dropped must not route an
                        // active-call panel on app entry (the engine's own
                        // ghost guard drops it before audio, the tail still
                        // publishes it to the flow).
                        if (WaCallEngine.currentCallPeer != null) {
                            callActive.value = true
                            // clearToRoot: an incoming panel may sit on the stack
                            // below — the answered call's screen replaces it.
                            pushActive(WaCallEngine.currentCallPeer ?: "call", clearToRoot = true)
                        }
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

    /** Known contact names, keyed both by full JID and bare number so a log
     *  entry stored under either form resolves. */
    private fun contactNames(): Map<String, String> =
        WaCallEngine.contactEntries()
            .filter { it.name.isNotEmpty() }
            .flatMap { listOf(it.id to it.name, peerLabel(it.id) to it.name) }
            .toMap()

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
        names.value = contactNames()
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
        val names by viewModel.names.collectAsState()

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
                    val grouped = groupMissedCalls(log)
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f),
                        reverseLayout = true,
                        // Row pitch ≈ 5.9gu (2-line rows measured 3.75; the
                        // duration row adds ~2gu) — the scrollbar's thumb
                        // math uses this as the uniform height.
                        uniformItemHeightGridUnits = 6.45f,
                    ) {
                        items(grouped.asReversed()) { (entry, missedCount) ->
                            CallLogRow(
                                entry = entry,
                                missedCount = missedCount,
                                peerDisplay = names[entry.peer]
                                    ?: names[peerLabel(entry.peer)]
                                    ?: formatPeer(entry.peer),
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
 *  reference/lp3/screens/phone/recents-logged.png): date line above, a gap,
 *  the handset glyph with the peer's name/number bottom-aligned to it —
 *  outgoing rows right-aligned with the glyph AFTER the number (native
 *  recents), incoming/missed left with the glyph first — and the connected
 *  duration ("0m 12s", "0s") under the glyph line. Native glyph ≈60px
 *  (1.5gu; the 2gu bar-button size reads oversized in the log). */
@Composable
private fun CallLogRow(entry: CallLogEntry, missedCount: Int, peerDisplay: String, onClick: () -> Unit) {
    val outgoing = entry.outgoing && !entry.missed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            // End inset 0: the list's scrollbar gutter (2gu) is the only
            // right spacing, so outgoing rows sit the thumb-to-edge distance
            // from the scrollbar (feedback 2026-09-22).
            .padding(
                start = 2f.gridUnitsAsDp(),
                end = 0f.gridUnitsAsDp(),
                top = 1f.gridUnitsAsDp(),
                bottom = 1f.gridUnitsAsDp(),
            ),
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
    ) {
        LightText(text = formatAt(entry.at), variant = LightTextVariant.Superfine)
        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
        // Bottom-aligned row: the CALL vector draws its glyph high inside the
        // box, so a 0.25gu down-shift puts the glyph's visual bottom on the
        // digits' baseline (feedback 2026-09-22: tops aligned, bottoms wanted).
        Row(verticalAlignment = Alignment.Bottom) {
            if (!outgoing) {
                LightIcon(
                    icon = if (entry.missed) LightIcons.CALL_MISSED else LightIcons.CALL,
                    size = 1.5f,
                    modifier = Modifier.offset(y = -0.35f.gridUnitsAsDp()),
                    contentDescription = if (entry.missed) "Missed call" else null,
                )
                Spacer(modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
            }
            LightText(text = peerDisplay, variant = LightTextVariant.Copy)
            if (outgoing) {
                Spacer(modifier = Modifier.padding(start = 0.5f.gridUnitsAsDp()))
                LightIcon(
                    icon = LightIcons.CALL,
                    size = 1.5f,
                    modifier = Modifier.offset(y = -0.35f.gridUnitsAsDp()),
                    contentDescription = "Outgoing call",
                )
            }
        }
        Spacer(modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()))
        LightText(
            text = when {
                entry.missed -> if (missedCount > 1) "missed call ($missedCount)" else "missed call"
                entry.duration > 0 -> formatDuration(entry.duration)
                // ACCEPTEDELSEWHERE with no wire duration — our device wasn't
                // a party to the connected call. A duration>0 elsewhere row
                // (they exist — the wire often carries it) shows above.
                entry.answeredElsewhere -> "answered elsewhere"
                else -> formatDuration(entry.duration)
            },
            variant = LightTextVariant.Superfine,
        )
    }
}

/** Native duration rendering: "0m 16s", "2m 05s". Missed calls render the
 *  lowercase "missed call" label, accepted-elsewhere calls "answered
 *  elsewhere" — the wire carries no duration for a call our device wasn't
 *  a party to (feedback 2026-09-22). */
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

/** Row timestamp in the Chats room-row grammar (feedback 2026-09-23): today
 *  shows the time only — the only rows that carry one — the past 6 days show
 *  the full day name with no time ("Monday"), and older calls show the whole
 *  date (year joins for previous years). Row order stays by time. */
private fun formatAt(at: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(at).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> dateTime.toLocalTime().format(TIME_FORMAT)
        date.isAfter(today.minusDays(7)) ->
            date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        date.year == today.year -> date.format(MONTH_DAY_FORMAT)
        else -> date.format(MONTH_DAY_YEAR_FORMAT)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val MONTH_DAY_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Collapses runs of missed calls from one peer on the same local day into a
 *  single row — "missed call (x)" (feedback 2026-09-23). The row keeps the
 *  LATEST call's entry (ordering stays by time); only consecutive runs merge,
 *  so a later non-missed row to the same peer starts a fresh group. */
internal fun groupMissedCalls(entries: List<CallLogEntry>): List<Pair<CallLogEntry, Int>> {
    val rows = mutableListOf<Pair<CallLogEntry, Int>>()
    for (entry in entries) {
        val last = rows.lastOrNull()
        if (
            entry.missed && last != null && last.first.missed &&
            last.first.peer == entry.peer && sameDay(last.first.at, entry.at)
        ) {
            rows[rows.lastIndex] = entry to last.second + 1
        } else {
            rows.add(entry to 1)
        }
    }
    return rows
}

private fun sameDay(a: Long, b: Long): Boolean =
    Instant.ofEpochMilli(a).atZone(ZoneId.systemDefault()).toLocalDate() ==
        Instant.ofEpochMilli(b).atZone(ZoneId.systemDefault()).toLocalDate()
