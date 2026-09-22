package com.lightphone.calls.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.calls.server.ContactEntry
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
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * Contact panel — layout copied from the chats tool's ContactScreen: no top
 * bar; the identity block sits near the screen's vertical center (name — or
 * the number when the peer has no push name — the formatted number as the
 * second Heading line). The bottom bar carries the call button in the left
 * slot (opens the dial pad prefilled; that screen owns the mic permission —
 * its dismiss pops back here) and an X to dismiss.
 */
class ContactInfoScreen(
    sealedActivity: SealedLightActivity,
    private val contact: ContactEntry,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val name = contact.name.ifEmpty { formatPeer(contact.id) }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                Spacer(modifier = Modifier.weight(1.4f))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(
                        text = name,
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (contact.name.isNotEmpty()) {
                        LightText(
                            text = formatPeer(contact.id),
                            variant = LightTextVariant.Heading,
                            align = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                LightBottomBar(
                    modifier = Modifier.navigationBarsPadding(),
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CALL,
                            onClick = {
                                navigateTo(screenFactory = { activity ->
                                    DialScreen(activity, contact.id)
                                })
                            },
                            contentDescription = "Call",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { goBack(Unit) },
                            contentDescription = "Close contact",
                        ),
                        null,
                    ),
                )
            }
        }
    }
}
