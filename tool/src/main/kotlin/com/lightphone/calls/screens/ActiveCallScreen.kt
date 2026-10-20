package com.lightphone.calls.screens

import android.Manifest
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.lightphone.calls.R
import com.lightphone.calls.server.WaCallEngine
import com.lightphone.calls.server.VideoUiState
import com.lightphone.calls.server.bridge.WaEvent
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.audio.LightBluetooth
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.rememberLightHapticClick
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ActiveCallViewModel : LightViewModel<Unit>() {
    companion object {
        private const val TAG = "ActiveCall"
    }

    /** True once `CallStarted` arrived — false shows "connecting…" (F1). An
     *  accepted incoming call replays its `CallStarted` through the engine's
     *  StateFlow and connects immediately. */
    val connected = MutableStateFlow(false)
    val muted = MutableStateFlow(false)
    val speaker = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Distinguishes a flash that leaves the screen (failed placement) from
     *  an in-call error line that just clears (video failure etc.). */
    val fatal = MutableStateFlow(false)

    /** The video plane's reduced state + peer orientation (engine-owned). */
    val videoState = WaCallEngine.videoState
    val peerVideoOn = WaCallEngine.peerVideoOn
    val peerOrientation = WaCallEngine.peerOrientation
    val peerFrameSize = WaCallEngine.peerFrameSize

    // Increments on every video tap that finds CAMERA ungranted, so each tap
    // re-arms the permission request even after a denial (the mic pattern).
    val cameraRequestTick = MutableStateFlow(0)

    init {
        // The engine's error line mirrors here so in-call failures (mic FGS
        // blocked, video failed) ride the same ErrorFlash.
        viewModelScope.launch {
            WaCallEngine.error.collect { error.value = it }
        }
    }

    /** The peer answered (outgoing) or the replayed event confirmed the
     *  accepted incoming call. */
    fun onStarted() {
        connected.value = true
    }

    /** Outgoing placement, run by the call panel the dial tap opened (the
     *  native shows the panel immediately, not after the offer lands). The
     *  wait spans the full native no-answer window: `Ringing` fires when the
     *  offer send is acknowledged — the bridge bounds that send itself
     *  (~10s), so the panel's old 10s budget raced it and declared live
     *  calls "failed" while they were still connecting; `CallEnded` arrives
     *  on decline / no-answer / peer-cancel. Only a send still hung past the
     *  60s budget flashes "call failed" — and it is terminated, never
     *  orphaned (the orphan kept ringing the peer and could still connect:
     *  "connected but nothing on the phone", LP3 2026-09-22). */
    fun place(peer: String) {
        viewModelScope.launch {
            try {
                WaCallEngine.parkEvents()
                val routed = withTimeoutOrNull(60_000) {
                    WaCallEngine.placeCall(peer)
                    WaCallEngine.events.first {
                        it is WaEvent.Ringing || it is WaEvent.CallStarted || it is WaEvent.CallEnded
                    }
                }
                if (routed is WaEvent.CallEnded || routed == null) {
                    if (routed == null) WaCallEngine.endCall()
                    WaCallEngine.dropCurrentCall()
                    Log.w(TAG, "place failed: routed=$routed")
                    fatal.value = true
                    error.value = "call failed"
                }
            } catch (e: Exception) {
                Log.w(TAG, "place failed with exception", e)
                WaCallEngine.dropCurrentCall()
                fatal.value = true
                error.value = "call failed"
            }
        }
    }

    fun toggleMute() {
        val next = !muted.value
        viewModelScope.launch {
            try {
                WaCallEngine.setMuted(next)
                muted.value = next
            } catch (e: Exception) {
                error.value = "mute failed"
            }
        }
    }

    fun toggleSpeaker() {
        speaker.value = !speaker.value
        WaCallEngine.setSpeaker(speaker.value)
    }

    /** Video toggle (icon row): off → start our direction; on → stop. The
     *  upgrade prompt is its own accept/decline row — the toggle ignores it.
     *  Starting (and accepting a peer upgrade) opens our camera, so the tap
     *  first needs the CAMERA grant: ungranted taps request it (mic
     *  grammar), the engine holds the no-grant-no-camera backstop. */
    fun toggleVideo() {
        when (videoState.value) {
            VideoUiState.On -> WaCallEngine.stopVideo()
            VideoUiState.Off -> viewModelScope.launch {
                if (cameraPermissionGranted()) {
                    WaCallEngine.startVideo()
                } else {
                    cameraRequestTick.value += 1
                }
            }
        }
    }

    /** The peer-video TextureView's lifecycle → the engine's decode half. */
    fun attachSurface(surface: Surface?) = WaCallEngine.attachPeerSurface(surface)

    fun end() = WaCallEngine.endCall()
}

