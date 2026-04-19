package com.dm.labs.wifi.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWifiNetworkRepositoryTest {

    @Test
    fun `recordNetwork inserts a new network with location`() = runTest {
        val dao = FakeWifiNetworkDao()
        val repository = RoomWifiNetworkRepository(dao)

        repository.recordNetwork(
            bssid = "aa:bb:cc:dd:ee:01",
            ssid = "Cafe WiFi",
            latitude = 12.34,
            longitude = 56.78
        )

        val saved = dao.getNetwork("aa:bb:cc:dd:ee:01")
        assertNotNull(saved)
        assertEquals("Cafe WiFi", saved?.ssid)
        assertEquals(12.34, saved!!.latitude!!, 0.0)
        assertEquals(56.78, saved.longitude!!, 0.0)
    }

    @Test
    fun `recordNetwork updates last connected and location for existing network`() = runTest {
        val dao = FakeWifiNetworkDao(
            initial = mutableMapOf(
                "aa:bb:cc:dd:ee:02" to WifiNetworkEntity(
                    bssid = "aa:bb:cc:dd:ee:02",
                    ssid = "Library",
                    latitude = 1.0,
                    longitude = 2.0,
                    lastConnected = 10L
                )
            )
        )
        val repository = RoomWifiNetworkRepository(dao)

        repository.recordNetwork(
            bssid = "aa:bb:cc:dd:ee:02",
            ssid = "Library",
            latitude = 3.0,
            longitude = 4.0
        )

        val updated = dao.getNetwork("aa:bb:cc:dd:ee:02")!!
        assertTrue(updated.lastConnected > 10L)
        assertEquals(3.0, updated.latitude!!, 0.0)
        assertEquals(4.0, updated.longitude!!, 0.0)
    }

    @Test
    fun `recordNetwork keeps existing location when new location is absent`() = runTest {
        val dao = FakeWifiNetworkDao(
            initial = mutableMapOf(
                "aa:bb:cc:dd:ee:03" to WifiNetworkEntity(
                    bssid = "aa:bb:cc:dd:ee:03",
                    ssid = "Mall",
                    latitude = 5.0,
                    longitude = 6.0,
                    lastConnected = 20L
                )
            )
        )
        val repository = RoomWifiNetworkRepository(dao)

        repository.recordNetwork(
            bssid = "aa:bb:cc:dd:ee:03",
            ssid = "Mall",
            latitude = null,
            longitude = null
        )

        val updated = dao.getNetwork("aa:bb:cc:dd:ee:03")!!
        assertEquals(5.0, updated.latitude!!, 0.0)
        assertEquals(6.0, updated.longitude!!, 0.0)
        assertTrue(updated.lastConnected > 20L)
    }

    @Test
    fun `setBlacklisted inserts network and clears whitelist when enabling`() = runTest {
        val dao = FakeWifiNetworkDao(
            initial = mutableMapOf(
                "aa:bb:cc:dd:ee:04" to WifiNetworkEntity(
                    bssid = "aa:bb:cc:dd:ee:04",
                    ssid = "Airport",
                    isWhitelisted = true
                )
            )
        )
        val repository = RoomWifiNetworkRepository(dao)

        repository.setBlacklisted(
            bssid = "aa:bb:cc:dd:ee:04",
            ssid = "Airport",
            blacklisted = true,
            latitude = 1.2,
            longitude = 3.4
        )

        val saved = dao.getNetwork("aa:bb:cc:dd:ee:04")!!
        assertTrue(saved.isBlacklisted)
        assertFalse(saved.isWhitelisted)
    }

    @Test
    fun `setWhitelisted inserts network and clears blacklist when enabling`() = runTest {
        val dao = FakeWifiNetworkDao(
            initial = mutableMapOf(
                "aa:bb:cc:dd:ee:05" to WifiNetworkEntity(
                    bssid = "aa:bb:cc:dd:ee:05",
                    ssid = "Station",
                    isBlacklisted = true
                )
            )
        )
        val repository = RoomWifiNetworkRepository(dao)

        repository.setWhitelisted(
            bssid = "aa:bb:cc:dd:ee:05",
            ssid = "Station",
            whitelisted = true,
            latitude = null,
            longitude = null
        )

        val saved = dao.getNetwork("aa:bb:cc:dd:ee:05")!!
        assertTrue(saved.isWhitelisted)
        assertFalse(saved.isBlacklisted)
    }

    @Test
    fun `blacklist and whitelist queries are false for unknown networks`() = runTest {
        val repository = RoomWifiNetworkRepository(FakeWifiNetworkDao())

        assertFalse(repository.isBlacklisted("missing"))
        assertFalse(repository.isWhitelisted("missing"))
    }

    @Test
    fun `deleteNetwork and updateLocation delegate to dao state`() = runTest {
        val dao = FakeWifiNetworkDao(
            initial = mutableMapOf(
                "aa:bb:cc:dd:ee:06" to WifiNetworkEntity(
                    bssid = "aa:bb:cc:dd:ee:06",
                    ssid = "Hotel"
                )
            )
        )
        val repository = RoomWifiNetworkRepository(dao)

        repository.updateLocation("aa:bb:cc:dd:ee:06", 9.0, 10.0)
        assertEquals(9.0, dao.getNetwork("aa:bb:cc:dd:ee:06")!!.latitude!!, 0.0)
        assertEquals(10.0, dao.getNetwork("aa:bb:cc:dd:ee:06")!!.longitude!!, 0.0)

        repository.deleteNetwork("aa:bb:cc:dd:ee:06")
        assertEquals(null, dao.getNetwork("aa:bb:cc:dd:ee:06"))
    }

    private class FakeWifiNetworkDao(
        initial: MutableMap<String, WifiNetworkEntity> = mutableMapOf()
    ) : WifiNetworkDao {
        private val store = initial

        override suspend fun getNetwork(bssid: String): WifiNetworkEntity? = store[bssid]

        override suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity> =
            store.values.filter { it.isBlacklisted }

        override suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity> =
            store.values.filter { it.isWhitelisted }

        override suspend fun getAllNetworks(): List<WifiNetworkEntity> = store.values.toList()

        override suspend fun insertNetwork(network: WifiNetworkEntity) {
            store[network.bssid] = network
        }

        override suspend fun setBlacklisted(bssid: String, blacklisted: Boolean) {
            val current = store[bssid] ?: return
            store[bssid] = current.copy(isBlacklisted = blacklisted)
        }

        override suspend fun setWhitelisted(bssid: String, whitelisted: Boolean) {
            val current = store[bssid] ?: return
            store[bssid] = current.copy(isWhitelisted = whitelisted)
        }

        override suspend fun updateLastConnected(bssid: String, timestamp: Long) {
            val current = store[bssid] ?: return
            store[bssid] = current.copy(lastConnected = timestamp)
        }

        override suspend fun updateLocation(bssid: String, lat: Double, lon: Double) {
            val current = store[bssid] ?: return
            store[bssid] = current.copy(latitude = lat, longitude = lon)
        }

        override suspend fun deleteNetwork(bssid: String) {
            store.remove(bssid)
        }
    }
}

