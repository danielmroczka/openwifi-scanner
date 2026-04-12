package com.example.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresPermission
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@Suppress("DEPRECATION")
class AndroidWifiScanner(context: Context) : WifiScanner {
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @SuppressLint("MissingPermission")
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> {
        if (!wifiManager.isWifiEnabled) {
            return Result.failure(IllegalStateException("Wi-Fi is turned off."))
        }

        wifiManager.startScan()
        val networks = wifiManager.scanResults
            .asSequence()
            .filter { it.SSID.isNotBlank() }
            .filter { it.isOpenNetwork() }
            .map { WifiNetwork(ssid = it.SSID, bssid = it.BSSID, capabilities = it.capabilities, level = it.level) }
            .distinctBy { it.bssid }
            .sortedByDescending { it.level }
            .toList()

        return Result.success(networks)
    }

    private fun ScanResult.isOpenNetwork(): Boolean {
        val caps = capabilities.uppercase()
        return !caps.contains("WEP") &&
            !caps.contains("PSK") &&
            !caps.contains("SAE") &&
            !caps.contains("EAP") &&
            !caps.contains("OWE")
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
            val connected = waitForConnection()
            if (connected) {
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

    private suspend fun waitForConnection(): Boolean {
        // Give the system time to auto-connect via the suggestion
        repeat(10) {
            delay(2_000)
            val activeNetwork = connectivityManager.activeNetwork ?: return@repeat
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return@repeat
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                connectivityManager.bindProcessToNetwork(activeNetwork)
                return true
            }
        }
        return false
    }

    private suspend fun connectViaSpecifier(ssid: String): ConnectAttemptResult {
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

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
                        activeCallback = callback
                        connectivityManager.bindProcessToNetwork(network)
                        if (!continuation.isCompleted) {
                            continuation.resume(ConnectAttemptResult.Connected)
                        }
                    }

                    override fun onUnavailable() {
                        failAndCleanup(ConnectAttemptResult.Failed("Network unavailable."))
                    }

                    override fun onLost(network: Network) {
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

        return outcome ?: ConnectAttemptResult.Failed("Timed out while trying to connect.")
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

    override fun getStatus(): CaptivePortalStatus {
        val network = connectivityManager.activeNetwork ?: return CaptivePortalStatus.UNKNOWN
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return CaptivePortalStatus.UNKNOWN

        return when {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                CaptivePortalStatus.CAPTIVE_PORTAL

            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                CaptivePortalStatus.OPEN_INTERNET

            else -> CaptivePortalStatus.UNKNOWN
        }
    }
}

class AndroidCaptivePortalResolver(context: Context) : CaptivePortalResolver {
    private val appContext = context.applicationContext

    override fun resolve() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(panelIntent)
            }.onSuccess {
                return
            }
        }

        val fallbackIntent = Intent(
            Intent.ACTION_VIEW,
            "http://connectivitycheck.gstatic.com/generate_204".toUri()
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(fallbackIntent)
    }
}

