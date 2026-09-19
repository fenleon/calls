package com.lightphone.calls.server

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process

private const val RATE = 16000
private const val FRAME = 960 // 60 ms @16 kHz — the library's operating point

/**
 * The mic↔playout pair for one call: a capture thread pushing 960-sample
 * i16 frames into the bridge (drop-oldest queue on the Rust side, so a slow
 * engine never blocks capture) and a playout thread pulling frames out
 * (an empty poll means starved — play silence for that frame). Started by
 * [WaCallEngine] on `CallStarted`, stopped on `CallEnded`.
 */
class AudioLink(private val bridge: com.lightphone.calls.server.bridge.WaBridge) {
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playoutThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2).apply { startRecording() }
        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // capture thread
            val buf = ShortArray(FRAME)
            while (running) {
                when (rec?.read(buf, 0, FRAME)) {
                    FRAME -> runCatching { bridge.sendMicPcm(buf.toList()) }
                    // null (released) or negative (error) — back off so a
                    // wedged AudioRecord idles instead of busy-spinning.
                    else -> {
                        runCatching { Thread.sleep(50) }
                        continue
                    }
                }
            }
        }.apply { start() }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build().apply { play() }
        playoutThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // playout thread
            while (running) {
                val pcm = bridge.recvPlayoutPcm(50u)
                if (pcm.isNotEmpty()) track?.let { t ->
                    // Join-timeout edge: the track can be released between
                    // the `running` check and this write.
                    runCatching { t.write(pcm.toShortArray(), 0, pcm.size) }
                }
                // empty = starved → silence (engine paces playout; see SPEC §2)
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        // A blocking read/write frame is at most 60 ms — let both threads
        // observe `running = false` before the endpoints are released.
        captureThread?.join(200)
        playoutThread?.join(200)
        captureThread = null; playoutThread = null
        rec?.run { stop(); release() }; track?.run { stop(); release() }
        rec = null; track = null
    }
}
