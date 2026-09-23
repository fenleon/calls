package com.lightphone.calls.server

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.lightphone.calls.server.bridge.WaVideoFrame
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.Executor

/** Encode tiers (SPEC §3.3). Whatever tier runs, its [fps] must be passed to
 *  `bridge.startVideo(fps)` before this link starts on a live call — the
 *  bridge derives the RTP timestamp stride (`90_000 / fps`) from it and a
 *  mismatch with the encoder's actual pacing fails at builder time
 *  (facade.rs:214). */
enum class VideoTier(val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
    /** Default (cellular) tier. */
    DEFAULT(640, 480, 15, 600_000),

    /** WiFi tier. */
    WIFI(1280, 720, 20, 1_800_000),
}

/**
 * The encode half of the video plane: Camera2 (front camera) → `MediaCodec`
 * `video/avc` via `createInputSurface()` (no YUV plumbing) → one complete
 * Annex-B access unit per [sink] call. The decode half is [VideoDecodeLink]
 * below.
 *
 * Encoder shape (SPEC §3.3): Constrained Baseline L3.1 (Baseline profile +
 * level, which forbids B-frames; one slice/frame is the encoder default),
 * I-frame interval 1 s — GOP 30 @ 15 fps / 20 @ 20 fps.
 *
 * CSD per IDR: the SPS/PPS handed over by `BUFFER_FLAG_CODEC_CONFIG` are held
 * and prepended to every keyframe AU (any parameter sets the encoder repeats
 * in-band are stripped first) — the engine packs one STAP-A only when the AU
 * carries them, and the peer needs them to decode a keyframe it tuned in on.
 *
 * Local overflow: [sink] returning `true` (the bridge's drop-oldest queue
 * discarded the AU) asks the encoder for a sync frame
 * (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) so the peer resyncs on an IDR instead
 * of waiting out the GOP. What counts as "dropped" is the caller's policy —
 * the real-call caller passes `bridge::sendVideoAu` verbatim.
 *
 * Camera open/encode runs on one dedicated handler thread (the AudioLink
 * thread conventions); [stop] releases camera, encoder and surface. Started
 * by [WaCallEngine], stopped there and on `CallEnded`.
 *
 * Camera loss (open error, disconnect, session configure failure, late start
 * failure) tears the encode half down and reports it through [onLost] — the
 * engine stops the outbound video direction so no UI keeps claiming an
 * active encoder (V1-review carry-over).
 */
