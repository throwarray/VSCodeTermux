package com.example.vscodetermux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val manager = RootfsManager(context)
        if (!manager.isBootstrapped()) {
            // environment hasn't been set up.
            return
        }

        // Termux:Boot interop runs what's in $HOME/.termux/boot/
        val serviceIntent = Intent(context, ProotServerService::class.java).apply {
            action = ProotServerService.ACTION_RUN_BOOT_SCRIPTS_ONLY
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
