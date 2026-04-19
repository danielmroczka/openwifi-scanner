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

        DevLog.i("🔍 Scanning for open WiFi networks…")
        val scan = scanner.scanOpenNetworks()
        val allNetworks = scan.getOrElse {
            val errorMsg = "Scan failed: ${it.message ?: "unknown error"}"
            ScanLogManager.log(errorMsg)
            DevLog.e("❌ Scan failed", it)
            return BackgroundAutoConnectState(
                isRunning = true,
                attempts = previousAttempts,
                message = errorMsg
            )
        }

        DevLog.i("   → Scan complete: found ${allNetworks.size} open network(s)")

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
            // Multi-level sort:
            // 1. Priority: whitelisted first (false = whitelisted), then neutral
            // 2. Signal strength: higher dBm = better (so negate for descending sort)
            filtered.sortedWith(
                compareBy(
                    { it.bssid !in whitelistedBssids },  // false (whitelisted) before true (neutral)
                    { -it.level }                         // stronger signal first (higher = better)
                )
            )
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
                        currentBssid = network.bssid,
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
            DevLog.i("📌 Attempt #$attempts: Trying to connect to ${describeNetwork(network)}…")

            onUpdate(
                BackgroundAutoConnectState(
                    isRunning = true,
                    currentSsid = network.ssid,
                    currentBssid = network.bssid,
                    attempts = attempts,
                    message = "Trying ${network.ssid}…"
                )
            )
            // user-facing log for attempts
            ScanLogManager.log("Trying ${network.ssid}")

            when (val connect = connector.connectToOpenNetwork(network.ssid)) {
                ConnectAttemptResult.Connected -> {
                    DevLog.i("📡 Wi-Fi ASSOCIATED with ${describeNetwork(network)}")
                    DevLog.i("   → Now validating internet capability (checking if network has connectivity)…")
                    ScanLogManager.log("Connected to ${network.ssid}, validating internet…")
                    val validated = waitForValidatedInternet(stopSignal)
                    if (validated) {
                        ScanLogManager.log("Connected to ${network.ssid} with validated internet")
                        DevLog.i("   ✓ Internet VALIDATED - connection successful!")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            currentBssid = network.bssid,
                            attempts = attempts,
                            hasValidatedInternet = true,
                            message = "Connected to ${network.ssid} with internet."
                        )
                    }

                    DevLog.i("   ⚠ Internet validation FAILED - checking for captive portal…")
                    val portalStatus = captivePortalChecker.getStatus()
                    DevLog.i("   → Portal status: $portalStatus")

                    if (portalStatus == CaptivePortalStatus.CAPTIVE_PORTAL) {
                        ScanLogManager.log("Captive portal detected on ${network.ssid}.")
                        DevLog.i("   🔐 CAPTIVE PORTAL DETECTED - starting recovery flow")
                        return BackgroundAutoConnectState(
                            isRunning = true,
                            currentSsid = network.ssid,
                            currentBssid = network.bssid,
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
                            currentBssid = network.bssid,
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
        // Wait up to 6 seconds (3 checks × 2 seconds) for internet validation
        // This allows time for the OS to perform its connectivity checks
        DevLog.i("↳ Starting internet validation check (waiting up to 6 seconds)…")

        repeat(3) { iteration ->
            if (stopSignal()) {
                DevLog.w("↳ Validation stopped by signal")
                return false
            }

            val status = captivePortalChecker.getStatus()
            DevLog.d("↳ Validation check #${iteration + 1}/3: status=$status")

            when (status) {
                CaptivePortalStatus.OPEN_INTERNET -> {
                    DevLog.i("↳ ✓ Internet VALIDATED successfully on attempt ${iteration + 1}")
                    return true
                }
                CaptivePortalStatus.CAPTIVE_PORTAL -> {
                    DevLog.w("↳ ✗ CAPTIVE PORTAL detected during validation on attempt ${iteration + 1}")
                    return false
                }
                CaptivePortalStatus.UNKNOWN -> {
                    if (iteration < 2) {
                        DevLog.d("↳ Status is UNKNOWN on attempt ${iteration + 1} - waiting 2 seconds for OS validation…")
                        delay(2_000)
                    } else {
                        DevLog.w("↳ Final check (attempt 3/3): status still UNKNOWN - connection not validated")
                    }
                }
            }
        }

        DevLog.w("↳ ✗ Internet validation FAILED - status remained UNKNOWN after 3 checks")
        return false
    }

    private fun describeNetwork(network: WifiNetwork): String {
        return "${network.ssid} [${network.bssid}] rssi=${network.level}dBm caps=${network.capabilities}"
    }
}
