package com.dm.labs.wifi.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.annotation.RequiresPermission
import com.dm.labs.wifi.log.DevLog
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Suppress("DEPRECATION")
class AndroidWifiScanner(context: Context) : WifiScanner {
    private val appContext = context.applicationContext
    private val wifiManager: WifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> {
        if (!wifiManager.isWifiEnabled) {
            return Result.failure(IllegalStateException("Wi-Fi is turned off."))
        }

        val freshResultsAvailable = awaitFreshScanResults()
        if (!freshResultsAvailable) {
            ScanLogManager.log("Wi-Fi scan did not deliver fresh results in time. Using last known scan results.")
        }

        val networks = wifiManager.scanResults
            .asSequence()
            .filter { it.SSID.isNotBlank() }
            .filter { it.isOpenNetwork() }
            .map {
                WifiNetwork(
                    ssid = it.SSID,
                    bssid = it.BSSID,
                    capabilities = it.capabilities,
                    level = it.level
                )
            }
            .distinctBy { it.bssid }
            .sortedByDescending { it.level }
            .toList()

        return Result.success(networks)
    }

    private suspend fun awaitFreshScanResults(): Boolean {
        return withTimeoutOrNull(SCAN_RESULTS_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var receiverRegistered = false
                lateinit var receiver: BroadcastReceiver

                fun unregisterReceiverSafe() {
                    if (!receiverRegistered) return
                    receiverRegistered = false
                    runCatching { appContext.unregisterReceiver(receiver) }
                }

                receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        unregisterReceiverSafe()
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                }

                val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    appContext.registerReceiver(receiver, filter)
                }
                receiverRegistered = true

                val scanStarted = runCatching { wifiManager.startScan() }.getOrDefault(false)
                if (!scanStarted) {
                    unregisterReceiverSafe()
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                    return@suspendCancellableCoroutine
                }

                continuation.invokeOnCancellation {
                    unregisterReceiverSafe()
                }
            }
        } ?: false
    }

    private fun ScanResult.isOpenNetwork(): Boolean {
        val caps = capabilities.uppercase()
        return !caps.contains("WEP") &&
                !caps.contains("PSK") &&
                !caps.contains("SAE") &&
                !caps.contains("EAP") &&
                !caps.contains("OWE")
    }

    private companion object {
        private const val SCAN_RESULTS_TIMEOUT_MS = 10_000L
    }
}

@SuppressLint("NewApi")
class AndroidWifiConnector(context: Context) : WifiConnector {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    private var activeSuggestions: List<WifiNetworkSuggestion> = emptyList()

    @RequiresPermission(anyOf = [Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.NEARBY_WIFI_DEVICES])
    override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult {
        disconnectCurrentNetwork()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConnectAttemptResult.Unsupported(
                "Automatic connection is only implemented for Android 10+ in this app."
            )
        }

        // Try suggestion-based approach first (no user dialog for open networks)
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(false)
            .build()

        val suggestions = listOf(suggestion)

        // Remove any previously added suggestions
        if (activeSuggestions.isNotEmpty()) {
            wifiManager.removeNetworkSuggestions(activeSuggestions)
        }

        val status = wifiManager.addNetworkSuggestions(suggestions)
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            activeSuggestions = suggestions
            ScanLogManager.log("Suggestion added for $ssid, waiting for system to connect...")

            // Wait for the system to pick up the suggestion and connect
            val connected = waitForConnection(ssid)
            if (connected) {
                ScanLogManager.log("Successfully connected to $ssid via suggestion")
                return ConnectAttemptResult.Connected
            }

