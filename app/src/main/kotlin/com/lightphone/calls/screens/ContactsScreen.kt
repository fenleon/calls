package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.lightphone.calls.server.ContactEntry
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
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
}

/** People reachable through the linked account. Tapping opens the contact
 *  panel (name/number + call button); the Call button there opens the dial
 *  pad prefilled (the Call button there owns the mic permission). */
class ContactsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ContactsViewModel>(sealedActivity) {

    override val viewModelClass: Class<ContactsViewModel>
        get() = ContactsViewModel::class.java

    override fun createViewModel(): ContactsViewModel = ContactsViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val peers by viewModel.peers.collectAsState()

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
                    center = LightTopBarCenter.Text("Contacts"),
                )
                if (peers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "No contacts yet",
                            variant = LightTextVariant.Copy,
                            align = TextAlign.Center,
                        )
                    }
                } else {
                    LightScrollView(modifier = Modifier.weight(1f)) {
                        peers.forEach { contact ->
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
                                    variant = LightTextVariant.Copy,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
