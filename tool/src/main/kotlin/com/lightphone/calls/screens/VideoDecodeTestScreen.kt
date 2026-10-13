package com.lightphone.calls.screens

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.calls.server.WaCallEngine
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
import com.thelightphone.sdk.ui.gridUnitsAsDp

class VideoDecodeTestViewModel : LightViewModel<Unit>() {
    val note = WaCallEngine.exerciseNote

    /** Guards the attach: the surface is handed to the engine once per
     *  texture lifetime (available or the DisposableEffect dispose). */
    private var attached = false

    /** The test screen's TextureView lifecycle → the diagnostics decode
     *  exercise (see [WaCallEngine.startDecodeExercise]). */
    fun attach(surface: Surface) {
        if (attached) return
        attached = true
        WaCallEngine.startDecodeExercise(surface)
    }

    fun detach() {
        attached = false
        WaCallEngine.stopDecodeExercise()
    }
}

/** Diagnostics only (dev aid, like the encoder exercise): replays the
 *  encoder test's archived AU capture through the real decode half into a
 *  TextureView. No call, no traffic — the stream is a local file. */
class VideoDecodeTestScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, VideoDecodeTestViewModel>(sealedActivity) {

    override val viewModelClass: Class<VideoDecodeTestViewModel>
        get() = VideoDecodeTestViewModel::class.java

    override fun createViewModel(): VideoDecodeTestViewModel = VideoDecodeTestViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val note by viewModel.note.collectAsState()
        // The decode exercise reports the stream's decoded size through the
        // engine (same flow the call screen uses).
        val videoSize by WaCallEngine.peerFrameSize.collectAsState()

        // Leaving the screen releases the decode exercise and its texture.
        DisposableEffect(Unit) {
            onDispose { viewModel.detach() }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // TextureView, matching the call screen: its listener fires
                // reliably on re-entry (the old SurfaceView's did not), so
                // no poll fallback is needed. Cover-scaled like the call
                // screen — uniform scale, center crop, no stretch.
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val src = videoSize
                            if (src != null && src.first > 0 && src.second > 0) {
                                val cover = maxOf(
                                    size.width / src.first.toFloat(),
                                    size.height / src.second.toFloat(),
                                )
                                scaleX = cover
                                scaleY = cover
                            }
                        },
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    viewModel.attach(Surface(surface))
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    viewModel.detach()
                                    return true // release the texture with the view
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2f.gridUnitsAsDp())
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LightText(text = "video decode test", variant = LightTextVariant.Copy)
                    note?.let { message ->
                        Spacer(modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()))
                        LightText(text = message, variant = LightTextVariant.Detail)
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            onClick = { goBack() },
                            contentDescription = "Close",
                        ),
                    ),
                )
            }
        }
    }
}