/** The live call: peer, "connecting…" until `CallStarted`, mute and speaker
 *  toggles (underline = on), × to end. `CallEnded` (exactly-once,
 *  bridge-deduped) returns to the call log — the engine has already torn
 *  down the audio plane, the service and the log. */
class ActiveCallScreen(
    sealedActivity: SealedLightActivity,
    private val peer: String,
    /** Outgoing: the dial tap already routed here — place the offer now. */
    private val placeOutgoing: Boolean = false,
) : LightScreen<Unit, ActiveCallViewModel>(sealedActivity) {

    override val viewModelClass: Class<ActiveCallViewModel>
        get() = ActiveCallViewModel::class.java

    override fun createViewModel(): ActiveCallViewModel = ActiveCallViewModel()

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val connected by viewModel.connected.collectAsState()
        val muted by viewModel.muted.collectAsState()
        val speaker by viewModel.speaker.collectAsState()
        val error by viewModel.error.collectAsState()
        val fatal by viewModel.fatal.collectAsState()
        val bluetoothConnected by LightBluetooth.connected.collectAsState()
        val videoState by viewModel.videoState.collectAsState()
        val peerVideoOn by viewModel.peerVideoOn.collectAsState()
        val orientation by viewModel.peerOrientation.collectAsState()
        // The peer stream's decoded size (decoder output format via the
        // engine): the cover scale needs the source aspect, never the view's.
        val videoSize by viewModel.peerFrameSize.collectAsState()
        val cameraRequestTick by viewModel.cameraRequestTick.collectAsState()
        val cameraLauncher = rememberPermissionRequestLauncher(Manifest.permission.CAMERA)

        LaunchedEffect(cameraRequestTick) {
            if (cameraRequestTick > 0) cameraLauncher?.launch()
        }
        val videoOn = videoState is VideoUiState.On
        // Native "call ended" panel (PHONE-PANELS.md §5.3: the bundle's
        // {title:'call ended', lightenTitle:true} DisplayArea overlay):
        // flashes before the log returns, for hang-up and peer-ended alike.
        var ended by remember { mutableStateOf(false) }
        val haptic = rememberLightHapticClick()

        // A buzz marks the two call-state transitions (feedback 2026-09-22):
        // the peer answering and the call ending.
        LaunchedEffect(connected) { if (connected) haptic() }
        LaunchedEffect(ended) { if (ended) haptic() }

        LaunchedEffect(Unit) {
            if (placeOutgoing) viewModel.place(peer)
            WaCallEngine.events.collect { event ->
                when (event) {
                    is WaEvent.CallStarted -> viewModel.onStarted()
                    is WaEvent.CallEnded -> ended = true
                    else -> Unit
                }
            }
        }

        // Native Phone call-screen grammar (calls/PHONE-PANELS.md §1/§5):
        // lowercase status line (copy, lighten) above the peer (subtitle,
        // shrinking to heading past the native 22-character-unit fit check),
        // icon toggle row with an underline marking the active one (native
        // order mute · speaker · bluetooth; no dialpad toggle — the bridge
        // has no DTMF), single × hang-up centered at the bottom. No timer,
        // no top bar.
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // Peer video. TextureView, not SurfaceView: a SurfaceView's
                // SurfaceFlinger-composited layer does not honor render-node/
                // view rotation (position/size sync only) — the rotation
                // silently did nothing (LP3↔emulator retest 2026-09-25);
                // TextureView draws in the view hierarchy, so the transform
                // applies. Composed for the whole call and attached once; the
                // decode half starts when video turns On (or CallStarted with
                // video) and this surface is attached.
                //
                // The buffer→view geometry is applied via setTransform: a
                // TextureView otherwise STRETCHES the decoder buffer over its
                // own bounds, which is what read as "very zoomed in" (a
                // 1280x720 frame stretched over 1080x1240, then cover-scaled
                // on top — feedback 2026-10-13). Instead: aspect-true, rotated
                // by the peer's camera orientation, scaled so the frame
                // exactly fills the horizontal edges, centered vertically.
                fun applyVideoTransform(view: TextureView) {
                    val src = videoSize
                    if (src == null || src.first <= 0 || src.second <= 0 ||
                        view.width <= 0 || view.height <= 0
                    ) {
                        return
                    }
                    val (sw, sh) = src
                    val w = view.width.toFloat()
                    val h = view.height.toFloat()
                    // WhatsApp convention (real-call verified 2026-10-13):
                    // the receiver rotates the frame (k+1)*90 clockwise — a
                    // portrait phone signals 1 with 180°-held frames; a
                    // desktop webcam signals 3 with upright frames. The old
                    // k*90 was one step CCW short. Odd steps swap the frame's
                    // on-screen dims.
                    val rot = (orientation + 1) % 4
                    val rotatedW = if (rot % 2 == 1) sh else sw
                    val scale = w / rotatedW.toFloat()
                    // setTransform operates in LAYER pixels, where the buffer
                    // is already drawn stretched over the view (HWUI
                    // Layer::draw applies the buffer→layer textureMatrix
                    // before this matrix — AOSP main, Layer.cpp). Undo that
                    // stretch first, then map the true-aspect frame: rotate,
                    // scale to fill the horizontal edges exactly, center.
                    val unstretch = Matrix().apply { setScale(sw / w, sh / h) }
                    val fit = Matrix().apply {
                        postTranslate(-sw / 2f, -sh / 2f)
                        postRotate(rot * 90f)
                        postScale(scale, scale)
                        postTranslate(w / 2f, h / 2f)
                    }
                    view.setTransform(Matrix().apply { setConcat(fit, unstretch) })
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    viewModel.attachSurface(Surface(surface))
                                    applyVideoTransform(this@apply)
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surface: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    applyVideoTransform(this@apply)
                                }

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    viewModel.attachSurface(null)
                                    return true // release the texture with the view
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    },
                    update = { view -> applyVideoTransform(view) },
                )
                // Cover the video area while no video shows (ours off, or the
                // peer's camera stopped — the decode half is down and a
                // TextureView keeps showing its last decoded frame otherwise).
                if (!videoOn || !peerVideoOn) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LightThemeTokens.colors.background),
                    )
                }
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    // Feedback 2026-09-22: content vertically centered (was
                    // pinned to the native 384px top offset).
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LightText(
                            text = if (connected) "connected" else "connecting...",
                            variant = LightTextVariant.Copy,
                        )
                        Spacer(modifier = Modifier.padding(top = 2.73f.gridUnitsAsDp()))
                        LightText(
                            text = peerDisplay(peer),
                            variant = if (peerDisplay(peer).length <= PEER_FIT_CHARS) {
                                LightTextVariant.Subtitle
                            } else {
                                LightTextVariant.Heading
                            },
                        )
                        Spacer(modifier = Modifier.padding(top = 1.25f.gridUnitsAsDp()))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ToggleIcon(
                                icon = LightIcons.MUTE,
                                active = muted,
                                contentDescription = if (muted) "Unmute" else "Mute",
                                onClick = viewModel::toggleMute,
                            )
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            ToggleIcon(
                                icon = LightIcons.SPEAKER,
                                active = speaker,
                                contentDescription = if (speaker) "Earpiece" else "Speaker",
                                onClick = viewModel::toggleSpeaker,
                            )
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            ToggleIcon(
                                icon = if (bluetoothConnected) {
                                    LightIcons.BLUETOOTH_CONNECTED
                                } else {
                                    LightIcons.BLUETOOTH
                                },
                                active = bluetoothConnected,
                                contentDescription = if (bluetoothConnected) {
                                    "Bluetooth connected"
                                } else {
                                    "Bluetooth settings"
                                },
                                onClick = { openBluetoothSettings() },
                            )
                            Spacer(modifier = Modifier.padding(start = 3f.gridUnitsAsDp()))
                            ToggleIcon(
                                icon = LightIcons.CAMERA,
                                active = videoOn,
                                contentDescription = if (videoOn) "Stop video" else "Start video",
                                onClick = viewModel::toggleVideo,
                            )
                        }
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.CLOSE,
                            // Native grammar: the hang-up shows "call ended"
                            // immediately — the terminate runs fire-and-forget
                            // and must not gate the panel on the bridge's
                            // `CallEnded` (which never arrives while the offer
                            // is still placing).
                            onClick = {
                                ended = true
                                viewModel.end()
                            },
                            contentDescription = "End call",
                        ),
                    ),
                )
            }
                // Sibling of the screen Column (not a child): the flash is a
                // true fullscreen overlay — no chrome visible around it.
                ErrorFlash(
                    message = if (ended) "call ended" else error,
                    onDismiss = {
                        viewModel.error.value = null
                        // A failed placement returns to the dialer; an ended
                        // call to the log. An in-call line (video failed,
                        // camera loss) just clears.
                        if (ended || fatal) goBack()
                    },
                )
            }
        }
    }
}

