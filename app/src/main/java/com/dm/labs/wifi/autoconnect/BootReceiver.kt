package com.dm.labs.wifi.autoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dm.labs.wifi.log.DevLog
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.settings.AppSettings

/**
 * Starts the AutoConnectService on device boot if autoStartOnBoot is enabled.
 * Uses a 10-second delay to let the system settle.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        DevLog.init(context)
        val settings = AppSettings.getInstance(context)

        if (settings.autoStartOnBoot) {
            DevLog.i("Boot completed — auto-start is enabled, launching service with delay…")
            ScanLogManager.log("Device booted — starting auto-connect service…")

            // Delayed start: give system 10 seconds to settle
            Thread {
                try {
                    Thread.sleep(10_000)
                    AutoConnectService.start(context)
                } catch (e: Exception) {
                    DevLog.e("Failed to start service after boot", e)
                }
            }.start()
        } else {
            DevLog.d("Boot completed — auto-start is disabled, not starting service.")
        }
    }
}

