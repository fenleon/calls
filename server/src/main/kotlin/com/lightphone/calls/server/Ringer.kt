package com.lightphone.calls.server

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Incoming-call ringer using the phone's own ringer: the default ringtone on
 * the ring stream (follows the user's ringer volume) while the ringer mode is
 * NORMAL, a repeating vibration in VIBRATE, nothing in SILENT. Stopped on
 * answer / decline / missed / end.
 */
object Ringer {
    private var ringtone: Ringtone? = null
    private var vibrator: android.os.Vibrator? = null

    fun start(context: Context) {
        stop()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
                runCatching {
                    RingtoneManager.getRingtone(context, uri)?.apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        isLooping = true
                        play()
                    }?.let { ringtone = it }
                }
            }
            AudioManager.RINGER_MODE_VIBRATE -> {
                val vib = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
                vib.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, 0))
                vibrator = vib
            }
            // RINGER_MODE_SILENT: honoured — nothing plays.
            else -> Unit
        }
    }

    fun stop() {
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    /** LP3-style buzz: one long pulse, pause, repeat. */
    private val VIBRATE_PATTERN = longArrayOf(0, 600, 800)
}
