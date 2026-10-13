package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.server.ContactEntry
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
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

class ContactsViewModel : LightViewModel<Unit>() {
    /** Distinct peers, named when the history sync knew a push name —
     *  alphabetical, unnamed numbers last. */
    val peers = MutableStateFlow<List<ContactEntry>>(emptyList())

    /** In-panel search query; filters [peers]. Blank = all. */
    val query = MutableStateFlow("")

    init {
        refresh()
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                if (event is WaEvent.CallEnded ||
                    event is WaEvent.MissedCall ||
                    event is WaEvent.CallLogImport ||
                    event is WaEvent.ContactsImport
                ) {
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        // Imported contacts carry the names; call-log-only peers join so a
        // number without a history-sync name still shows.
        val byId = WaCallEngine.callLogEntries()
            .map { ContactEntry(id = it.peer, name = "") }
            .plus(WaCallEngine.contactEntries())
            .associateBy { it.id }
        peers.value = byId.values.sortedWith(
            compareBy({ it.name.isEmpty() }, { it.name.lowercase() }, { it.id }),
        )
    }

    /** [peers] filtered by [query]: a name or number containing it
     *  (case-insensitive; the raw digits match too). Number matching uses
     *  the raw JID digits only — no PhoneNumberUtils formatting per peer
     *  here: it ran on the main thread inside the submit tap and stalled
     *  the frame ~1s (Davey, 2026-09-22). */
    fun entries(peers: List<ContactEntry>, query: String): List<ContactEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return peers
        val qDigits = q.filter { it.isDigit() }
        return peers.filter {
            it.name.lowercase().contains(q) ||
                (qDigits.isNotEmpty() && peerLabel(it.id).contains(qDigits))
        }
    }
}

/** People reachable through the linked account. Tapping opens the contact
 *  panel (name/number + call button); the Call button there opens the dial
 *  pad prefilled (the Call button there owns the mic permission). SEARCH
 *  top-right opens the field editor (the chats contacts-search pattern):
 *  the title shows the active query in quotes. */
class ContactsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ContactsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ContactsViewModel>
        get() = ContactsViewModel::class.java

    override fun createViewModel(): ContactsViewModel = ContactsViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val peers by viewModel.peers.collectAsState()
        val query by viewModel.query.collectAsState()
        val entries = remember(peers, query) { viewModel.entries(peers, query) }

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
                    center = LightTopBarCenter.Text(
                        if (query.isNotBlank()) "'$query'" else "Contacts",
                    ),
                    rightButton = LightBarButton.LightIcon(
                        icon = LightIcons.SEARCH,
                        onClick = { openSearch() },
                        contentDescription = "Search contacts",
                    ),
                )
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = if (query.isNotBlank()) "No contacts match" else "No contacts yet",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                    }
                } else {
                    // Lazy: the list can run long, and eager composition of
                    // every row is what made the screen slow to open.
                    LightLazyScrollView(
                        modifier = Modifier.weight(1f),
                        // Native contacts panel pitch: ~130px (Heading name +
                        // whitespace) — 5.4gu.
                        uniformItemHeightGridUnits = 5.4f,
                    ) {
                        items(entries) { contact ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable(
                                        onClick = {
                                            navigateTo(screenFactory = { activity ->
                                                ContactInfoScreen(activity, contact)
                                            })
                                        },
                                    )
                                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                            ) {
                                LightText(
                                    text = contact.name.ifEmpty { formatPeer(contact.id) },
                                    variant = LightTextVariant.Heading,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openSearch() {
        navigateTo(
            screenFactory = {
                FieldEditorScreen(
                    it,
                    "Search Contacts",
                    viewModel.query.value,
                    "SEARCH",
                    submitIcon = LightIcons.SEARCH,
                )
            },
        ) { result -> viewModel.query.value = result }
    }
}
