package com.lightphone.calls.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the sync FGS when the system hands control back to the app:
 *  boot (START_STICKY alone does not survive a reboot) and package update
 *  (`adb install -r` kills the process with no other restart path — the
 *  incoming-call miss after every reinstall). A first-ever install gets no
 *  broadcast; the ContentProvider hook on the first tool launch covers it. */
class WaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> WaSyncService.tryStart(context)
        }
    }
}
