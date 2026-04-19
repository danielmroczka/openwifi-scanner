package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.data.WifiNetworkEntity
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for network sorting by priority (whitelist first) and signal strength (level).
 * Validates that the coordinator prioritizes:
 * 1. Whitelisted networks over unknown networks
 * 2. Stronger signal strength within each priority group
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkSortingAndPrioritizationTest {

    @Test
    fun `whitelisted networks prioritized over unknown networks`() = runTest {
        val repository = InMemoryWifiRepo()
        val networks = listOf(
            WifiNetwork("Unknown1", "aa:bb:cc:00:00:01", "[ESS]", -60),  // Unknown, weak
            WifiNetwork("WhiteListed", "aa:bb:cc:00:00:02", "[ESS]", -70), // Whitelisted, weaker
            WifiNetwork("Unknown2", "aa:bb:cc:00:00:03", "[ESS]", -50)  // Unknown, strong
        )

        // Whitelist one network
        repository.setWhitelisted("aa:bb:cc:00:00:02", "WhiteListed", true)

        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeSelectorConnector { ssid ->
                // Only connect to the first attempted network
                if (ssid == "WhiteListed") ConnectAttemptResult.Connected else ConnectAttemptResult.Failed("skip")
            },
            captivePortalChecker = FakePortalChecker(mutableListOf(CaptivePortalStatus.OPEN_INTERNET)),
            repository = repository
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        // Should connect to whitelisted network even though it has weaker signal
        assertEquals("WhiteListed", result.currentSsid)
        assertTrue(result.hasValidatedInternet)
    }

    @Test
    fun `strongest signal prioritized within unknown networks`() = runTest {
        val networks = listOf(
            WifiNetwork("Weak", "aa:bb:cc:00:00:04", "[ESS]", -80),      // Weakest
            WifiNetwork("Strong", "aa:bb:cc:00:00:05", "[ESS]", -40),     // Strongest
            WifiNetwork("Medium", "aa:bb:cc:00:00:06", "[ESS]", -60)      // Medium
        )

        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeSelectorConnector { ssid ->
                if (ssid == "Strong") ConnectAttemptResult.Connected else ConnectAttemptResult.Failed("skip")
            },
            captivePortalChecker = FakePortalChecker(mutableListOf(CaptivePortalStatus.OPEN_INTERNET))
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        // Should attempt to connect to strongest network first
        assertEquals("Strong", result.currentSsid)
        assertTrue(result.hasValidatedInternet)
    }

    @Test
    fun `strongest whitelisted network prioritized within whitelist`() = runTest {
        val repository = InMemoryWifiRepo()
        val networks = listOf(
            WifiNetwork("Weak-Whitelisted", "aa:bb:cc:00:00:07", "[ESS]", -80),
            WifiNetwork("Strong-Whitelisted", "aa:bb:cc:00:00:08", "[ESS]", -40),
            WifiNetwork("Unknown-Strong", "aa:bb:cc:00:00:09", "[ESS]", -30)  // Stronger but unknown
        )

        // Whitelist both networks
        repository.setWhitelisted("aa:bb:cc:00:00:07", "Weak-Whitelisted", true)
        repository.setWhitelisted("aa:bb:cc:00:00:08", "Strong-Whitelisted", true)

        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeSelectorConnector { ssid ->
                if (ssid == "Strong-Whitelisted") ConnectAttemptResult.Connected
                else ConnectAttemptResult.Failed("skip")
            },
            captivePortalChecker = FakePortalChecker(mutableListOf(CaptivePortalStatus.OPEN_INTERNET)),
            repository = repository
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        // Should prefer whitelisted networks and choose strongest among them
        assertEquals("Strong-Whitelisted", result.currentSsid)
        assertTrue(result.hasValidatedInternet)
    }

    @Test
    fun `blacklisted networks ignored completely`() = runTest {
        val repository = InMemoryWifiRepo()
        val networks = listOf(
            WifiNetwork("Blacklisted-Strong", "aa:bb:cc:00:00:0a", "[ESS]", -30),  // Strongest but blacklisted
            WifiNetwork("Unknown-Weak", "aa:bb:cc:00:00:0b", "[ESS]", -70)  // Weakest but not blacklisted
        )

        repository.setBlacklisted("aa:bb:cc:00:00:0a", "Blacklisted-Strong", true)

        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeSelectorConnector { ssid ->
                if (ssid == "Unknown-Weak") ConnectAttemptResult.Connected
                else ConnectAttemptResult.Failed("skip")
            },
            captivePortalChecker = FakePortalChecker(mutableListOf(CaptivePortalStatus.OPEN_INTERNET)),
            repository = repository
        )

        val result = coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        // Should skip blacklisted network entirely
        assertEquals("Unknown-Weak", result.currentSsid)
        assertTrue(result.hasValidatedInternet)
    }

    @Test
    fun `multiple whitelisted networks sorted by signal strength`() = runTest {
        val repository = InMemoryWifiRepo()
        val networks = listOf(
            WifiNetwork("Whitelisted-Weak", "aa:bb:cc:00:00:0c", "[ESS]", -75),
            WifiNetwork("Whitelisted-Strong", "aa:bb:cc:00:00:0d", "[ESS]", -45),
            WifiNetwork("Whitelisted-Medium", "aa:bb:cc:00:00:0e", "[ESS]", -60)
        )

        // Whitelist all networks
        for (net in networks) {
            repository.setWhitelisted(net.bssid, net.ssid, true)
        }

        val attemptOrder = mutableListOf<String>()
        val coordinator = AutoConnectCoordinator(
            scanner = FakeScanner(Result.success(networks)),
            connector = FakeSelectorConnector { ssid ->
                attemptOrder.add(ssid)
                ConnectAttemptResult.Failed("skip")
            },
            captivePortalChecker = FakePortalChecker(mutableListOf()),
            repository = repository
        )

        coordinator.connectNextOpenNetworkCycle(
            stopSignal = { false },
            previousAttempts = 0,
            onUpdate = {}
        )

        // Should attempt strongest first
        assertEquals("Whitelisted-Strong", attemptOrder.getOrNull(0))
        assertEquals("Whitelisted-Medium", attemptOrder.getOrNull(1))
        assertEquals("Whitelisted-Weak", attemptOrder.getOrNull(2))
    }

    // -------- Fake implementations --------

    private class FakeScanner(private val result: Result<List<WifiNetwork>>) : WifiScanner {
        override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = result
    }

    private class FakeSelectorConnector(
        private val decider: suspend (String) -> ConnectAttemptResult
    ) : WifiConnector {
        var disconnectCalls: Int = 0
        override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult = decider(ssid)
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
