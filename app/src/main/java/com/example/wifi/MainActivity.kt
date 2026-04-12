package com.example.wifi

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi.data.AppDatabase
import com.example.wifi.data.RoomWifiNetworkRepository
import com.example.wifi.data.WifiNetworkEntity
import com.example.wifi.ui.theme.WIFITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WIFITheme {
                val db = remember { AppDatabase.getDatabase(applicationContext) }
                val repository = remember { RoomWifiNetworkRepository(db.wifiNetworkDao()) }

                val vm: WifiViewModel = viewModel(
                    factory = WifiViewModelFactory(
                        scanner = AndroidWifiScanner(applicationContext),
                        connector = AndroidWifiConnector(applicationContext),
                        captivePortalChecker = AndroidCaptivePortalChecker(applicationContext),
                        repository = repository,
                        appContext = applicationContext
                    )
                )
                val state by vm.uiState.collectAsState()
                val logs by ScanLogManager.logs.collectAsState()
                var hasPermissions by remember { mutableStateOf(hasRequiredPermissions()) }
                var selectedTabIndex by remember { mutableStateOf(0) }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    hasPermissions = hasRequiredPermissions()
                }

                LaunchedEffect(hasPermissions) {
                    if (hasPermissions) {
                        AutoConnectService.start(this@MainActivity)
                        vm.startPeriodicScan()
                    } else {
                        vm.stopPeriodicScan()
                    }
                }

                val tabTitles = listOf("Scanner", "Whitelist", "Blacklist", "Logs")

                // Approval dialog for unknown networks
                state.pendingApproval?.let { pending ->
                    AlertDialog(
                        onDismissRequest = { vm.handleApprovalDecision(UserNetworkDecision.SKIP) },
                        title = { Text("Unknown Network") },
                        text = {
                            Column {
                                Text("An open network was found:")
                                Text(
                                    pending.ssid,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "BSSID: ${pending.bssid}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Signal: ${pending.level} dBm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { vm.handleApprovalDecision(UserNetworkDecision.WHITELIST) }) {
                                Text("Connect & Whitelist")
                            }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { vm.handleApprovalDecision(UserNetworkDecision.BLACKLIST) }) {
                                    Text("Block", color = MaterialTheme.colorScheme.error)
                                }
                                TextButton(onClick = { vm.handleApprovalDecision(UserNetworkDecision.SKIP) }) {
                                    Text("Skip")
                                }
                            }
                        }
                    )
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        TabRow(selectedTabIndex = selectedTabIndex) {
                            tabTitles.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTabIndex == index,
                                    onClick = { selectedTabIndex = index }
                                ) {
                                    Text(title, modifier = Modifier.padding(16.dp))
                                }
                            }
                        }

                        when (selectedTabIndex) {
                            0 -> WifiScreen(
                                state = state,
                                hasPermissions = hasPermissions,
                                onRequestPermissions = {
                                    permissionsLauncher.launch(requiredPermissions())
                                },
                                onScan = vm::scanOpenNetworks,
                                onConnect = vm::connectToNetwork,
                                onToggleBlacklist = vm::toggleBlacklist,
                                onToggleWhitelist = vm::toggleWhitelist,
                                onStartAutoConnect = { AutoConnectService.start(this@MainActivity) },
                                onStopAutoConnect = { AutoConnectService.stop(this@MainActivity) },
                                onResolvePortal = { CaptivePortalSolverActivity.launch(this@MainActivity) },
                                modifier = Modifier.weight(1f)
                            )
                            1 -> NetworkListScreen(
                                title = "Whitelisted Networks",
                                emptyMessage = "No whitelisted networks yet.\nFavourite a network from the Scanner tab.",
                                networks = state.whitelistedNetworks,
                                onRemove = { vm.toggleWhitelist(it.bssid, it.ssid) },
                                onDelete = vm::removeNetwork,
                                modifier = Modifier.weight(1f)
                            )
                            2 -> NetworkListScreen(
                                title = "Blacklisted Networks",
                                emptyMessage = "No blacklisted networks yet.\nBlock a network from the Scanner tab.",
                                networks = state.blacklistedNetworks,
                                onRemove = { vm.toggleBlacklist(it.bssid, it.ssid) },
                                onDelete = vm::removeNetwork,
                                modifier = Modifier.weight(1f)
                            )
                            3 -> LogsScreen(
                                logs = logs,
                                onClearLogs = { ScanLogManager.clearLogs() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED
        }
    }
}

