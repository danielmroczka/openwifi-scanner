package com.example.wifi

import kotlinx.coroutines.delay

class AutoConnectCoordinator(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker
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
        val networks = scan.getOrElse {
            val errorMsg = "Scan failed: ${it.message ?: "unknown error"}"
            ScanLogManager.log(errorMsg)
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = errorMsg
            )
        }

        ScanLogManager.log("Background scan found ${networks.size} open networks.")

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
