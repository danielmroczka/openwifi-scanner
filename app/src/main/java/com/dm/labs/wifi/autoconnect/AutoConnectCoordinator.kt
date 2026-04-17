package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.log.DevLog
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
            DevLog.e("Scan failed", it)
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = errorMsg
            )
        }

        if (allNetworks.isEmpty()) {
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = "No open networks found. Retrying..."
            )
        }

        // Filter out blacklisted, cooldown, and prioritise whitelisted
        val whitelistedBssids: Set<String>
        val filteredOutDetails = mutableListOf<String>()
        val networks = if (repository != null) {
            val blacklisted = repository.getBlacklistedNetworks().map { it.bssid }.toSet()
            whitelistedBssids = repository.getWhitelistedNetworks().map { it.bssid }.toSet()
            val filtered = allNetworks
                .filter { net ->
                    if (net.bssid in blacklisted) {
                        filteredOutDetails += "${describeNetwork(net)} -> blacklisted"
                        false
                    } else if (NetworkCooldownManager.isOnCooldown(net.ssid)) {
                        filteredOutDetails += "${describeNetwork(net)} -> cooldown"
                        false
                    } else {
                        true
                    }
                }
            filtered.sortedByDescending { it.bssid in whitelistedBssids }
        } else {
            whitelistedBssids = emptySet()
            allNetworks.filter {
                val onCooldown = NetworkCooldownManager.isOnCooldown(it.ssid)
                if (onCooldown) {
                    filteredOutDetails += "${describeNetwork(it)} -> cooldown"
                }
                !onCooldown
            }
        }

        DevLog.i(
            "Open network scan: total=${allNetworks.size}, eligible=${networks.size}, " +
                "whitelistedEligible=${networks.count { it.bssid in whitelistedBssids }}"
        )
        DevLog.i("Eligible candidates (priority order): ${networks.joinToString { describeNetwork(it) }}")
        if (filteredOutDetails.isNotEmpty()) {
            DevLog.i("Filtered out candidates: ${filteredOutDetails.joinToString("; ")}")
        }

        if (networks.isEmpty()) {
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = "No open networks found. Retrying..."
            )
        }

        // Log only when we find open networks (user-facing log)
        if (allNetworks.isNotEmpty()) {
            ScanLogManager.log("Background scan found ${allNetworks.size} open networks")
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

            // --- Unknown network? Ask the user first ---
            val isWhitelisted = network.bssid in whitelistedBssids
            if (!isWhitelisted && onUnknownNetwork != null && repository != null) {
                DevLog.i("Unknown network requires approval: ${describeNetwork(network)}")
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
                        repository.setBlacklisted(network.bssid, network.ssid, true)
                        DevLog.i("User decision for ${describeNetwork(network)}: BLACKLIST")
                        ScanLogManager.log("User blacklisted ${network.ssid}")
                        continue
                    }
                    UserNetworkDecision.SKIP -> {
                        DevLog.i("User decision for ${describeNetwork(network)}: SKIP")
                        continue
                    }
                    UserNetworkDecision.WHITELIST -> {
                        repository.setWhitelisted(network.bssid, network.ssid, true)
                        DevLog.i("User decision for ${describeNetwork(network)}: WHITELIST")
                    }
                }
            }

            // --- Connect ---
            attempts += 1
            DevLog.i("Attempting connection to ${describeNetwork(network)}")

            onUpdate(
                BackgroundAutoConnectState(
                    isRunning = true,
                    currentSsid = network.ssid,
                    attempts = attempts,
                    message = "Trying ${network.ssid}…"
                )
            )
            // user-facing log for attempts
            ScanLogManager.log("Trying ${network.ssid}")

            when (val connect = connector.connectToOpenNetwork(network.ssid)) {
                ConnectAttemptResult.Connected -> {
                    DevLog.i("Wi-Fi associated with ${describeNetwork(network)}; validating internet capability")
                    val validated = waitForValidatedInternet(stopSignal)
                    if (validated) {
                        ScanLogManager.log("Connected to ${network.ssid} with validated internet")
                        DevLog.i("Internet validated on ${describeNetwork(network)}")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            hasValidatedInternet = true,
                            message = "Connected to ${network.ssid} with internet."
                        )
                    }

                    val portalStatus = captivePortalChecker.getStatus()
                    DevLog.i("Post-connect status for ${describeNetwork(network)}: $portalStatus")

                    if (portalStatus == CaptivePortalStatus.CAPTIVE_PORTAL) {
                        ScanLogManager.log("Captive portal detected on ${network.ssid}.")
                        DevLog.i("Captive portal detected on ${describeNetwork(network)}; starting recovery flow")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            attempts = attempts,
                            captivePortalDetected = true,
                            message = "Captive portal detected on ${network.ssid}."
                        )
                    } else {
                        // No internet and no captive portal → put on 1h cooldown
                        connector.disconnectCurrentNetwork()
                        NetworkCooldownManager.putOnCooldown(network.ssid)
                        ScanLogManager.log("No internet on ${network.ssid}, disconnected")
                        DevLog.w(
                            "No internet for ${describeNetwork(network)} (status=$portalStatus); " +
                                "disconnect + 1h cooldown"
                        )
                    }
                }

                is ConnectAttemptResult.Failed -> {
                    ScanLogManager.log("Failed to connect to ${network.ssid}: ${connect.reason}")
                    DevLog.w("Connection failed for ${describeNetwork(network)}: ${connect.reason}")
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
                    ScanLogManager.log("Unsupported: ${connect.reason}")
                    DevLog.e("Unsupported connection for ${describeNetwork(network)}: ${connect.reason}")
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

    private fun describeNetwork(network: WifiNetwork): String {
        return "${network.ssid} [${network.bssid}] rssi=${network.level}dBm caps=${network.capabilities}"
    }
}