class VideoLink(
    private val tier: VideoTier = VideoTier.DEFAULT,
    /** One complete Annex-B AU per call; returns true when the AU was dropped
     *  and the encoder should be asked for a sync frame. */
    private val sink: (ByteArray) -> Boolean,
    /** Non-null: every pushed AU (post-CSD-prepend) is appended here — the
     *  AU-stream capture for ffprobe. Diagnostics only, behind the runtime
     *  opt-in flag; never set in production. */
    private val dumpFile: File? = null,
    /** Camera-loss / start-failure callback: the encode half is already torn
     *  down when it fires. */
    private val onLost: (String) -> Unit = {},
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var dump: OutputStream? = null
    @Volatile private var running = false

    /** SPS+PPS from the encoder's `INFO_OUTPUT_FORMAT_CHANGED`
     *  (BUFFER_FLAG_CODEC_CONFIG bytes) — prepended to every keyframe AU. */
    @Volatile private var csd: ByteArray? = null

    /** Diagnostics counters — logged on stop (a plain marker `volatile ++`
     *  pair is fine at AU cadence from the single callback thread). */
    private var pushedAUs = 0
    private var keyframeAUs = 0

    fun start() {
        if (running) return
        running = true
        csd = null
        pushedAUs = 0
        keyframeAUs = 0
        dump = dumpFile?.let { FileOutputStream(it) }
        thread = HandlerThread("VideoLink-encode", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        handler = Handler(thread!!.looper)
        handler!!.post { runCatching(::startOnThread).onFailure {
            Log.w(TAG, "video start failed: ${it.message}")
            failOnThread("start failed: ${it.message}")
        } }
    }

    fun stop() {
        if (!running) return
        running = false
        val h = handler ?: return
        val t = thread ?: return
        h.post { teardownOnThread() }
        // Camera session close and codec stop can each take a frame or two.
        t.join(1000)
        thread = null
        handler = null
    }

    /** Peer-driven keyframe request (`WaEvent.KeyframePressure` — a relayed
     *  PLI/FIR): asks the encoder for a sync frame so the peer's repair
     *  doesn't wait out the GOP. No-op when the encode half is inactive. */
    fun requestSyncFrame() {
        if (!running) return
        handler?.post {
            codec?.let { encoder ->
                runCatching {
                    encoder.setParameters(Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
                    })
                }.onFailure { Log.w(TAG, "sync frame request failed: ${it.message}") }
            }
        }
    }

    // --- Handler-thread side ---

    @android.annotation.SuppressLint("MissingPermission")
    private fun startOnThread() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, tier.width, tier.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, tier.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, tier.fps)
            setInteger(MediaFormat.KEY_CAPTURE_RATE, tier.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 s GOP
            setInteger(MediaFormat.KEY_BITRATE_MODE, BIT_RATE_MODE_CBR)
            // QP caps (API 35 keys, string-valued here) so the rate control
            // can't starve far below the CBR target on low-motion scenes —
            // the soft AVC encoder inserts no filler.
            setInteger("video-qp-i-max", QP_MAX)
            setInteger("video-qp-p-max", QP_MAX)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.setCallback(encoderCallback, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        inputSurface = surface

        val manager = appContext().getSystemService(CameraManager::class.java)
        val id = frontCameraId(manager)
        // Pace the sensor at the tier's fps: with surface input the encoder
        // encodes every frame the camera delivers, so KEY_FRAME_RATE alone
        // doesn't set the output cadence.
        val fpsRange = runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.let { ranges ->
                    // V1-review carry-over: only a fixed [fps, fps] range
                    // paces the sensor at the tier's fps — a containing range
                    // (e.g. [10,30]) lets it drift and mismatches the bridge's
                    // RTP timestamp stride (`90_000 / fps`). Fail loudly.
                    ranges.firstOrNull { it.lower == tier.fps && it.upper == tier.fps }
                }
        }.getOrNull()
            ?: throw IllegalStateException(
                "camera $id offers no fixed [${tier.fps},${tier.fps}] fps range — refusing to mispace the encode",
            )
        Log.i(TAG, "video encode start: ${tier.width}x${tier.height}@${tier.fps} camera=$id fpsRange=$fpsRange")
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                // A stop() between openCamera and this callback must not
                // resurrect a session after teardown.
                if (!running) {
                    device.close()
                    return
                }
                camera = device
                runCatching {
                    device.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(surface)),
                            Executor { command -> handler!!.post(command) },
                            object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!running) {
                                    s.close()
                                    return
                                }
                                session = s
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                }
                                s.setRepeatingRequest(request.build(), null, handler)
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                Log.w(TAG, "camera session configure failed")
                                s.close()
                                if (running) failOnThread("camera session configure failed")
                            }
                            },
                        ),
                    )
                }.onFailure { Log.w(TAG, "camera session failed: ${it.message}") }
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (running) failOnThread("camera disconnected")
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.w(TAG, "camera open error: $error")
                device.close()
                if (running) failOnThread("camera error: $error")
            }
        }, handler)
        }.onFailure {
            // Synchronous open failure (permission revoked, no camera) —
            // release the encoder/surface started above and surface it.
            Log.w(TAG, "camera open failed: ${it.message}")
            failOnThread("camera open failed: ${it.message}")
        }
    }

    /** Camera-loss / start-failure path (V1-review carry-over): tear the
     *  encode half down and surface it — the engine stops the outbound
     *  video direction so no UI keeps claiming an active encoder. */
    private fun failOnThread(reason: String) {
        running = false
        teardownOnThread()
        onLost(reason)
    }

    private fun teardownOnThread() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release()
        inputSurface = null
        runCatching { dump?.flush(); dump?.close() }
        dump = null
        Log.i(TAG, "video encode stop: $pushedAUs AUs ($keyframeAUs keyframes) pushed")
    }

    private fun frontCameraId(manager: CameraManager): String {
        val ids = manager.cameraIdList
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        } ?: ids.firstOrNull() ?: throw IllegalStateException("no cameras")
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!running) {
                runCatching { codec.releaseOutputBuffer(index, false) }
                return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            if (info.size > 0) {
                codec.getOutputBuffer(index)?.let { buf ->
                    buf.position(info.offset).limit(info.offset + info.size)
                    val au = ByteArray(info.size)
                    buf.get(au)
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val payload = if (isKey) prependCsd(au) else au
                    pushedAUs++
                    if (isKey) keyframeAUs++
                    // A dropped AU means the (peer's) reference chain broke —
                    // the encoder's next frame must be an IDR.
                    if (sink(payload)) {
                        codec.setParameters(Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 1)
                        })
                    }
                    runCatching { dump?.write(payload) }
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            fun csdBytes(name: String): ByteArray? =
                format.getByteBuffer(name)?.let { bb -> ByteArray(bb.remaining()).also { bb.get(it) } }
            val sps = csdBytes("csd-0")
            val pps = csdBytes("csd-1")
            csd = when {
                sps != null && pps != null -> sps + pps
                sps != null -> sps
                else -> null
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "encoder error: ${e.message}")
        }
    }

    private fun prependCsd(au: ByteArray): ByteArray {
        val params = csd ?: return au
        val stripped = stripParameterSets(au)
        return params + stripped
    }

    private fun appContext(): android.content.Context =
        WaCallEngine.appContextForVideoLink()

    companion object {
        private const val TAG = "VideoLink"

        /** `MediaCodecInfo.EncoderCapabilities.BIT_RATE_MODE_CBR` — not visible
         *  to the Kotlin compiler from the SDK stubs here. */
        private const val BIT_RATE_MODE_CBR = 2

        /** QP ceiling paired with the CBR target — low enough that even a
         *  low-motion scene spends ~the configured bits. */
        private const val QP_MAX = 28
    }
}

