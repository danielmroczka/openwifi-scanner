package com.example.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiViewModelTest {

    private val testDispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `connect marks portal flow when captive portal is detected`() = runTest {
        val vm = WifiViewModel(
            scanner = FakeScanner(Result.success(emptyList())),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(CaptivePortalStatus.CAPTIVE_PORTAL)
        )

        vm.connectToNetwork("GuestWiFi")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("GuestWiFi", state.connectedSsid)
        assertTrue(state.needsPortalLogin)
        assertFalse(state.isConnecting)
        assertTrue(state.statusMessage?.contains("captcha", ignoreCase = true) == true)
    }

    @Test
    fun `scan publishes open network results`() = runTest {
        val vm = WifiViewModel(
            scanner = FakeScanner(
                Result.success(listOf(WifiNetwork("OpenCafe", "[ESS]", -42)))
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(CaptivePortalStatus.OPEN_INTERNET)
        )

        vm.scanOpenNetworks()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(1, state.networks.size)
        assertEquals("OpenCafe", state.networks.first().ssid)
        assertFalse(state.isScanning)
    }

    private class FakeScanner(private val result: Result<List<WifiNetwork>>) : WifiScanner {
        override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = result
    }

    private class FakeConnector(private val result: ConnectAttemptResult) : WifiConnector {
        override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult = result

        override fun disconnectCurrentNetwork() = Unit
    }

    private class FakePortalChecker(private val status: CaptivePortalStatus) : CaptivePortalChecker {
        override fun getStatus(): CaptivePortalStatus = status
    }
}

