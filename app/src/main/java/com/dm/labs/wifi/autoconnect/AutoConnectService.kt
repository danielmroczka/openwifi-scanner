package com.dm.labs.wifi.autoconnect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dm.labs.wifi.R
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.PendingNetworkApproval
import com.dm.labs.wifi.captive.CaptivePortalAutoSolver
import com.dm.labs.wifi.data.AppDatabase
import com.dm.labs.wifi.data.RoomCaptivePortalSolutionRepository
import com.dm.labs.wifi.data.RoomWifiNetworkRepository
import com.dm.labs.wifi.log.DevLog
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
    private lateinit var captivePortalRecoveryHandler: CaptivePortalRecoveryHandler
    private lateinit var appSettings: AppSettings
    private val approvalNotificationId = NOTIFICATION_ID + 1

    override fun onCreate() {
        super.onCreate()

        DevLog.init(applicationContext)

        val scanner = AndroidWifiScanner(applicationContext)
        connector = AndroidWifiConnector(applicationContext)
        checker = AndroidCaptivePortalChecker(applicationContext)
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = RoomWifiNetworkRepository(database.wifiNetworkDao())
        val solutionRepository = RoomCaptivePortalSolutionRepository(database.captivePortalSolutionDao())

        coordinator = AutoConnectCoordinator(scanner, connector, checker, repository)
        portalSolver = CaptivePortalAutoSolver.create(applicationContext, checker, solutionRepository)
        captivePortalRecoveryHandler = CaptivePortalRecoveryHandler(
            portalSolver = { ssid -> portalSolver.trySolve(ssid) },
            disconnectCurrentNetwork = { connector.disconnectCurrentNetwork() },
            putOnCooldown = { ssid -> NetworkCooldownManager.putOnCooldown(ssid) },
            scanLog = { message -> ScanLogManager.log(message) },
            devInfo = { message -> DevLog.i(message) },
            devWarn = { message -> DevLog.w(message) }
        )
        appSettings = AppSettings.getInstance(applicationContext)

        ScanLogManager.log("Auto-connect scanner started.")
        DevLog.i("AutoConnectService created.")
        DevLog.i("Notification permission granted: ${hasNotificationPermission()}")

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
        clearApprovalNotification()
        NetworkApprovalManager.clear()
        if (::connector.isInitialized) {
            connector.disconnectCurrentNetwork()
        }
        ScanLogManager.log("Auto-connect scanner stopped.")
        DevLog.i("AutoConnectService destroyed.")
        AutoConnectRuntime.reset()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DevLog.i("App removed from recents – stopping AutoConnectService.")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAutoConnect() {
        if (autoJob?.isActive == true) return

        shouldRun = true
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Auto-connect started"))
        } catch (e: Exception) {
            DevLog.w("Failed to start foreground service: ${e.message}")
            ScanLogManager.log("Warning: Could not display notification. Grant 'Display over other apps' permission.")
        }

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
                            showApprovalNotification(network.ssid, network.bssid)
                            val decision = NetworkApprovalManager.awaitDecision()
                            clearApprovalNotification()
                            decision
                        }
                    )
                    attempts = state.attempts
                    pushState(state)

                    when {
                        state.hasValidatedInternet -> {
                            connected = true
                        }

                        state.captivePortalDetected -> {
                            val recoveryState = captivePortalRecoveryHandler.recover(
                                state = state,
                                attempts = attempts,
                                pushState = { pushState(it) }
                            )
                            connected = recoveryState.hasValidatedInternet
                        }

                        else -> delay(appSettings.state.value.scanIntervalMs)
                    }
                } else {
                    val status = checker.getStatus()
                    DevLog.d("Connection monitor check: $status")
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
                            val ssid = AutoConnectRuntime.state.value.currentSsid
                            connector.disconnectCurrentNetwork()
                            connected = false
                            ScanLogManager.log("Internet lost on $ssid. Reconnecting…")
                            DevLog.w("Internet lost (status=$status) on $ssid. Will reconnect.")
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

    private fun showApprovalNotification(ssid: String, bssid: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val whitelistIntent = Intent(this, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_APPROVAL_DECISION
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, "WHITELIST")
        }
        val blacklistIntent = Intent(this, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_APPROVAL_DECISION
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, "BLACKLIST")
        }
        val skipIntent = Intent(this, ApprovalActionReceiver::class.java).apply {
            action = ApprovalActionReceiver.ACTION_APPROVAL_DECISION
            putExtra(ApprovalActionReceiver.EXTRA_DECISION, "SKIP")
        }

        val whitelistPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            whitelistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val blacklistPendingIntent = PendingIntent.getBroadcast(
            this,
            102,
            blacklistIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipPendingIntent = PendingIntent.getBroadcast(
            this,
            103,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Unknown open network")
            .setContentText("$ssid ($bssid) found. Connect?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, "Connect & Favourite", whitelistPendingIntent)
            .addAction(0, "Block", blacklistPendingIntent)
            .addAction(0, "Skip", skipPendingIntent)
            .build()

        manager.notify(approvalNotificationId, notification)
    }

    private fun clearApprovalNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(approvalNotificationId)
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
        val intent = Intent(this, com.dm.labs.wifi.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.auto_connect_notification_title))
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