/** Removes SPS/PPS NALs from an Annex-B AU: the encoder repeats them in-band
 *  before IDRs and [VideoLink] prepends its held CSD copy instead, so a
 *  keyframe AU carries SPS+PPS+IDR exactly once. Walks start codes — both
 *  3-byte (`00 00 01`) and 4-byte (`00 00 00 01`; the extra zero belongs to
 *  the start code, not the previous NAL). */
internal fun stripParameterSets(au: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(au.size)
    var nalStart = -1 // first NAL byte after the current start code
    var prevNalFrom = 0 // where the current NAL's start code begins
    var i = 0
    while (i + 2 < au.size) {
        if (au[i] == 0.toByte() && au[i + 1] == 0.toByte() && au[i + 2] == 1.toByte()) {
            var scStart = i
            while (scStart > 0 && au[scStart - 1] == 0.toByte()) scStart--
            if (nalStart >= 0) emitNalUnlessParameterSet(au, prevNalFrom, scStart, nalStart, out)
            prevNalFrom = scStart
            nalStart = i + 3
            i = nalStart
        } else {
            i++
        }
    }
    if (nalStart >= 0) emitNalUnlessParameterSet(au, prevNalFrom, au.size, nalStart, out)
    return out.toByteArray()
}

private fun emitNalUnlessParameterSet(au: ByteArray, from: Int, to: Int, nalStart: Int, out: ByteArrayOutputStream) {
    val type = au[nalStart].toInt() and 0x1F
    if (type != 7 && type != 8) out.write(au, from, to - from)
}

/**
 * The decode half of the video plane: one pull thread draining [pull] (the
 * bridge's `recvVideoFrame(50)` — or the diagnostics replay source) into a
 * `MediaCodec` `video/avc` decoder rendering to [output] (the UI's
 * SurfaceView). `KEY_LOW_LATENCY=1`; the decoder is seeded by pushing the
 * first keyframe AU with `BUFFER_FLAG_CODEC_CONFIG` (every keyframe AU
 * carries SPS+PPS+IDR — the encode half's CSD-per-IDR contract), so the
 * parameter sets land before any decodable slice. AUs arriving before the
 * first keyframe are held back (dropped) — they reference data the decoder
 * doesn't have and only a future IDR can recover the picture.
 *
 * Runs entirely on its own thread; [stop] joins it and releases the codec.
 * [onOrientation] fires with the latest frame's orientation (90° steps
 * 0..3, per `WaVideoFrame.orientation`) for the UI to rotate the surface —
 * the exact convention gets verified against a real call in V4.
 * [onEnded] surfaces a crashed/dead decode path (codec error, released
 * surface); a normal [stop] does not fire it.
 */
