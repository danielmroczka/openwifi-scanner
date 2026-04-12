package com.example.wifi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectCoordinatorTest {

    @Test
    fun `disconnects and marks portal when captive portal appears`() = runTest {
        val connector = FakeConnector(ConnectAttemptResult.Connected)
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(listOf(WifiNetwork("OpenOne", "[ESS]", -50)))),
            connector = connector,
            captivePortalChecker = FakePortalChecker(
                statuses = mutableListOf(
                    CaptivePortalStatus.CAPTIVE_PORTAL,
                    CaptivePortalStatus.CAPTIVE_PORTAL
                )
            )
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertTrue(result.captivePortalDetected)
        assertFalse(result.hasValidatedInternet)
        assertEquals(1, connector.disconnectCalls)
    }

    @Test
    fun `returns success when validated internet is available`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(listOf(WifiNetwork("OpenTwo", "[ESS]", -45)))),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(
                statuses = mutableListOf(
                    CaptivePortalStatus.UNKNOWN,
                    CaptivePortalStatus.OPEN_INTERNET
                )
            )
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 3,
            onUpdate = {}
        )

        assertTrue(result.hasValidatedInternet)
        assertEquals("OpenTwo", result.currentSsid)
        assertEquals(4, result.attempts)
    }

    private class FakeScanner(private val result: Result<List<WifiNetwork>>) : WifiScanner {
        override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = result
    }

    private class FakeConnector(private val result: ConnectAttemptResult) : WifiConnector {
        var disconnectCalls: Int = 0

        override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult = result

        override fun disconnectCurrentNetwork() {
            disconnectCalls += 1
        }
    }

    private class FakePortalChecker(private val statuses: MutableList<CaptivePortalStatus>) : CaptivePortalChecker {
        override fun getStatus(): CaptivePortalStatus {
            return if (statuses.isNotEmpty()) {
                statuses.removeAt(0)
            } else {
                CaptivePortalStatus.UNKNOWN
            }
        }
    }
}

