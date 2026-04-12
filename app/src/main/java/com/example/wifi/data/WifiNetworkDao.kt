package com.example.wifi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WifiNetworkDao {
    @Query("SELECT * FROM wifi_networks WHERE bssid = :bssid LIMIT 1")
    suspend fun getNetwork(bssid: String): WifiNetworkEntity?

    @Query("SELECT * FROM wifi_networks WHERE isBlacklisted = 1 ORDER BY dateAdded DESC")
    suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity>

    @Query("SELECT * FROM wifi_networks WHERE isWhitelisted = 1 ORDER BY dateAdded DESC")
    suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity>

    @Query("SELECT * FROM wifi_networks ORDER BY dateAdded DESC")
    suspend fun getAllNetworks(): List<WifiNetworkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetwork(network: WifiNetworkEntity)

    @Query("UPDATE wifi_networks SET isBlacklisted = :blacklisted WHERE bssid = :bssid")
    suspend fun setBlacklisted(bssid: String, blacklisted: Boolean)

    @Query("UPDATE wifi_networks SET isWhitelisted = :whitelisted WHERE bssid = :bssid")
    suspend fun setWhitelisted(bssid: String, whitelisted: Boolean)

    @Query("UPDATE wifi_networks SET lastConnected = :timestamp WHERE bssid = :bssid")
    suspend fun updateLastConnected(bssid: String, timestamp: Long)

    @Query("UPDATE wifi_networks SET latitude = :lat, longitude = :lon WHERE bssid = :bssid")
    suspend fun updateLocation(bssid: String, lat: Double, lon: Double)

    @Query("DELETE FROM wifi_networks WHERE bssid = :bssid")
    suspend fun deleteNetwork(bssid: String)
}