class VideoDecodeLink(
    private val output: Surface,
    private val pull: () -> WaVideoFrame?,
    private val onOrientation: (Int) -> Unit = {},
    private val onEnded: (String) -> Unit = {},
) {
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Latest queued frame's orientation — read at render time (lags at
     *  most one frame behind the picture; fine for 90° steps). */
    @Volatile private var latestOrientation = 0

    fun start() {
        if (running) return
        running = true
        thread = Thread({ decodeLoop() }, "VideoLink-decode").apply {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val t = thread ?: return
        // recvVideoFrame polls at 5 ms internally, so the pull returns
        // within ~50 ms and the loop notices `running` right after.
        t.join(1000)
        thread = null
    }

    private fun decodeLoop() {
        var decoder: MediaCodec? = null
        try {
            // Width/height are placeholders — the decoder takes the real
            // dimensions from the seeded SPS.
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, output, null, 0)
                start()
            }
            feedAndDrain(decoder)
        } catch (e: Exception) {
            if (running) onEnded("video decode stopped: ${e.message}")
        } finally {
            decoder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
        }
    }

    private fun feedAndDrain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        var seeded = false
        var fed = 0
        var rendered = 0
        var lastLog = System.nanoTime()
        while (running) {
            val frame = pull()
            if (frame != null) {
                latestOrientation = frame.orientation.toInt() and 0xFF
                if (seeded || frame.keyframe) {
                    val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        if (frame.data.size <= buffer.capacity()) {
                            buffer.clear()
                            buffer.put(frame.data)
                            // Seed: the first keyframe AU rides in as codec
                            // config so SPS/PPS are in place before any
                            // slice is decoded.
                            val seed = !seeded
                            seeded = true
                            ptsUs += PTS_STEP_US
                            decoder.queueInputBuffer(
                                index, 0, frame.data.size, ptsUs,
                                if (seed) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
                            )
                            fed++
                        } else {
                            Log.w(TAG, "video AU too large for the decoder input (${frame.data.size} B) — dropped")
                        }
                    }
                    // else: codec backpressured — a live stream's stale AU is
                    // worth less than the delay of waiting to queue it.
                }
                // else: hold — nothing is decodable before the first keyframe.
            }
            rendered += drain(decoder, info)
            val now = System.nanoTime()
            if (now - lastLog > 2_000_000_000L) {
                Log.i(TAG, "decode: fed=$fed rendered=$rendered")
                lastLog = now
            }
        }
        Log.i(TAG, "decode loop exit: fed=$fed rendered=$rendered")
    }

    private fun drain(decoder: MediaCodec, info: MediaCodec.BufferInfo): Int {
        var rendered = 0
        while (running) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                in 0..Int.MAX_VALUE -> {
                    onOrientation(latestOrientation)
                    decoder.releaseOutputBuffer(index, true)
                    rendered++
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return rendered // TRY_AGAIN — nothing more to render right now
            }
        }
        return rendered
    }

    companion object {
        private const val TAG = "VideoLink"

        private const val INPUT_TIMEOUT_US = 10_000L

        /** Nominal 30 fps pts pacing — the decoder needs monotonic pts only
         *  (no B-frames, no A/V sync on this path). */
        private const val PTS_STEP_US = 33_333L
    }
}

/** One complete Annex-B access unit: raw bytes (start codes included) plus
 *  whether the AU carries an IDR NAL (a keyframe). */
internal class AnnexBAu(val data: ByteArray, val keyframe: Boolean)

/** Splits a raw Annex-B H.264 stream into complete access units — the
 *  diagnostics decode exercise replays the V1 encoder dump (the
 *  `video-exercise.h264` capture) through the real decode half. The encoder
 *  emits one slice per frame, so every VCL NAL (type 1..5) starts a new AU;
 *  non-VCL NALs (SPS/PPS/SEI/AUD) ride with the AU that follows them. */
internal fun parseAnnexBAus(stream: ByteArray): List<AnnexBAu> {
    // NAL k spans [scStarts[k], next scStart) — `nalStarts[k]` is its first
    // byte after the start code.
    val scStarts = mutableListOf<Int>()
    val nalStarts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < stream.size) {
        if (stream[i] == 0.toByte() && stream[i + 1] == 0.toByte() && stream[i + 2] == 1.toByte()) {
            var scStart = i
            while (scStart > 0 && stream[scStart - 1] == 0.toByte()) scStart--
            scStarts += scStart
            nalStarts += i + 3
            i += 3
        } else {
            i++
        }
    }
    val aus = mutableListOf<AnnexBAu>()
    val current = ByteArrayOutputStream()
    var hasVcl = false
    var keyframe = false
    for (k in nalStarts.indices) {
        val from = scStarts[k]
        val to = if (k + 1 < nalStarts.size) scStarts[k + 1] else stream.size
        if (from >= to) continue
        val type = stream[nalStarts[k]].toInt() and 0x1F
        if (type in 1..5 && hasVcl) {
            aus += AnnexBAu(current.toByteArray(), keyframe)
            current.reset()
            hasVcl = false
            keyframe = false
        }
        current.write(stream, from, to - from)
        if (type in 1..5) {
            hasVcl = true
            keyframe = keyframe || type == 5
        }
    }
    if (current.size() > 0) aus += AnnexBAu(current.toByteArray(), keyframe)
    return aus
}
