package com.lightphone.calls.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class PairingViewModel(initialPayload: String?) : LightViewModel<Unit>() {
    /** Latest QR payload; updated in place from PairingQr rotations so the
     *  already-shown screen never goes stale (HomeScreen won't re-navigate). */
    val qrPayload = MutableStateFlow(initialPayload)

    init {
        viewModelScope.launch {
            WaCallEngine.events.collect { event ->
                if (event is WaEvent.PairingQr) qrPayload.value = event.payload
            }
        }
    }
}

/**
 * WhatsApp pairing: the bot's pairing QR, rendered monochrome from theme
 * colors. The SDK's navigateTo pushes (LightActivity backStack.add), so QR
 * rotations must not re-trigger navigation — this screen observes them itself
 * and updates the QR in place. When pairing completes (`Paired` arrives), the
 * screen pops back to the call log.
 */
class PairingScreen(
    sealedActivity: SealedLightActivity,
    private val initialPayload: String?,
) : LightScreen<Unit, PairingViewModel>(sealedActivity) {

    override val viewModelClass: Class<PairingViewModel>
        get() = PairingViewModel::class.java

    override fun createViewModel(): PairingViewModel = PairingViewModel(initialPayload)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val qrPayload by viewModel.qrPayload.collectAsState()

        LaunchedEffect(Unit) {
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.Paired -> goBack()
                    else -> Unit
                }
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                    ),
                    center = LightTopBarCenter.Text("Link WhatsApp"),
                )
                Spacer(modifier = Modifier.padding(top = 1f.gridUnitsAsDp()))
                LightText(
                    text = "Scan the QR code to link to your account",
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                )
                Spacer(modifier = Modifier.padding(top = 2f.gridUnitsAsDp()))
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    qrPayload?.let { payload ->
                        Qr(
                            payload,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f),
                        )
                    } ?: run {
                        // No spinner in the Light icon set — a lowercase
                        // status word is the design system's loading state
                        // (same grammar as "connecting…" / "incoming…").
                        LightText(
                            text = "loading…",
                            variant = LightTextVariant.Copy,
                        )
                    }
                }
                Spacer(modifier = Modifier.padding(bottom = 2f.gridUnitsAsDp()))
                LightBottomBar(
                    items = listOf(
                        LightBarButton.Text(
                            text = "CANCEL",
                            onClick = { goBack() },
                        ),
                    ),
                )
            }
        }
    }
}

/** The pairing QR as a monochrome bitmap — dark modules in the content color
 *  on the background color (no fixed palette; follows the active theme). */
@Composable
private fun Qr(payload: String, modifier: Modifier = Modifier) {
    val content = LightThemeTokens.colors.content
    val background = LightThemeTokens.colors.background
    val bitmap = remember(payload, content, background) {
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            512,
            512,
            mapOf(EncodeHintType.MARGIN to 1),
        )
        val size = matrix.width
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix.get(x, y)) content.toArgb() else background.toArgb()
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "WhatsApp pairing QR code",
        modifier = modifier.aspectRatio(1f),
    )
}
