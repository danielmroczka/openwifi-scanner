package com.dm.labs.wifi.data

interface WifiNetworkRepository {
    suspend fun getNetwork(bssid: String): WifiNetworkEntity?
    suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity>
    suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity>
    suspend fun getAllNetworks(): List<WifiNetworkEntity>
    suspend fun recordNetwork(
        bssid: String,
        ssid: String,
        latitude: Double? = null,
        longitude: Double? = null
    )

    suspend fun setBlacklisted(
        bssid: String,
        ssid: String,
        blacklisted: Boolean,
        latitude: Double? = null,
        longitude: Double? = null
    )

    suspend fun setWhitelisted(
        bssid: String,
        ssid: String,
        whitelisted: Boolean,
        latitude: Double? = null,
        longitude: Double? = null
    )

    suspend fun deleteNetwork(bssid: String)
    suspend fun isBlacklisted(bssid: String): Boolean
    suspend fun isWhitelisted(bssid: String): Boolean
    suspend fun updateLocation(bssid: String, latitude: Double, longitude: Double)
}

class RoomWifiNetworkRepository(private val dao: WifiNetworkDao) : WifiNetworkRepository {

    override suspend fun getNetwork(bssid: String): WifiNetworkEntity? = dao.getNetwork(bssid)

    override suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity> =
        dao.getBlacklistedNetworks()

    override suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity> =
        dao.getWhitelistedNetworks()

    override suspend fun getAllNetworks(): List<WifiNetworkEntity> =
        dao.getAllNetworks()

    override suspend fun recordNetwork(
        bssid: String,
        ssid: String,
        latitude: Double?,
        longitude: Double?
    ) {
        val existing = dao.getNetwork(bssid)
        if (existing != null) {
            dao.updateLastConnected(bssid, System.currentTimeMillis())
            if (latitude != null && longitude != null) {
                dao.updateLocation(bssid, latitude, longitude)
            }
        } else {
            dao.insertNetwork(
                WifiNetworkEntity(
                    bssid = bssid,
                    ssid = ssid,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
    }

    override suspend fun setBlacklisted(
        bssid: String,
        ssid: String,
        blacklisted: Boolean,
        latitude: Double?,
        longitude: Double?
    ) {
        ensureExists(bssid, ssid, latitude, longitude)
        dao.setBlacklisted(bssid, blacklisted)
        if (blacklisted) {
            dao.setWhitelisted(bssid, false)
        }
    }

    override suspend fun setWhitelisted(
        bssid: String,
        ssid: String,
        whitelisted: Boolean,
        latitude: Double?,
        longitude: Double?
    ) {
        ensureExists(bssid, ssid, latitude, longitude)
        dao.setWhitelisted(bssid, whitelisted)
        if (whitelisted) {
            dao.setBlacklisted(bssid, false)
        }
    }

    override suspend fun deleteNetwork(bssid: String) = dao.deleteNetwork(bssid)

    override suspend fun isBlacklisted(bssid: String): Boolean =
        dao.getNetwork(bssid)?.isBlacklisted == true

    override suspend fun isWhitelisted(bssid: String): Boolean =
        dao.getNetwork(bssid)?.isWhitelisted == true

    override suspend fun updateLocation(bssid: String, latitude: Double, longitude: Double) {
        dao.updateLocation(bssid, latitude, longitude)
    }

    private suspend fun ensureExists(
        bssid: String,
        ssid: String,
        latitude: Double?,
        longitude: Double?
    ) {
        if (dao.getNetwork(bssid) == null) {
            dao.insertNetwork(
                WifiNetworkEntity(
                    bssid = bssid,
                    ssid = ssid,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
    }
}