/** An in-call toggle: icon with a bar underneath when active — the native
 *  call screen's underline-means-on grammar. Bar measured off the native
 *  capture (reference/lp3/screens/phone/call-connected.png): ≈80 px wide
 *  (icon width) and ≈10 px thick — 1.8gu × 0.2gu. */
@Composable
private fun ToggleIcon(
    icon: LightIconConfiguration,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightIcon(icon = icon, contentDescription = contentDescription)
        Box(
            modifier = Modifier
                .width(2.35f.gridUnitsAsDp())
                .height(0.3f.gridUnitsAsDp())
                .background(
                    if (active) {
                        LightThemeTokens.colors.content
                    } else {
                        Color.Transparent
                    },
                ),
        )
    }
}

/** A confirm-panel-style action icon (the upgrade prompt's decline × and
 *  accept △, same drawables as the unlink confirm panel's bottom bar) sized
 *  to the native in-call icon (2 grid units, §5.4). */
@Composable
private fun ConfirmIcon(
    drawable: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.gridUnitsAsDp()),
    ) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = contentDescription,
            tint = LightThemeTokens.colors.content,
            modifier = Modifier.size(2f.gridUnitsAsDp()),
        )
    }
}

/** The native call screen's peer fit check: `Subtitle` while the formatted
 *  number fits the 22-character budget, `Heading` past it (the native §5.3
 *  rule). ActiveCall tail-truncates; incoming shows the full label. Status
 *  lines stay `Copy` (smaller than the peer), flashes ride `Subtitle`. */
internal const val PEER_FIT_CHARS = 22

internal fun peerDisplay(peer: String): String {
    val label = callPeerLabel(peer)
    return if (label.length > PEER_FIT_CHARS) {
        label.take(PEER_FIT_CHARS) + "…"
    } else {
        label
    }
}
