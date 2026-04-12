package com.example.wifi

import kotlinx.coroutines.flow.StateFlow

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val level: Int
)

sealed interface ConnectAttemptResult {
    data object Connected : ConnectAttemptResult
    data class Failed(val reason: String) : ConnectAttemptResult
    data class Unsupported(val reason: String) : ConnectAttemptResult
}

enum class CaptivePortalStatus {
    OPEN_INTERNET,
    CAPTIVE_PORTAL,
    UNKNOWN
}

interface WifiScanner {
    suspend fun scanOpenNetworks(): Result<List<WifiNetwork>>
}

interface WifiConnector {
    suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult
    fun disconnectCurrentNetwork()
}

interface CaptivePortalChecker {
    fun getStatus(): CaptivePortalStatus
}

interface CaptivePortalResolver {
    fun resolve()
}

data class BackgroundAutoConnectState(
    val isRunning: Boolean = false,
    val currentSsid: String? = null,
    val attempts: Int = 0,
    val captivePortalDetected: Boolean = false,
    val hasValidatedInternet: Boolean = false,
    val message: String = "Idle"
)

interface AutoConnectStateSource {
    val state: StateFlow<BackgroundAutoConnectState>
}

