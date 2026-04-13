package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.data.WifiNetworkEntity
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectCoordinatorTest {

    @Before
    fun setUp() {
        ScanLogManager.clearLogs()
    }

    @After
    fun tearDown() {
        ScanLogManager.clearLogs()
    }

    @Test
    fun `disconnects and marks portal when captive portal appears`() = runTest {
        val connector = FakeConnector(ConnectAttemptResult.Connected)
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "OpenOne",
                            "aa:bb:cc:00:00:01",
                            "[ESS]",
                            -50
                        )
                    )
                )
            ),
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
        assertEquals(0, connector.disconnectCalls)
    }

    @Test
    fun `returns success when validated internet is available`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "OpenTwo",
                            "aa:bb:cc:00:00:02",
                            "[ESS]",
                            -45
                        )
                    )
                )
            ),
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

    @Test
    fun `stop signal returns stopped state immediately`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "Net",
                            "aa:bb:cc:00:00:03",
                            "[ESS]",
                            -50
                        )
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { true },
            previousAttempts = 5,
            onUpdate = {}
        )

        assertFalse(result.isRunning)
        assertEquals(5, result.attempts)
        assertEquals("Stopped", result.message)
    }

    @Test
    fun `scan failure logs error and returns running state`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.failure(RuntimeException("Wi-Fi off"))),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertTrue(result.isRunning)
        assertTrue(result.message.contains("Scan failed"))

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Scan failed") })
    }

    @Test
    fun `empty network list returns retrying state`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(emptyList())),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 2,
            onUpdate = {}
        )

        assertTrue(result.isRunning)
        assertEquals(2, result.attempts)
        assertTrue(result.message.contains("No open networks found"))
    }

    @Test
    fun `logs trying message for each network`() = runTest {
        val networks = listOf(
            WifiNetwork("Net1", "aa:bb:cc:00:00:04", "[ESS]", -40),
            WifiNetwork("Net2", "aa:bb:cc:00:00:05", "[ESS]", -60)
        )
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeConnector(ConnectAttemptResult.Failed("unavailable")),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Trying Net1") })
        assertTrue(logs.any { it.message.contains("Trying Net2") })
    }

    @Test
    fun `failed connection logs reason and continues to next network`() = runTest {
        val networks = listOf(
            WifiNetwork("BadNet", "aa:bb:cc:00:00:06", "[ESS]", -50),
            WifiNetwork("GoodNet", "aa:bb:cc:00:00:07", "[ESS]", -60)
        )
        val connector = object : WifiConnector {
            var disconnectCalls = 0
            override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult {
                return if (ssid == "BadNet") ConnectAttemptResult.Failed("Auth error")
                else ConnectAttemptResult.Failed("Also failed")
            }

            override fun disconnectCurrentNetwork() {
                disconnectCalls++
            }
        }

        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = connector,
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertTrue(result.isRunning)
        assertEquals(2, result.attempts)
        assertTrue(result.message.contains("exhausted"))

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Failed to connect to BadNet: Auth error") })
        assertTrue(logs.any { it.message.contains("Failed to connect to GoodNet: Also failed") })
    }

    @Test
    fun `unsupported connection logs and stops`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "OldNet",
                            "aa:bb:cc:00:00:08",
                            "[ESS]",
                            -50
                        )
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Unsupported("Android 9 not supported")),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertFalse(result.isRunning)
        assertEquals("Android 9 not supported", result.message)

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Unsupported") })
    }

    @Test
    fun `successful connection logs validated internet`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "GoodNet",
                            "aa:bb:cc:00:00:09",
                            "[ESS]",
                            -45
                        )
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(
                statuses = mutableListOf(CaptivePortalStatus.OPEN_INTERNET)
            )
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertTrue(result.hasValidatedInternet)

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Connected to GoodNet with validated internet") })
    }

    @Test
    fun `captive portal detection logs message`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "PortalNet",
                            "aa:bb:cc:00:00:0a",
                            "[ESS]",
                            -50
                        )
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(
                statuses = mutableListOf(
                    CaptivePortalStatus.CAPTIVE_PORTAL,
                    CaptivePortalStatus.CAPTIVE_PORTAL
                )
            )
        )

        coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Captive portal detected on PortalNet") })
    }

    @Test
    fun `no internet without portal logs disconnect`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork(
                            "DeadNet",
                            "aa:bb:cc:00:00:0b",
                            "[ESS]",
                            -50
                        )
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(
                statuses = mutableListOf(
                    CaptivePortalStatus.UNKNOWN,
                    CaptivePortalStatus.UNKNOWN,
                    CaptivePortalStatus.UNKNOWN,
                    CaptivePortalStatus.UNKNOWN
                )
            )
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("No internet on DeadNet, disconnected") })
    }

    @Test
    fun `stop signal mid-iteration stops processing remaining networks`() = runTest {
        var callCount = 0
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("Net1", "aa:bb:cc:00:00:0c", "[ESS]", -40),
                        WifiNetwork("Net2", "aa:bb:cc:00:00:0d", "[ESS]", -60),
                        WifiNetwork("Net3", "aa:bb:cc:00:00:0e", "[ESS]", -70)
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Failed("fail")),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = {
                callCount++
                callCount > 2
            },
            previousAttempts = 0,
            onUpdate = {}
        )

        assertFalse(result.isRunning)
        assertEquals("Stopped", result.message)
    }

    @Test
    fun `onUpdate called for scanning and each network attempt`() = runTest {
        val updates = mutableListOf<BackgroundAutoConnectState>()
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("A", "aa:bb:cc:00:00:0f", "[ESS]", -40),
                        WifiNetwork("B", "aa:bb:cc:00:00:10", "[ESS]", -60)
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Failed("nope")),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = { updates.add(it) }
        )

        assertTrue(updates.size >= 3)
        assertTrue(updates[0].message.contains("Scanning"))
        assertTrue(updates[1].message.contains("Trying A"))
    }

    @Test
    fun `background scan logs network count`() = runTest {
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("X", "aa:bb:cc:00:00:11", "[ESS]", -40),
                        WifiNetwork("Y", "aa:bb:cc:00:00:12", "[ESS]", -50),
                        WifiNetwork("Z", "aa:bb:cc:00:00:13", "[ESS]", -60)
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Failed("fail")),
            captivePortalChecker = FakePortalChecker(statuses = mutableListOf())
        )

        coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        val logs = ScanLogManager.logs.value
        assertTrue(logs.any { it.message.contains("Background scan found 3 open networks") })
    }

    @Test
    fun `unknown network approval whitelists and connects`() = runTest {
        val repository = InMemoryWifiRepo()
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("Cafe", "aa:bb:cc:00:00:21", "[ESS]", -40)
                    )
                )
            ),
            connector = FakeConnector(ConnectAttemptResult.Connected),
            captivePortalChecker = FakePortalChecker(mutableListOf(CaptivePortalStatus.OPEN_INTERNET)),
            repository = repository
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {},
            onUnknownNetwork = { UserNetworkDecision.WHITELIST }
        )

        assertTrue(repository.isWhitelisted("aa:bb:cc:00:00:21"))
        assertTrue(result.hasValidatedInternet)
        assertEquals("Cafe", result.currentSsid)
    }

    @Test
    fun `unknown network approval blacklist skips connection`() = runTest {
        val repository = InMemoryWifiRepo()
        val connector = FakeConnector(ConnectAttemptResult.Connected)
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(
                Result.success(
                    listOf(
                        WifiNetwork("BlockMe", "aa:bb:cc:00:00:22", "[ESS]", -55)
                    )
                )
            ),
            connector = connector,
            captivePortalChecker = FakePortalChecker(mutableListOf()),
            repository = repository
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {},
            onUnknownNetwork = { UserNetworkDecision.BLACKLIST }
        )

        assertTrue(repository.isBlacklisted("aa:bb:cc:00:00:22"))
        assertEquals(0, result.attempts)
        assertEquals(0, connector.disconnectCalls)
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

    private class FakePortalChecker(private val statuses: MutableList<CaptivePortalStatus>) :
        CaptivePortalChecker {
        override fun getStatus(): CaptivePortalStatus {
            return if (statuses.isNotEmpty()) statuses.removeAt(0) else CaptivePortalStatus.UNKNOWN
        }
    }

    private class InMemoryWifiRepo : WifiNetworkRepository {
        private val store = mutableMapOf<String, WifiNetworkEntity>()

        override suspend fun getNetwork(bssid: String): WifiNetworkEntity? = store[bssid]

        override suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity> =
            store.values.filter { it.isBlacklisted }

        override suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity> =
            store.values.filter { it.isWhitelisted }

        override suspend fun getAllNetworks(): List<WifiNetworkEntity> = store.values.toList()

        override suspend fun recordNetwork(
            bssid: String,
            ssid: String,
            latitude: Double?,
            longitude: Double?
        ) {
            store[bssid] = (store[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)).copy(
                ssid = ssid,
                latitude = latitude,
                longitude = longitude
            )
        }

        override suspend fun setBlacklisted(
            bssid: String,
            ssid: String,
            blacklisted: Boolean,
            latitude: Double?,
            longitude: Double?
        ) {
            val current = store[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)
            store[bssid] = current.copy(
                ssid = ssid,
                isBlacklisted = blacklisted,
                isWhitelisted = if (blacklisted) false else current.isWhitelisted,
                latitude = latitude,
                longitude = longitude
            )
        }

        override suspend fun setWhitelisted(
            bssid: String,
            ssid: String,
            whitelisted: Boolean,
            latitude: Double?,
            longitude: Double?
        ) {
            val current = store[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)
            store[bssid] = current.copy(
                ssid = ssid,
                isWhitelisted = whitelisted,
                isBlacklisted = if (whitelisted) false else current.isBlacklisted,
                latitude = latitude,
                longitude = longitude
            )
        }

        override suspend fun deleteNetwork(bssid: String) {
            store.remove(bssid)
        }

        override suspend fun isBlacklisted(bssid: String): Boolean =
            store[bssid]?.isBlacklisted == true

        override suspend fun isWhitelisted(bssid: String): Boolean =
            store[bssid]?.isWhitelisted == true

        override suspend fun updateLocation(bssid: String, latitude: Double, longitude: Double) {
            val current = store[bssid] ?: return
            store[bssid] = current.copy(latitude = latitude, longitude = longitude)
        }
    }
}

