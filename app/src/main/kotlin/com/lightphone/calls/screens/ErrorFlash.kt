package com.lightphone.calls.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import kotlinx.coroutines.delay

/** Fullscreen failure flash — the native call screen's error display
 *  ("call failed"; the bundle renders it through the same DisplayArea as the
 *  status line, PHONE-PANELS.md §5.3): fullscreen black with the centered
 *  status-line style, auto-dismissed. */
@Composable
fun ErrorFlash(
    message: String?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(1500)
            onDismiss()
        }
    }
    if (message != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Subtitle)
        }
    }
}
