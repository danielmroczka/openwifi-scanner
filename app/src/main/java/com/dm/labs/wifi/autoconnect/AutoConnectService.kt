package com.dm.labs.wifi.autoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dm.labs.wifi.R
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.PendingNetworkApproval
import com.dm.labs.wifi.captive.CaptivePortalAutoSolver
import com.dm.labs.wifi.data.AppDatabase
import com.dm.labs.wifi.data.RoomWifiNetworkRepository
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.platform.AndroidCaptivePortalChecker
import com.dm.labs.wifi.platform.AndroidWifiConnector
import com.dm.labs.wifi.platform.AndroidWifiScanner
import com.dm.labs.wifi.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutoConnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoJob: Job? = null
    private var shouldRun: Boolean = false

    private lateinit var connector: WifiConnector
    private lateinit var checker: CaptivePortalChecker
    private lateinit var coordinator: AutoConnectCoordinator
    private lateinit var portalSolver: CaptivePortalAutoSolver
    private lateinit var appSettings: AppSettings

    override fun onCreate() {
        super.onCreate()

        val scanner = AndroidWifiScanner(applicationContext)
        connector = AndroidWifiConnector(applicationContext)
        checker = AndroidCaptivePortalChecker(applicationContext)
        val repository =
            RoomWifiNetworkRepository(AppDatabase.getDatabase(applicationContext).wifiNetworkDao())

        coordinator = AutoConnectCoordinator(scanner, connector, checker, repository)
        portalSolver = CaptivePortalAutoSolver(applicationContext, checker)
        appSettings = AppSettings.getInstance(applicationContext)

        AutoConnectRuntime.update(
            BackgroundAutoConnectState(
                isRunning = true,
                message = "Service started."
            )
        )
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAutoConnect()
        return START_STICKY
    }

    override fun onDestroy() {
        shouldRun = false
        autoJob?.cancel()
        NetworkApprovalManager.clear()
        if (::connector.isInitialized) {
            connector.disconnectCurrentNetwork()
        }
        AutoConnectRuntime.reset()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAutoConnect() {
        if (autoJob?.isActive == true) return

        shouldRun = true
        startForeground(NOTIFICATION_ID, buildNotification("Auto-connect started"))

        autoJob = serviceScope.launch {
            var attempts = AutoConnectRuntime.state.value.attempts
            var connected = false

            while (isActive && shouldRun) {
                if (!connected) {
                    val state = coordinator.connectNextOpenNetworkCycle(
                        stopSignal = { !shouldRun },
                        previousAttempts = attempts,
                        onUpdate = { pushState(it) },
                        onUnknownNetwork = { network ->
                            NetworkApprovalManager.requestApproval(
                                PendingNetworkApproval(network.ssid, network.bssid, network.level)
                            )
                            NetworkApprovalManager.awaitDecision()
                        }
                    )
                    attempts = state.attempts
                    pushState(state)

                    when {
                        state.hasValidatedInternet -> {
                            connected = true
                        }

                        state.captivePortalDetected -> {
                            pushState(state.copy(message = "Solving captive portal on ${state.currentSsid}…"))
                            val solved = portalSolver.trySolve()
                            if (solved) {
                                connected = true
                                pushState(
                                    state.copy(
                                        captivePortalDetected = false,
                                        hasValidatedInternet = true,
                                        message = "Connected to ${state.currentSsid} with internet."
                                    )
                                )
                            } else {
                                connector.disconnectCurrentNetwork()
                                ScanLogManager.log("Portal solve failed on ${state.currentSsid}, moving on.")
                                pushState(
                                    BackgroundAutoConnectState(
                                        isRunning = true,
                                        attempts = attempts,
                                        message = "Portal solve failed. Retrying…"
                                    )
                                )
                            }
                        }

                        else -> delay(appSettings.state.value.scanIntervalMs)
                    }
                } else {
                    val status = checker.getStatus()
                    when (status) {
                        CaptivePortalStatus.OPEN_INTERNET -> {
                            pushState(
                                BackgroundAutoConnectState(
                                    isRunning = true,
                                    currentSsid = AutoConnectRuntime.state.value.currentSsid,
                                    attempts = attempts,
                                    hasValidatedInternet = true,
                                    message = "Internet is available. Monitoring connection…"
                                )
                            )
                        }

                        else -> {
                            connector.disconnectCurrentNetwork()
                            connected = false
                            pushState(
                                BackgroundAutoConnectState(
                                    isRunning = true,
                                    attempts = attempts,
                                    captivePortalDetected = status == CaptivePortalStatus.CAPTIVE_PORTAL,
                                    message = "Internet lost. Trying next open network…"
                                )
                            )
                        }
                    }
                    delay(appSettings.state.value.scanIntervalMs)
                }
            }
        }
    }

    private fun pushState(state: BackgroundAutoConnectState) {
        AutoConnectRuntime.update(state)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state.message))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.auto_connect_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.auto_connect_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "auto_connect_channel"
        private const val NOTIFICATION_ID = 88

        fun start(context: Context) {
            val intent = Intent(context, AutoConnectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoConnectService::class.java))
        }
    }
}