            // Suggestion was accepted but system didn't connect in time
            ScanLogManager.log("Suggestion for $ssid accepted but system did not connect. Trying specifier...")
        } else {
            ScanLogManager.log("Suggestion for $ssid failed (status=$status). Trying specifier...")
        }

        // Fallback: WifiNetworkSpecifier (shows system dialog)
        return connectViaSpecifier(ssid)
    }

    private suspend fun waitForConnection(expectedSsid: String): Boolean {
        // Give the system time to auto-connect via the suggestion
        // Extended timeout: try up to 40 seconds (20 iterations × 2 seconds)
        // Some devices take 36+ seconds to connect
        DevLog.i("   → Waiting for system to auto-connect (checking every 2 seconds, max 40 seconds)…")
        repeat(20) { iteration ->
            if (isConnectedToExpectedWifi(expectedSsid)) {
                DevLog.i("   ✓ Successfully connected via suggestion on attempt ${iteration + 1} (${(iteration + 1) * 2} seconds)")
                return true
            }
            if (iteration < 19) {
                DevLog.d("   ↳ Check #${iteration + 1}/20: not connected yet, waiting…")
                delay(2_000)
            }
        }

        if (isConnectedToExpectedWifi(expectedSsid)) {
            DevLog.i("   ✓ Connected on final check")
            return true
        }
        DevLog.w("   ✗ Suggestion connection timeout after 40 seconds")
        return false
    }

    private fun isConnectedToExpectedWifi(expectedSsid: String): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false
        }

        val currentSsid = currentWifiSsid() ?: return false
        if (currentSsid == expectedSsid) {
            connectivityManager.bindProcessToNetwork(activeNetwork)
            return true
        }

        return false
    }

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String? {
        val rawSsid = wifiManager.connectionInfo?.ssid ?: return null
        return normalizeSsid(rawSsid)
    }

    private fun normalizeSsid(rawSsid: String): String? {
        val normalized = rawSsid.removePrefix("\"").removeSuffix("\"")
        return normalized.takeIf { it.isNotBlank() && !it.equals("<unknown ssid>", ignoreCase = true) }
    }


    private suspend fun connectViaSpecifier(ssid: String): ConnectAttemptResult {
        DevLog.i("   → Fallback: Using WifiNetworkSpecifier (may show system dialog)…")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        DevLog.i("   → Requesting network connection with specifier (20 second timeout)…")
        val outcome = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<ConnectAttemptResult> { continuation ->
                lateinit var callback: ConnectivityManager.NetworkCallback

                fun failAndCleanup(result: ConnectAttemptResult) {
                    if (!continuation.isCompleted) {
                        continuation.resume(result)
                    }
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    if (activeCallback == callback) {
                        activeCallback = null
                    }
                }

                callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        DevLog.i("   ✓ Network AVAILABLE callback received")
                        activeCallback = callback
                        connectivityManager.bindProcessToNetwork(network)
                        if (!continuation.isCompleted) {
                            continuation.resume(ConnectAttemptResult.Connected)
                        }
                    }

                    override fun onUnavailable() {
                        DevLog.w("   ✗ Network UNAVAILABLE callback")
                        failAndCleanup(ConnectAttemptResult.Failed("Network unavailable."))
                    }

                    override fun onLost(network: Network) {
                        DevLog.w("   ✗ Network LOST callback")
                        connectivityManager.bindProcessToNetwork(null)
                        failAndCleanup(ConnectAttemptResult.Failed("Connection was lost."))
                    }
                }

                connectivityManager.requestNetwork(request, callback)

                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                    if (activeCallback == callback) {
                        activeCallback = null
                    }
                }
            }
        }

        return when (outcome) {
            null -> {
                DevLog.w("   ✗ Specifier connection timed out after 20 seconds")
                ConnectAttemptResult.Failed("Timed out while trying to connect.")
            }
            else -> {
                DevLog.i("   ✓ Specifier connection completed: $outcome")
                outcome
            }
        }
    }

    override fun disconnectCurrentNetwork() {
        connectivityManager.bindProcessToNetwork(null)
        activeCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        activeCallback = null
        if (activeSuggestions.isNotEmpty()) {
            runCatching { wifiManager.removeNetworkSuggestions(activeSuggestions) }
            activeSuggestions = emptyList()
        }
    }
}

class AndroidCaptivePortalChecker(context: Context) : CaptivePortalChecker {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val log = com.dm.labs.wifi.log.DevLog  // Direct access for detailed logging

    override fun getStatus(): CaptivePortalStatus {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            log.d("⚠ No active network")
            return CaptivePortalStatus.UNKNOWN
        }

        val caps = connectivityManager.getNetworkCapabilities(network)
        if (caps == null) {
            log.d("⚠ No network capabilities available")
            return CaptivePortalStatus.UNKNOWN
        }

        // Check for captive portal first (priority)
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
            log.d("🔐 Portal capability detected: NET_CAPABILITY_CAPTIVE_PORTAL")
            return CaptivePortalStatus.CAPTIVE_PORTAL
        }

        // Check for validated internet
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            log.d("✓ Internet capability detected: NET_CAPABILITY_VALIDATED")
            return CaptivePortalStatus.OPEN_INTERNET
        }

        // Check for internet capability without validation (intermediate state before full validation)
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            log.d("↳ Connected but not validated: NET_CAPABILITY_INTERNET (validating…)")
            return CaptivePortalStatus.UNKNOWN
        }

        log.d("✗ No relevant capabilities: VALIDATED=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}, CAPTIVE=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}, INTERNET=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, NOT_RESTRICTED=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)}")
        return CaptivePortalStatus.UNKNOWN
    }
}

/**
 * Quick connectivity test using HTTP to verify internet without relying on Android's status
 * Useful when ConnectivityManager reports UNKNOWN but we need to know if internet actually works
 */
class QuickConnectivityTester {
    companion object {
        private const val CONNECTIVITY_CHECK = "http://connectivitycheck.gstatic.com/generate_204"
        private const val TIMEOUT_MS = 3_000

        suspend fun testInternetAvailable(): Boolean {
            return try {
                val url = java.net.URL(CONNECTIVITY_CHECK)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false

                try {
                    val code = conn.responseCode
                    code == 204 || code == 200  // 204 = no content (success), 200 = OK
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                com.dm.labs.wifi.log.DevLog.d("HTTP connectivity test failed: ${e.message}")
                false
            }
        }
    }
}
