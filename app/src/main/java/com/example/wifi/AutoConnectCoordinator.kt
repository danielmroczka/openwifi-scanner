package com.example.wifi

import com.example.wifi.data.WifiNetworkRepository
import kotlinx.coroutines.delay

class AutoConnectCoordinator(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val repository: WifiNetworkRepository? = null
) {
    suspend fun connectNextOpenNetworkCycle(
        stopSignal: () -> Boolean,
        previousAttempts: Int,
        onUpdate: (BackgroundAutoConnectState) -> Unit
    ): BackgroundAutoConnectState {
        if (stopSignal()) {
            return BackgroundAutoConnectState(isRunning = false, attempts = previousAttempts, message = "Stopped")
        }

        onUpdate(
            BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = "Scanning open networks..."
            )
        )

        val scan = scanner.scanOpenNetworks()
        val allNetworks = scan.getOrElse {
            val errorMsg = "Scan failed: ${it.message ?: "unknown error"}"
            ScanLogManager.log(errorMsg)
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = errorMsg
            )
        }

        ScanLogManager.log("Background scan found ${allNetworks.size} open networks.")

        // Filter out blacklisted and prioritise whitelisted networks
        val networks = if (repository != null) {
            val blacklisted = repository.getBlacklistedNetworks().map { it.bssid }.toSet()
            val whitelisted = repository.getWhitelistedNetworks().map { it.bssid }.toSet()
            val filtered = allNetworks.filter { it.bssid !in blacklisted }
            if (filtered.size < allNetworks.size) {
                ScanLogManager.log("Filtered out ${allNetworks.size - filtered.size} blacklisted network(s).")
            }
            filtered.sortedByDescending { it.bssid in whitelisted }
        } else {
            allNetworks
        }

        if (networks.isEmpty()) {
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = "No open networks found. Retrying..."
            )
        }

        var attempts = previousAttempts
        for (network in networks) {
            if (stopSignal()) {
                return BackgroundAutoConnectState(isRunning = false, attempts = attempts, message = "Stopped")
            }

            attempts += 1
            val logMessage = "Trying ${network.ssid}..."
            ScanLogManager.log(logMessage)

            onUpdate(
                BackgroundAutoConnectState(
                    isRunning = true,
                    currentSsid = network.ssid,
                    attempts = attempts,
                    message = "Trying ${network.ssid}..."
                )
            )

            when (val connect = connector.connectToOpenNetwork(network.ssid)) {
                ConnectAttemptResult.Connected -> {
                    val validated = waitForValidatedInternet(stopSignal)
                    if (validated) {
                        ScanLogManager.log("Connected to ${network.ssid} with validated internet.")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            hasValidatedInternet = true,
                            message = "Connected to ${network.ssid} with internet."
                        )
                    }

                    val portalDetected = captivePortalChecker.getStatus() == CaptivePortalStatus.CAPTIVE_PORTAL
                    connector.disconnectCurrentNetwork()
                    if (portalDetected) {
                        ScanLogManager.log("Captive portal detected on ${network.ssid}.")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            captivePortalDetected = true,
                            message = "Captive portal detected on ${network.ssid}. Trying next network."
                        )
                    } else {
                        ScanLogManager.log("No internet on ${network.ssid}, disconnected.")
                    }
                }

                is ConnectAttemptResult.Failed -> {
                    ScanLogManager.log("Failed to connect to ${network.ssid}: ${connect.reason}")
                    onUpdate(
                        BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            message = "${network.ssid} failed: ${connect.reason}"
                        )
                    )
                }

                is ConnectAttemptResult.Unsupported -> {
                    ScanLogManager.log("Unsupported connection to ${network.ssid}: ${connect.reason}")
                    return BackgroundAutoConnectState(
                        isRunning = false,
                        attempts = attempts,
                        message = connect.reason
                    )
                }
            }
        }

        return BackgroundAutoConnectState(
            isRunning = true,
            attempts = attempts,
            message = "Open networks exhausted. Restarting scan..."
        )
    }

    private suspend fun waitForValidatedInternet(stopSignal: () -> Boolean): Boolean {
        repeat(3) {
            if (stopSignal()) {
                return false
            }
            when (captivePortalChecker.getStatus()) {
                CaptivePortalStatus.OPEN_INTERNET -> return true
                CaptivePortalStatus.CAPTIVE_PORTAL -> return false
                CaptivePortalStatus.UNKNOWN -> delay(2_000)
            }
        }
        return false
    }
}
