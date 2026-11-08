package com.example.kidsafe.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts location sharing after the phone reboots, so the child never has to re-open the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (PairingRepository(context).isPaired && Permissions.allGranted(context)) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }
    }
}
