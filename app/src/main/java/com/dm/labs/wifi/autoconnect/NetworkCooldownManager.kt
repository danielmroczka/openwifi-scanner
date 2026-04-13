package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.log.DevLog

/**
 * Tracks networks that failed to provide internet after connecting.
 * Networks are put on cooldown for 1 hour before being retried.
 */
object NetworkCooldownManager {
    private const val COOLDOWN_MS = 60 * 60 * 1000L // 1 hour

    // Key: SSID, Value: timestamp when cooldown expires
    private val cooldowns = mutableMapOf<String, Long>()

    fun putOnCooldown(ssid: String) {
        val until = System.currentTimeMillis() + COOLDOWN_MS
        cooldowns[ssid] = until
        DevLog.i("Network '$ssid' put on 1-hour cooldown (until ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(until))})")
    }

    fun isOnCooldown(ssid: String): Boolean {
        val until = cooldowns[ssid] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldowns.remove(ssid)
            return false
        }
        return true
    }

    /** Returns set of SSIDs currently on cooldown (cleans expired entries). */
    fun getCooldownSsids(): Set<String> {
        val now = System.currentTimeMillis()
        cooldowns.entries.removeAll { it.value <= now }
        return cooldowns.keys.toSet()
    }

    fun clear() {
        cooldowns.clear()
    }
}

