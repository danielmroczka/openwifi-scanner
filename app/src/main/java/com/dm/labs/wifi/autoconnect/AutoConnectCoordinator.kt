package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.delay

class AutoConnectCoordinator(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val repository: WifiNetworkRepository? = null
) {
    /**
     * @param onUnknownNetwork called when a network is found that is NOT on the whitelist
     *        or blacklist. The coordinator suspends until the user decides. If null, all
     *        non-blacklisted networks are connected automatically.
     */
    suspend fun connectNextOpenNetworkCycle(
        stopSignal: () -> Boolean,
        previousAttempts: Int,
        onUpdate: (BackgroundAutoConnectState) -> Unit,
        onUnknownNetwork: (suspend (WifiNetwork) -> UserNetworkDecision)? = null
    ): BackgroundAutoConnectState {
        if (stopSignal()) {
            return BackgroundAutoConnectState(
                isRunning = false,
                attempts = previousAttempts,
                message = "Stopped"
            )
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
        val whitelistedBssids: Set<String>
        val networks = if (repository != null) {
            val blacklisted = repository.getBlacklistedNetworks().map { it.bssid }.toSet()
            whitelistedBssids = repository.getWhitelistedNetworks().map { it.bssid }.toSet()
            val filtered = allNetworks.filter { it.bssid !in blacklisted }
            if (filtered.size < allNetworks.size) {
                ScanLogManager.log("Filtered out ${allNetworks.size - filtered.size} blacklisted network(s).")
            }
            filtered.sortedByDescending { it.bssid in whitelistedBssids }
        } else {
            whitelistedBssids = emptySet()
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
                return BackgroundAutoConnectState(
                    isRunning = false,
                    attempts = attempts,
                    message = "Stopped"
                )
            }

            // --- Unknown network? Ask the user first ---------------------------------
            val isWhitelisted = network.bssid in whitelistedBssids
            if (!isWhitelisted && onUnknownNetwork != null && repository != null) {
                ScanLogManager.log("Unknown network ${network.ssid} (${network.bssid}), requesting approval…")
                onUpdate(
                    BackgroundAutoConnectState(
                        isRunning = true,
                        currentSsid = network.ssid,
                        attempts = attempts,
                        message = "Waiting for approval: ${network.ssid}…"
                    )
                )
                when (onUnknownNetwork(network)) {
                    UserNetworkDecision.BLACKLIST -> {
                        ScanLogManager.log("User blacklisted ${network.ssid}")
                        continue
                    }

                    UserNetworkDecision.SKIP -> {
                        ScanLogManager.log("User skipped ${network.ssid}")
                        continue
                    }

                    UserNetworkDecision.WHITELIST -> {
                        ScanLogManager.log("User whitelisted ${network.ssid}")
                        // fall through to connect
                    }
                }
            }

            // --- Connect -------------------------------------------------------------
            attempts += 1
            ScanLogManager.log("Trying ${network.ssid}…")

            onUpdate(
                BackgroundAutoConnectState(
                    isRunning = true,
                    currentSsid = network.ssid,
                    attempts = attempts,
                    message = "Trying ${network.ssid}…"
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

                    val portalDetected =
                        captivePortalChecker.getStatus() == CaptivePortalStatus.CAPTIVE_PORTAL
                    if (portalDetected) {
                        // Stay connected — let the service try to solve the portal
                        ScanLogManager.log("Captive portal detected on ${network.ssid}.")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            captivePortalDetected = true,
                            message = "Captive portal detected on ${network.ssid}."
                        )
                    } else {
                        connector.disconnectCurrentNetwork()
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
            message = "Open networks exhausted. Restarting scan…"
        )
    }

    private suspend fun waitForValidatedInternet(stopSignal: () -> Boolean): Boolean {
        repeat(3) {
            if (stopSignal()) return false
            when (captivePortalChecker.getStatus()) {
                CaptivePortalStatus.OPEN_INTERNET -> return true
                CaptivePortalStatus.CAPTIVE_PORTAL -> return false
                CaptivePortalStatus.UNKNOWN -> delay(2_000)
            }
        }
        return false
    }
}

