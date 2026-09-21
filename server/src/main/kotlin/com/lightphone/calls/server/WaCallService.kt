package com.lightphone.calls.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * The `microphone` foreground service fronting an active call. Started from
 * [WaCallEngine] when `CallStarted` arrives (the answer/dial tap has granted
 * RECORD_AUDIO and the app is foreground-visible — SPEC §3.1); stopped on
 * `CallEnded`. Also owns the call audio routing: communication mode while
 * the call lives, earpiece↔speaker for the active-call screen's toggle.
 */
class WaCallService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Bluetooth state for the call screen's toggle (permission-free;
        // idempotent process-wide singleton — started here because the tool
        // module can't touch Context).
        com.thelightphone.sdk.audio.LightBluetooth.observe(this)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val TAG = "WaCallService"

        /** Starts the microphone FGS for an active call; returns false if
         *  Android blocked the start (background FGS restriction). */
        fun tryStart(context: Context): Boolean = try {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, WaCallService::class.java),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "call service start blocked: ${e.message}")
            false
        }

        fun tryStop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, WaCallService::class.java),
                )
            }
        }

        /** Routes call audio to the built-in speaker (or back to the
         *  earpiece). `setCommunicationDevice` is API 31+ — minSdk is 34, so
         *  the level check is moot; the whole call is guarded anyway. */
        fun setSpeaker(context: Context, speaker: Boolean) {
            runCatching {
                val am = context.applicationContext.getSystemService(AudioManager::class.java)
                val type = if (speaker) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                am.availableCommunicationDevices.firstOrNull { it.type == type }?.let { device ->
                    am.setCommunicationDevice(device)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, notification())
        audioManager().mode = AudioManager.MODE_IN_COMMUNICATION
        holdCallLocks()
        return START_NOT_STICKY
    }

    /** CPU + wifi held for the call's lifetime (SPEC §3.1): mic capture,
     *  playout and the bridge audio path must survive screen-off. WAKE_LOCK
     *  is the only permission either lock needs — already in this module's
     *  manifest; ACCESS_WIFI_STATE is not required for a WifiLock. */
    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is deprecated in 34; LOW_LATENCY needs hardware support
    private fun holdCallLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.lightphone.calls:call").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "com.lightphone.calls:call-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        audioManager().mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun audioManager(): AudioManager = getSystemService(AudioManager::class.java)

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("calls", "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "calls")
            .setContentTitle("WhatsApp call")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pi)
            .build()
    }
}
