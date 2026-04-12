package com.example.wifi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WifiNetworkDao {
    @Query("SELECT * FROM wifi_networks WHERE ssid = :ssid LIMIT 1")
    suspend fun getNetwork(ssid: String): WifiNetworkEntity?

    @Query("SELECT * FROM wifi_networks WHERE isBlacklisted = 1")
    suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity>

    @Query("SELECT * FROM wifi_networks WHERE isWhitelisted = 1")
    suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNetwork(network: WifiNetworkEntity)

    @Query("UPDATE wifi_networks SET isBlacklisted = :blacklisted WHERE ssid = :ssid")
    suspend fun setBlacklisted(ssid: String, blacklisted: Boolean)

    @Query("UPDATE wifi_networks SET isWhitelisted = :whitelisted WHERE ssid = :ssid")
    suspend fun setWhitelisted(ssid: String, whitelisted: Boolean)

    @Query("DELETE FROM wifi_networks WHERE ssid = :ssid")
    suspend fun deleteNetwork(ssid: String)
}

