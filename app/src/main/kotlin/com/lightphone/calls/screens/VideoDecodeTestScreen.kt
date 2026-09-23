package com.lightphone.calls.screens

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lightphone.calls.server.WaCallEngine
import kotlinx.coroutines.delay
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

    /** Guards the attach fallback: the surface is handed to the engine once
     *  (surfaceCreated or the AndroidView update pass) and dropped once. */
    private var attached = false

    /** The test screen's SurfaceView lifecycle → the diagnostics decode
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
 *  SurfaceView. No call, no traffic — the stream is a local file. */
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

        // Leaving the screen releases the decode exercise and its surface.
        DisposableEffect(Unit) {
            onDispose { viewModel.detach() }
        }

        // A re-entered screen can miss `surfaceCreated` (the view re-attaches
        // to a window whose surface already exists) — poll for a valid
        // surface and hand it over; [VideoDecodeTestViewModel.attach] runs
        // once per surface lifetime.
        var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
        LaunchedEffect(surfaceView) {
            while (surfaceView != null) {
                surfaceView?.holder?.surface?.takeIf { it.isValid }?.let { viewModel.attach(it) }
                delay(500)
            }
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    viewModel.attach(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    viewModel.detach()
                                }
                            })
                            surfaceView = this
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
