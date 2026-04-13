package com.dm.labs.wifi.ui

import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.AutoConnectStateSource
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeAutoConnectState = MutableStateFlow(BackgroundAutoConnectState())
    private val fakeAutoConnectSource = object : AutoConnectStateSource {
        override val state: StateFlow<BackgroundAutoConnectState> =
            fakeAutoConnectState.asStateFlow()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ScanLogManager.clearLogs()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ScanLogManager.clearLogs()
    }

    private fun createVm(
        scanner: WifiScanner = FakeScanner(Result.success(emptyList())),
        connector: WifiConnector = FakeConnector(ConnectAttemptResult.Connected),
        checker: CaptivePortalChecker = FakePortalChecker(CaptivePortalStatus.OPEN_INTERNET)
    ): WifiViewModel = WifiViewModel(
        scanner = scanner,
        connector = connector,
        captivePortalChecker = checker,
        autoConnectStateSource = fakeAutoConnectSource
    )

    @Test
    fun `connect marks portal flow when captive portal is detected`() = runTest {
        val vm = createVm(
            connector = FakeConnector(ConnectAttemptResult.Connected),
            checker = FakePortalChecker(CaptivePortalStatus.CAPTIVE_PORTAL)
        )

        vm.connectToNetwork("GuestWiFi")

        val state = vm.uiState.value
        assertEquals("GuestWiFi", state.connectedSsid)
        assertTrue(state.needsPortalLogin)
        assertFalse(state.isConnecting)
        assertTrue(state.statusMessage?.contains("captcha", ignoreCase = true) == true)
    }

    @Test
    fun `scan publishes open network results`() = runTest {
        val vm = createVm(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "OpenCafe",
                            "aa:bb:cc:dd:ee:01",
                            "[ESS]",
                            -42
                        )
                    )
                )
            )
        )

        vm.scanOpenNetworks()

        val state = vm.uiState.value
        assertEquals(1, state.networks.size)
        assertEquals("OpenCafe", state.networks.first().ssid)
        assertEquals("aa:bb:cc:dd:ee:01", state.networks.first().bssid)
        assertFalse(state.isScanning)
    }

    @Test
    fun `scan updates lastScanTimestamp`() = runTest {
        val vm = createVm(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "Net1",
                            "aa:bb:cc:dd:ee:02",
                            "[ESS]",
                            -50
                        )
                    )
                )
            )
        )

        assertEquals(0L, vm.uiState.value.lastScanTimestamp)

        vm.scanOpenNetworks()

        assertTrue(vm.uiState.value.lastScanTimestamp > 0L)
    }

    @Test
    fun `scan writes log on success`() = runTest {
        val vm = createVm(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("A", "aa:bb:cc:dd:ee:03", "[ESS]", -40),
                        WifiNetwork("B", "aa:bb:cc:dd:ee:04", "[ESS]", -60)
                    )
                )
            )
        )

        vm.scanOpenNetworks()

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("2 open networks") })
    }

    @Test
    fun `scan writes log on failure`() = runTest {
        val vm = createVm(
            scanner = FakeScanner(Result.failure(RuntimeException("Wi-Fi is turned off.")))
        )

        vm.scanOpenNetworks()

        val state = vm.uiState.value
        assertFalse(state.isScanning)
        assertEquals("Wi-Fi is turned off.", state.statusMessage)

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Scan failed") })
    }

    @Test
    fun `scan with empty results shows no networks message`() = runTest {
        val vm = createVm(scanner = FakeScanner(Result.success(emptyList())))

        vm.scanOpenNetworks()

        val state = vm.uiState.value
        assertTrue(state.networks.isEmpty())
        assertEquals("No open networks found.", state.statusMessage)
    }

    @Test
    fun `startPeriodicScan triggers at least one scan`() = runTest {
        var scanCount = 0
        val scanner = object : WifiScanner {
            override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> {
                scanCount++
                return Result.success(
                    listOf(
                        WifiNetwork(
                            "Net-$scanCount",
                            "aa:bb:cc:dd:ee:0$scanCount",
                            "[ESS]",
                            -50
                        )
                    )
                )
            }
        }

        val vm = createVm(scanner = scanner)
        vm.startPeriodicScan()

        assertTrue("Expected at least 1 scan, got $scanCount", scanCount >= 1)
        vm.stopPeriodicScan()
    }

    @Test
    fun `stopPeriodicScan stops scanning`() = runTest {
        var scanCount = 0
        val scanner = object : WifiScanner {
            override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> {
                scanCount++
                return Result.success(emptyList())
            }
        }

        val vm = createVm(scanner = scanner)
        vm.startPeriodicScan()
        val countAfterStart = scanCount

        vm.stopPeriodicScan()

        assertEquals(countAfterStart, scanCount)
    }

    @Test
    fun `startPeriodicScan is idempotent`() = runTest {
        var scanCount = 0
        val scanner = object : WifiScanner {
            override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> {
                scanCount++
                return Result.success(emptyList())
            }
        }

        val vm = createVm(scanner = scanner)
        vm.startPeriodicScan()
        val countAfterFirst = scanCount
        vm.startPeriodicScan()

        assertEquals(countAfterFirst, scanCount)
        vm.stopPeriodicScan()
    }

    @Test
    fun `connect to open internet sets correct status`() = runTest {
        val vm = createVm(
            connector = FakeConnector(ConnectAttemptResult.Connected),
            checker = FakePortalChecker(CaptivePortalStatus.OPEN_INTERNET)
        )

        vm.connectToNetwork("FreeWifi")

        val state = vm.uiState.value
        assertEquals("FreeWifi", state.connectedSsid)
        assertFalse(state.needsPortalLogin)
        assertFalse(state.isConnecting)
        assertTrue(state.statusMessage?.contains("internet access") == true)
    }

    @Test
    fun `connect failure sets status message`() = runTest {
        val vm = createVm(connector = FakeConnector(ConnectAttemptResult.Failed("Timed out")))

        vm.connectToNetwork("FreeWifi")

        val state = vm.uiState.value
        assertFalse(state.isConnecting)
        assertEquals("Timed out", state.statusMessage)
    }

    @Test
    fun `connect unsupported sets status message`() = runTest {
        val vm = createVm(
            connector = FakeConnector(ConnectAttemptResult.Unsupported("Android 9 not supported"))
        )

        vm.connectToNetwork("FreeWifi")

        val state = vm.uiState.value
        assertFalse(state.isConnecting)
        assertEquals("Android 9 not supported", state.statusMessage)
    }

    @Test
    fun `connect unknown status sets correct message`() = runTest {
        val vm = createVm(
            connector = FakeConnector(ConnectAttemptResult.Connected),
            checker = FakePortalChecker(CaptivePortalStatus.UNKNOWN)
        )

        vm.connectToNetwork("MysteryNet")

        val state = vm.uiState.value
        assertEquals("MysteryNet", state.connectedSsid)
        assertFalse(state.needsPortalLogin)
        assertTrue(state.statusMessage?.contains("unknown") == true)
    }

    @Test
    fun `autoConnectState updates propagate to uiState`() = runTest {
        val vm = createVm()

        fakeAutoConnectState.value = BackgroundAutoConnectState(
            isRunning = true,
            message = "Scanning...",
            captivePortalDetected = false
        )

        val state = vm.uiState.value
        assertTrue(state.autoConnectRunning)
        assertEquals("Scanning...", state.autoConnectMessage)
    }

    private class FakeScanner(private val result: Result<List<WifiNetwork>>) : WifiScanner {
        override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = result
    }

    private class FakeConnector(private val result: ConnectAttemptResult) : WifiConnector {
        override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult = result
        override fun disconnectCurrentNetwork() = Unit
    }

    private class FakePortalChecker(private val status: CaptivePortalStatus) :
        CaptivePortalChecker {
        override fun getStatus(): CaptivePortalStatus = status
    }
}

