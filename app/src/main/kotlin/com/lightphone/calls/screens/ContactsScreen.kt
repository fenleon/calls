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
    /** Distinct peers from the call log, alphabetical. The bridge has no
     *  contacts API yet — these are the people the account has called. */
    val peers = MutableStateFlow<List<String>>(emptyList())

    init {
        refresh()
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                if (event is WaEvent.CallEnded || event is WaEvent.MissedCall) refresh()
            }
        }
    }

    private fun refresh() {
        peers.value = WaCallEngine.callLogEntries()
            .map { peerLabel(it.peer) }
            .distinct()
            .sorted()
    }
}

/** People reachable through the linked account. Tapping opens the dial pad
 *  prefilled (the Call button there owns the mic permission). */
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
                        peers.forEach { peer ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable(
                                        onClick = {
                                            navigateTo(screenFactory = { activity ->
                                                DialScreen(activity, peer)
                                            })
                                        },
                                    )
                                    .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 1f.gridUnitsAsDp()),
                            ) {
                                LightText(text = peer, variant = LightTextVariant.Copy)
                            }
                        }
                    }
                }
            }
        }
    }
}
