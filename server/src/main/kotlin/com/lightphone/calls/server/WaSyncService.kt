package com.lightphone.calls.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

class WaSyncService : Service() {
    override fun onCreate() { super.onCreate(); createChannel() }

    companion object {
        private const val TAG = "WaSyncService"

        /** Starts the dataSync FGS holding the WhatsApp WebSocket; returns
         *  false if Android blocked the start (background FGS restriction). */
        fun tryStart(context: Context): Boolean = try {
            context.applicationContext.startForegroundService(
                Intent(context.applicationContext, WaSyncService::class.java),
            )
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "sync service start blocked: ${e.message}")
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("sync", "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun notification(): Notification {
        val pi = android.app.PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "sync")
            .setContentTitle("WhatsApp")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pi)
            .build()
    }
}
