package com.lightphone.calls.server

import android.media.AudioManager
import android.media.ToneGenerator

/** End-of-call tone. The LP3 dialer ships no end-call audio asset (dialer
 *  decode has none — AOSP Telecom synthesizes its tones), so use the
 *  platform's double-beep; pending ear-check against the real device. */
object CallTone {
    private var generator: ToneGenerator? = null

    @Synchronized
    fun playDisconnect() {
        try {
            generator?.release()
            generator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME).also {
                it.startTone(ToneGenerator.TONE_PROP_BEEP2, DURATION_MS)
            }
        } catch (_: RuntimeException) {
            // ToneGenerator creation can fail on exhausted audio resources —
            // a missing end tone beats a crash.
        }
    }

    private const val VOLUME = 80
    private const val DURATION_MS = 300
}
