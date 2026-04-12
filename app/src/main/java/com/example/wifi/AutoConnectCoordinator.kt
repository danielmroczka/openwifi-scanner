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
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = "Scan failed: ${it.message ?: "unknown error"}"
            )
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
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            captivePortalDetected = true,
                            message = "Captive portal detected on ${network.ssid}. Trying next network."
                        )
                    }
                }

                is ConnectAttemptResult.Failed -> {
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

