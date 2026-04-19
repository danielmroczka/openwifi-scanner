package com.dm.labs.wifi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "captive_portal_solutions")
data class CaptivePortalSolutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ssid: String,
    val portalUrl: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val stepCount: Int = 0,
    val bssid: String? = null,           // AP identifier (BSSID) where solution was recorded
    val portalHost: String? = null,     // Extracted host from portalUrl for precise matching
    val isShared: Boolean = false       // For future peer-to-peer sharing
)

