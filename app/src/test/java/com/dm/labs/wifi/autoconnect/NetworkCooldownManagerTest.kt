package com.dm.labs.wifi.autoconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkCooldownManagerTest {

    @Test
    fun `putOnCooldown marks ssid as cooling down and exposes it in list`() {
        NetworkCooldownManager.clear()

        NetworkCooldownManager.putOnCooldown("Cafe")

        assertTrue(NetworkCooldownManager.isOnCooldown("Cafe"))
        assertEquals(setOf("Cafe"), NetworkCooldownManager.getCooldownSsids())
        NetworkCooldownManager.clear()
    }

    @Test
    fun `clear removes all cooldown entries`() {
        NetworkCooldownManager.clear()
        NetworkCooldownManager.putOnCooldown("Cafe")
        NetworkCooldownManager.putOnCooldown("Library")

        NetworkCooldownManager.clear()

        assertFalse(NetworkCooldownManager.isOnCooldown("Cafe"))
        assertTrue(NetworkCooldownManager.getCooldownSsids().isEmpty())
    }
}