@Composable
fun WifiScreen(
    state: WifiUiState,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onToggleBlacklist: (String, String) -> Unit,
    onToggleWhitelist: (String, String) -> Unit,
    onStartAutoConnect: () -> Unit,
    onStopAutoConnect: () -> Unit,
    onResolvePortal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasPermissions) {
            Text("Grant Wi-Fi and location permissions to scan nearby networks.")
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = "package:${context.packageName}".toUri()
                    })
                }
            ) {
                Text("Open App Settings")
            }
            return@Column
        }

        // Controls row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onStartAutoConnect, enabled = !state.autoConnectRunning) {
                Text("Auto-Connect")
            }
            Button(onClick = onStopAutoConnect, enabled = state.autoConnectRunning) {
                Text("Stop")
            }
            if (state.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        state.autoConnectMessage?.let {
            Text("Auto-connect: $it", style = MaterialTheme.typography.bodySmall)
        }

        state.statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        if (state.needsPortalLogin) {
            Button(onClick = onResolvePortal) {
                Text("Resolve Captive Portal")
            }
        }

        // Network list header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Open Networks (${state.networks.size})",
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.lastScanTimestamp > 0L) {
                    Text(
                        text = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(state.lastScanTimestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Button(onClick = onScan, enabled = !state.isScanning) {
                    Text("Scan")
                }
            }
        }

        // Network list
        if (state.networks.isEmpty() && !state.isScanning) {
            Text(
                text = "No open networks found nearby.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.networks) { network ->
                val isBlacklisted = network.bssid in state.blacklistedBssids
                val isWhitelisted = network.bssid in state.whitelistedBssids
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.isConnecting && !isBlacklisted) {
                            onConnect(network.ssid)
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = network.ssid, style = MaterialTheme.typography.titleSmall)
                                    if (isWhitelisted) {
                                        Text(
                                            " ★",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (isBlacklisted) {
                                        Text(
                                            " ✖",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                Text(
                                    text = "BSSID: ${network.bssid}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${network.level} dBm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            val signalStrength = when {
                                network.level >= -50 -> "▂▄▆█"
                                network.level >= -60 -> "▂▄▆_"
                                network.level >= -70 -> "▂▄__"
                                else -> "▂___"
                            }
                            Text(
                                text = signalStrength,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onToggleWhitelist(network.bssid, network.ssid) },
                                modifier = Modifier.size(height = 32.dp, width = 100.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (isWhitelisted) "Unfavourite" else "Favourite",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Button(
                                onClick = { onToggleBlacklist(network.bssid, network.ssid) },
                                modifier = Modifier.size(height = 32.dp, width = 100.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    if (isBlacklisted) "Unblock" else "Block",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkListScreen(
    title: String,
    emptyMessage: String,
    networks: List<WifiNetworkEntity>,
    onRemove: (WifiNetworkEntity) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf<WifiNetworkEntity?>(null) }
    val dateFormat = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    }

    confirmDelete?.let { network ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete Network") },
            text = { Text("Remove ${network.ssid} (${network.bssid}) permanently from the database?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(network.bssid)
                    confirmDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "$title (${networks.size})",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (networks.isEmpty()) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(networks, key = { it.bssid }) { network ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = network.ssid,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "BSSID: ${network.bssid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        Text(
                            text = "Added: ${dateFormat.format(java.util.Date(network.dateAdded))}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Last connected: ${dateFormat.format(java.util.Date(network.lastConnected))}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (network.latitude != null && network.longitude != null) {
                            Text(
                                text = "📍 ${String.format(java.util.Locale.US, "%.5f", network.latitude)}, ${String.format(java.util.Locale.US, "%.5f", network.longitude)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text(
                                text = "📍 Location unknown",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onRemove(network) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Remove from list", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { confirmDelete = network },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Delete", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<ScanLog>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Scan Logs", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onClearLogs) {
                Text("Clear")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logs.isEmpty()) {
                item {
                    Text("No logs yet.")
                }
            }
            items(logs) { log ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(text = log.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun WifiScreenPreview() {
    WIFITheme {
        WifiScreen(
            state = WifiUiState(
                networks = listOf(WifiNetwork("Cafe Open Wi-Fi", "00:11:22:33:44:55", "[ESS]", -56)),
                statusMessage = "Connected to Cafe Open Wi-Fi, but sign-in/captcha is required.",
                needsPortalLogin = true
            ),
            hasPermissions = true,
            onRequestPermissions = {},
            onScan = {},
            onConnect = {},
            onToggleBlacklist = { _, _ -> },
            onToggleWhitelist = { _, _ -> },
            onStartAutoConnect = {},
            onStopAutoConnect = {},
            onResolvePortal = {}
        )
    }
}
