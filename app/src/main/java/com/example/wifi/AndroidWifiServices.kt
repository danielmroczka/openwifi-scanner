package com.example.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidWifiScanner(private val context: Context) : WifiScanner {
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
            .map { WifiNetwork(ssid = it.SSID, capabilities = it.capabilities, level = it.level) }
            .distinctBy { it.ssid }
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

class AndroidWifiConnector(private val context: Context) : WifiConnector {
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    @RequiresPermission(anyOf = [Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.NEARBY_WIFI_DEVICES])
    override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult {
        disconnectCurrentNetwork()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConnectAttemptResult.Unsupported(
                "Automatic connection is only implemented for Android 10+ in this app."
            )
        }

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
            Uri.parse("http://connectivitycheck.gstatic.com/generate_204")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(fallbackIntent)
    }
}

