package com.example.wifi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wifi_networks")
data class WifiNetworkEntity(
    @PrimaryKey val ssid: String,
    val isBlacklisted: Boolean = false,
    val isWhitelisted: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastConnected: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null
)

