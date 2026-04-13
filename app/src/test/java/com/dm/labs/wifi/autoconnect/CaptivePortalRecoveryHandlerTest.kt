package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.model.BackgroundAutoConnectState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptivePortalRecoveryHandlerTest {

    @Test
    fun `recover calls solver for detected captive portal and marks connected when solved`() = runTest {
        val pushedStates = mutableListOf<BackgroundAutoConnectState>()
        val solverSsids = mutableListOf<String?>()
        var disconnectCalls = 0
        val cooldownSsids = mutableListOf<String>()

        val handler = CaptivePortalRecoveryHandler(
            portalSolver = { ssid ->
                solverSsids += ssid
                true
            },
            disconnectCurrentNetwork = { disconnectCalls++ },
            putOnCooldown = { cooldownSsids += it },
            scanLog = {},
            devInfo = {},
            devWarn = {}
        )

        val initial = BackgroundAutoConnectState(
            isRunning = true,
            currentSsid = "CafeOpen",
            attempts = 4,
            captivePortalDetected = true,
            hasValidatedInternet = false
        )

        val result = handler.recover(initial, attempts = 4, pushState = { pushedStates += it })

        assertEquals(listOf("CafeOpen"), solverSsids)
        assertTrue(result.hasValidatedInternet)
        assertFalse(result.captivePortalDetected)
        assertEquals("CafeOpen", result.currentSsid)
        assertEquals(0, disconnectCalls)
        assertTrue(cooldownSsids.isEmpty())
        assertTrue(pushedStates.size >= 2)
    }

    @Test
    fun `recover disconnects and cools down when solver fails`() = runTest {
        var disconnectCalls = 0
        val cooldownSsids = mutableListOf<String>()

        val handler = CaptivePortalRecoveryHandler(
            portalSolver = { false },
            disconnectCurrentNetwork = { disconnectCalls++ },
            putOnCooldown = { cooldownSsids += it },
            scanLog = {},
            devInfo = {},
            devWarn = {}
        )

        val initial = BackgroundAutoConnectState(
            isRunning = true,
            currentSsid = "GuestOpen",
            attempts = 7,
            captivePortalDetected = true,
            hasValidatedInternet = false
        )

        val result = handler.recover(initial, attempts = 7, pushState = {})

        assertEquals(1, disconnectCalls)
        assertEquals(listOf("GuestOpen"), cooldownSsids)
        assertFalse(result.hasValidatedInternet)
        assertEquals("Portal solve failed on GuestOpen. Retrying...", result.message)
        assertEquals(7, result.attempts)
    }

    @Test
    fun `recover uses unknown ssid fallback when currentSsid is null`() = runTest {
        var disconnectCalls = 0
        val cooldownSsids = mutableListOf<String>()

        val handler = CaptivePortalRecoveryHandler(
            portalSolver = { false },
            disconnectCurrentNetwork = { disconnectCalls++ },
            putOnCooldown = { cooldownSsids += it },
            scanLog = {},
            devInfo = {},
            devWarn = {}
        )

        val initial = BackgroundAutoConnectState(
            isRunning = true,
            currentSsid = null,
            attempts = 3,
            captivePortalDetected = true,
            hasValidatedInternet = false
        )

        val result = handler.recover(initial, attempts = 3, pushState = {})

        assertEquals(1, disconnectCalls)
        assertEquals(listOf("unknown"), cooldownSsids)
        assertFalse(result.hasValidatedInternet)
        assertEquals("Portal solve failed on unknown. Retrying...", result.message)
        assertEquals(3, result.attempts)
    }
}

