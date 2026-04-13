package com.dm.labs.wifi.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.autoconnect.AutoConnectService
import com.dm.labs.wifi.autoconnect.NetworkCooldownManager
import com.dm.labs.wifi.captive.CaptivePortalSolverActivity
import com.dm.labs.wifi.data.AppDatabase
import com.dm.labs.wifi.data.CaptivePortalSolutionEntity
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.RoomCaptivePortalSolutionRepository
import com.dm.labs.wifi.data.RoomWifiNetworkRepository
import com.dm.labs.wifi.data.WifiNetworkEntity
import com.dm.labs.wifi.log.DevLog
import com.dm.labs.wifi.log.ScanLog
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.platform.AndroidCaptivePortalChecker
import com.dm.labs.wifi.platform.AndroidWifiConnector
import com.dm.labs.wifi.platform.AndroidWifiScanner
import com.dm.labs.wifi.settings.AppSettings
import com.dm.labs.wifi.ui.theme.WIFITheme

private data class NavItem(val label: String, val icon: @Composable () -> Unit)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DevLog.init(applicationContext)

        setContent {
            WIFITheme {
                val db = remember { AppDatabase.getDatabase(applicationContext) }
                val repository = remember { RoomWifiNetworkRepository(db.wifiNetworkDao()) }
                val solutionRepository = remember { RoomCaptivePortalSolutionRepository(db.captivePortalSolutionDao()) }
                val appSettings = remember { AppSettings.getInstance(applicationContext) }

                val vm: WifiViewModel = viewModel(
                    factory = WifiViewModelFactory(
                        scanner = AndroidWifiScanner(applicationContext),
                        connector = AndroidWifiConnector(applicationContext),
                        captivePortalChecker = AndroidCaptivePortalChecker(applicationContext),
                        repository = repository,
                        solutionRepository = solutionRepository,
                        appContext = applicationContext,
                        appSettings = appSettings
                    )
                )
                val state by vm.uiState.collectAsState()
                val logs by ScanLogManager.logs.collectAsState()
                var hasPermissions by remember { mutableStateOf(hasRequiredPermissions()) }
                var selectedTabIndex by remember { mutableStateOf(0) }
                var showSettingsDialog by remember { mutableStateOf(false) }

                // --- Solutions import/export state ---
                var exportJson by remember { mutableStateOf("") }
                var exportFileName by remember { mutableStateOf("solution.json") }
                val scope = rememberCoroutineScope()

                val exportDocLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null && exportJson.isNotEmpty()) {
                        try {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(exportJson.toByteArray())
                            }
                            Toast.makeText(this@MainActivity, "Exported successfully", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val importDocLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                                val count = vm.importSolutionsFromJson(json)
                                Toast.makeText(this@MainActivity, "Imported $count solution(s)", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

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

                if (showSettingsDialog) {
                    SettingsDialog(
                        appSettings = appSettings,
                        onDismiss = { showSettingsDialog = false }
                    )
                }

                val navItems = remember {
                    listOf(
                        NavItem("Scanner") { Icon(Icons.Default.Home, contentDescription = "Scanner") },
                        NavItem("Networks") { Icon(Icons.Default.Favorite, contentDescription = "Networks") },
                        NavItem("Solutions") { Icon(Icons.Default.PlayArrow, contentDescription = "Solutions") },
                        NavItem("Logs") { Icon(Icons.Default.Info, contentDescription = "Logs") }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when (selectedTabIndex) {
                                        0 -> "WiFi Scanner"
                                        1 -> "Saved Networks"
                                        2 -> "Portal Solutions"
                                        3 -> "Scan Logs"
                                        else -> "WiFi Scanner"
                                    }
                                )
                            },
                            actions = {
                                IconButton(onClick = { showSettingsDialog = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Settings")
                                }
                            }
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            navItems.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = selectedTabIndex == index,
                                    onClick = { selectedTabIndex = index },
                                    icon = item.icon,
                                    label = { Text(item.label) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTabIndex) {
                            0 -> WifiScreen(
                                state = state,
                                hasPermissions = hasPermissions,
                                cooldownSsids = remember { NetworkCooldownManager.getCooldownSsids() },
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

                            1 -> NetworksScreen(
                                whitelistedNetworks = state.whitelistedNetworks,
                                blacklistedNetworks = state.blacklistedNetworks,
                                onRemoveWhitelisted = { vm.toggleWhitelist(it.bssid, it.ssid) },
                                onRemoveBlacklisted = { vm.toggleBlacklist(it.bssid, it.ssid) },
                                onDelete = vm::removeNetwork,
                                modifier = Modifier.weight(1f)
                            )

                            2 -> SolutionsScreen(
                                solutions = state.solutions,
                                selectedDetail = state.selectedSolutionDetail,
                                onOpenDetail = vm::loadSolutionDetail,
                                onCloseDetail = vm::closeSolutionDetail,
                                onReplay = { sol ->
                                    CaptivePortalSolverActivity.launchReplay(
                                        this@MainActivity, sol.ssid, sol.id
                                    )
                                },
                                onDelete = vm::deleteSolution,
                                onUpdateSolutionInfo = vm::updateSolutionInfo,
                                onUpdateStep = vm::updateStep,
                                onDeleteStep = vm::deleteStep,
                                onExport = { sol ->
                                    scope.launch {
                                        val json = vm.exportSolutionToJson(sol.id)
                                        if (json != null) {
                                            exportJson = json
                                            exportFileName = "captive_solution_${sol.ssid}_${sol.id}.json"
                                            exportDocLauncher.launch(exportFileName)
                                        }
                                    }
                                },
                                onExportAll = {
                                    scope.launch {
                                        val json = vm.exportAllSolutionsToJson()
                                        exportJson = json
                                        exportFileName = "all_captive_solutions.json"
                                        exportDocLauncher.launch(exportFileName)
                                    }
                                },
                                onImport = { importDocLauncher.launch(arrayOf("application/json", "*/*")) },
                                onRefresh = vm::refreshSolutions,
                                modifier = Modifier.weight(1f)
                            )

                            3 -> LogsScreen(
                                logs = logs,
                                onClearLogs = { ScanLogManager.clearLogs() },
                                onExportDevLogs = {
                                    scope.launch {
                                        val content = DevLog.readAllLogs()
                                        if (content.isNotEmpty()) {
                                            exportJson = content
                                            exportFileName = "dev_logs_export.txt"
                                            exportDocLauncher.launch(exportFileName)
                                        } else {
                                            Toast.makeText(this@MainActivity, "No dev logs to export", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
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
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PermissionChecker.PERMISSION_GRANTED
        }
    }
}

@Composable
fun WifiScreen(
    state: WifiUiState,
    hasPermissions: Boolean,
    cooldownSsids: Set<String> = emptySet(),
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
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(20.dp),
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
                val isOnCooldown = network.ssid in cooldownSsids
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.isConnecting && !isBlacklisted && !isOnCooldown) {
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
                                    Text(
                                        text = network.ssid,
                                        style = MaterialTheme.typography.titleSmall
                                    )
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
                                    if (isOnCooldown) {
                                        Text(
                                            " ⏳",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.outline
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
                                if (isOnCooldown) {
                                    Text(
                                        text = "⏳ Cooldown – no internet detected",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
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
fun NetworksScreen(
    whitelistedNetworks: List<WifiNetworkEntity>,
    blacklistedNetworks: List<WifiNetworkEntity>,
    onRemoveWhitelisted: (WifiNetworkEntity) -> Unit,
    onRemoveBlacklisted: (WifiNetworkEntity) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showWhitelist by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ── Filter chips toggle ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showWhitelist,
                onClick = { showWhitelist = true },
                label = { Text("★ Favourites (${whitelistedNetworks.size})") }
            )
            FilterChip(
                selected = !showWhitelist,
                onClick = { showWhitelist = false },
                label = { Text("✖ Blocked (${blacklistedNetworks.size})") }
            )
        }

        if (showWhitelist) {
            NetworkListContent(
                emptyMessage = "No favourite networks yet.\nFavourite a network from the Scanner tab.",
                networks = whitelistedNetworks,
                onRemove = onRemoveWhitelisted,
                onDelete = onDelete,
                modifier = Modifier.weight(1f)
            )
        } else {
            NetworkListContent(
                emptyMessage = "No blocked networks yet.\nBlock a network from the Scanner tab.",
                networks = blacklistedNetworks,
                onRemove = onRemoveBlacklisted,
                onDelete = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NetworkListContent(
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
            text = { Text("Remove ${network.ssid} (${network.bssid}) permanently?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(network.bssid)
                    confirmDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (networks.isEmpty()) {
        Text(
            text = emptyMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp)
        )
    }

    LazyColumn(
        modifier = modifier,
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
                            text = "\uD83D\uDCCD ${
                                String.format(java.util.Locale.US, "%.5f", network.latitude)
                            }, ${
                                String.format(java.util.Locale.US, "%.5f", network.longitude)
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "\uD83D\uDCCD Location unknown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
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

@Composable
fun SolutionsScreen(
    solutions: List<CaptivePortalSolutionEntity>,
    selectedDetail: com.dm.labs.wifi.data.SolutionWithSteps?,
    onOpenDetail: (Long) -> Unit,
    onCloseDetail: () -> Unit,
    onReplay: (CaptivePortalSolutionEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onUpdateSolutionInfo: (Long, String, String, String) -> Unit,
    onUpdateStep: (CaptivePortalStepEntity) -> Unit,
    onDeleteStep: (Long, Long) -> Unit,
    onExport: (CaptivePortalSolutionEntity) -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { onRefresh() }

    if (selectedDetail != null) {
        SolutionDetailScreen(
            detail = selectedDetail,
            onBack = onCloseDetail,
            onReplay = { onReplay(selectedDetail.solution) },
            onDelete = { onDelete(selectedDetail.solution.id) },
            onExport = { onExport(selectedDetail.solution) },
            onUpdateSolutionInfo = onUpdateSolutionInfo,
            onUpdateStep = onUpdateStep,
            onDeleteStep = onDeleteStep,
            modifier = modifier
        )
    } else {
        SolutionListScreen(
            solutions = solutions,
            onOpenDetail = onOpenDetail,
            onReplay = onReplay,
            onDelete = onDelete,
            onExport = onExport,
            onExportAll = onExportAll,
            onImport = onImport,
            modifier = modifier
        )
    }
}

@Composable
private fun SolutionListScreen(
    solutions: List<CaptivePortalSolutionEntity>,
    onOpenDetail: (Long) -> Unit,
    onReplay: (CaptivePortalSolutionEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: (CaptivePortalSolutionEntity) -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDelete by remember { mutableStateOf<CaptivePortalSolutionEntity?>(null) }
    val dateFormat = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    }

    confirmDelete?.let { sol ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete Solution") },
            text = { Text("Delete the recorded solution for \"${sol.ssid}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(sol.id)
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
            text = "Portal Solutions (${solutions.size})",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text = "Record how you solve captive portals and share with others.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onImport) {
                Text("\uD83D\uDCE5 Import")
            }
            if (solutions.isNotEmpty()) {
                Button(onClick = onExportAll) {
                    Text("\uD83D\uDCE4 Export All")
                }
            }
        }

        if (solutions.isEmpty()) {
            Text(
                text = "No recorded solutions yet.\n\nTo record:\n1. Connect to a network with a captive portal\n2. Open the Captive Portal Solver\n3. Tap \"\u23FA Record Steps\"\n4. Solve the portal manually\n5. Tap \"\u23F9 Stop Recording\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(solutions, key = { it.id }) { solution ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(solution.id) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = solution.ssid,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${solution.stepCount} steps recorded",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "\uD83D\uDCCB",
                                style = MaterialTheme.typography.titleLarge
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                        if (solution.description.isNotEmpty()) {
                            Text(
                                text = solution.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Portal: ${solution.portalUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Created: ${dateFormat.format(java.util.Date(solution.createdAt))}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { onReplay(solution) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text("\u25B6 Replay", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { onExport(solution) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text("\uD83D\uDCE4 Export", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { confirmDelete = solution },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text("Delete", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Tap hint
                        Text(
                            text = "Tap to view & edit steps →",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }
    }
}

// ─── Solution Detail / Edit Screen ───────────────────────────────────────────

@Composable
private fun SolutionDetailScreen(
    detail: com.dm.labs.wifi.data.SolutionWithSteps,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onUpdateSolutionInfo: (Long, String, String, String) -> Unit,
    onUpdateStep: (CaptivePortalStepEntity) -> Unit,
    onDeleteStep: (Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val solution = detail.solution
    val steps = detail.steps

    var showEditSolution by remember { mutableStateOf(false) }
    var editingStep by remember { mutableStateOf<CaptivePortalStepEntity?>(null) }
    var confirmDeleteStep by remember { mutableStateOf<CaptivePortalStepEntity?>(null) }
    var confirmDeleteSolution by remember { mutableStateOf(false) }

    // ── Edit Solution Dialog ──
    if (showEditSolution) {
        EditSolutionDialog(
            solution = solution,
            onDismiss = { showEditSolution = false },
            onSave = { ssid, desc, portalUrl ->
                onUpdateSolutionInfo(solution.id, ssid, desc, portalUrl)
                showEditSolution = false
            }
        )
    }

    // ── Edit Step Dialog ──
    editingStep?.let { step ->
        EditStepDialog(
            step = step,
            onDismiss = { editingStep = null },
            onSave = { updated ->
                onUpdateStep(updated)
                editingStep = null
            }
        )
    }

    // ── Confirm Delete Step ──
    confirmDeleteStep?.let { step ->
        AlertDialog(
            onDismissRequest = { confirmDeleteStep = null },
            title = { Text("Delete Step") },
            text = { Text("Delete step #${step.stepOrder + 1} (${step.type})?") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteStep(solution.id, step.id)
                    confirmDeleteStep = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteStep = null }) { Text("Cancel") }
            }
        )
    }

    // ── Confirm Delete Solution ──
    if (confirmDeleteSolution) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSolution = false },
            title = { Text("Delete Solution") },
            text = { Text("Delete the entire solution for \"${solution.ssid}\" and all its steps?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSolution = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSolution = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // ── Header with back ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back")
            }
            Text(
                text = "Solution Details",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // ── Solution info card ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = solution.ssid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showEditSolution = true }) {
                        Text("\u270F\uFE0F Edit")
                    }
                }
                if (solution.description.isNotEmpty()) {
                    Text(
                        text = solution.description,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = "Portal URL: ${solution.portalUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = "${steps.size} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onReplay,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("\u25B6 Replay", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = onExport,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("\uD83D\uDCE4 Export", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { confirmDeleteSolution = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("Delete", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ── Steps header ──
        Text(
            text = "Steps",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        if (steps.isEmpty()) {
            Text(
                text = "No steps recorded for this solution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Steps list ──
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(steps, key = { it.id }) { step ->
                StepCard(
                    step = step,
                    onEdit = { editingStep = step },
                    onDelete = { confirmDeleteStep = step }
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    step: CaptivePortalStepEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val typeIcon = when (step.type) {
        "NAVIGATION" -> "\uD83C\uDF10"  // 🌐
        "CLICK" -> "\uD83D\uDC46"       // 👆
        "INPUT" -> "\u2328\uFE0F"         // ⌨️
        "FORM_SUBMIT" -> "\uD83D\uDCE8"  // 📨
        else -> "\u2753"                  // ❓
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$typeIcon #${step.stepOrder + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "  ${step.type}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (step.url.isNotEmpty()) {
                Text(
                    text = "URL: ${step.url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (step.cssSelector.isNotEmpty()) {
                Text(
                    text = "Selector: ${step.cssSelector}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (step.elementId.isNotEmpty()) {
                Text(
                    text = "Element ID: ${step.elementId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (step.elementName.isNotEmpty()) {
                Text(
                    text = "Element Name: ${step.elementName}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (step.elementType.isNotEmpty()) {
                Text(
                    text = "Element Type: ${step.elementType}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (step.inputValue.isNotEmpty()) {
                Text(
                    text = "Value: ${step.inputValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (step.formData.isNotEmpty()) {
                Text(
                    text = "Form data: ${step.formData}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEdit) {
                    Text("\u270F\uFE0F Edit", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDelete) {
                    Text(
                        "\uD83D\uDDD1\uFE0F Delete",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ─── Edit Solution Dialog ────────────────────────────────────────────────────

@Composable
private fun EditSolutionDialog(
    solution: CaptivePortalSolutionEntity,
    onDismiss: () -> Unit,
    onSave: (ssid: String, description: String, portalUrl: String) -> Unit
) {
    var ssid by remember(solution.id) { mutableStateOf(solution.ssid) }
    var description by remember(solution.id) { mutableStateOf(solution.description) }
    var portalUrl by remember(solution.id) { mutableStateOf(solution.portalUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Solution") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                OutlinedTextField(
                    value = portalUrl,
                    onValueChange = { portalUrl = it },
                    label = { Text("Portal URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(ssid, description, portalUrl) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─── Edit Step Dialog ────────────────────────────────────────────────────────

@Composable
private fun EditStepDialog(
    step: CaptivePortalStepEntity,
    onDismiss: () -> Unit,
    onSave: (CaptivePortalStepEntity) -> Unit
) {
    var type by remember(step.id) { mutableStateOf(step.type) }
    var url by remember(step.id) { mutableStateOf(step.url) }
    var cssSelector by remember(step.id) { mutableStateOf(step.cssSelector) }
    var inputValue by remember(step.id) { mutableStateOf(step.inputValue) }
    var elementId by remember(step.id) { mutableStateOf(step.elementId) }
    var elementName by remember(step.id) { mutableStateOf(step.elementName) }
    var elementType by remember(step.id) { mutableStateOf(step.elementType) }
    var formData by remember(step.id) { mutableStateOf(step.formData) }

    var showTypeDropdown by remember { mutableStateOf(false) }
    val stepTypes = listOf("NAVIGATION", "CLICK", "INPUT", "FORM_SUBMIT")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Step #${step.stepOrder + 1}") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    // Type selector
                    Column {
                        Text("Type", style = MaterialTheme.typography.labelMedium)
                        Button(onClick = { showTypeDropdown = true }) {
                            Text(type)
                        }
                        DropdownMenu(
                            expanded = showTypeDropdown,
                            onDismissRequest = { showTypeDropdown = false }
                        ) {
                            stepTypes.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t) },
                                    onClick = {
                                        type = t
                                        showTypeDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = cssSelector,
                        onValueChange = { cssSelector = it },
                        label = { Text("CSS Selector") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
                item {
                    OutlinedTextField(
                        value = elementId,
                        onValueChange = { elementId = it },
                        label = { Text("Element ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = elementName,
                        onValueChange = { elementName = it },
                        label = { Text("Element Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = elementType,
                        onValueChange = { elementType = it },
                        label = { Text("Element Type") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        label = { Text("Input Value") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2
                    )
                }
                item {
                    OutlinedTextField(
                        value = formData,
                        onValueChange = { formData = it },
                        label = { Text("Form Data (JSON)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    step.copy(
                        type = type,
                        url = url,
                        cssSelector = cssSelector,
                        inputValue = inputValue,
                        elementId = elementId,
                        elementName = elementName,
                        elementType = elementType,
                        formData = formData
                    )
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun LogsScreen(
    logs: List<ScanLog>,
    onClearLogs: () -> Unit,
    onExportDevLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showScanLogs by remember { mutableStateOf(true) }

    Column(modifier = modifier
        .fillMaxSize()
        .padding(16.dp)) {

        // ── Toggle chips ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = showScanLogs,
                onClick = { showScanLogs = true },
                label = { Text("Scan Logs") }
            )
            FilterChip(
                selected = !showScanLogs,
                onClick = { showScanLogs = false },
                label = { Text("Dev Logs") }
            )
        }

        if (showScanLogs) {
            // ── Scan Logs ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Scan Logs (${logs.size})", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onClearLogs) {
                    Text("Clear")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            "No scan logs yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(logs) { log ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    java.util.Locale.getDefault()
                                ).format(java.util.Date(log.timestamp)),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(text = log.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        } else {
            // ── Dev Logs ──
            val devLogContent = remember { mutableStateOf("") }
            val devLogFiles = remember { mutableStateOf(DevLog.getLogFiles()) }

            LaunchedEffect(showScanLogs) {
                devLogFiles.value = DevLog.getLogFiles()
                devLogContent.value = DevLog.readAllLogs()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Dev Logs (${devLogFiles.value.size} file${if (devLogFiles.value.size != 1) "s" else ""})",
                    style = MaterialTheme.typography.titleMedium
                )
                Button(
                    onClick = onExportDevLogs,
                    enabled = devLogContent.value.isNotEmpty()
                ) {
                    Text("📤 Export")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (devLogContent.value.isEmpty()) {
                Text(
                    "No developer logs.\n\nEnable developer logging in Settings to capture detailed troubleshooting data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = devLogContent.value,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    appSettings: AppSettings,
    onDismiss: () -> Unit
) {
    val settingsState by appSettings.state.collectAsState()
    var textValue by remember(settingsState.scanIntervalSeconds) {
        mutableStateOf(settingsState.scanIntervalSeconds.toString())
    }

    fun applyValue(newVal: Int) {
        val clamped = newVal.coerceIn(AppSettings.MIN_SCAN_INTERVAL, AppSettings.MAX_SCAN_INTERVAL)
        textValue = clamped.toString()
        appSettings.scanIntervalSeconds = clamped
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // ── Scan Interval ──
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Scan interval", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "How often the app scans for open Wi-Fi networks (${AppSettings.MIN_SCAN_INTERVAL}–${AppSettings.MAX_SCAN_INTERVAL} s).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(onClick = {
                                applyValue(
                                    (textValue.toIntOrNull() ?: settingsState.scanIntervalSeconds) - 1
                                )
                            }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Decrease")
                            }
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { input ->
                                    textValue = input.filter { it.isDigit() }
                                    input.toIntOrNull()?.let { applyValue(it) }
                                },
                                modifier = Modifier.width(80.dp),
                                textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text("s") }
                            )
                            IconButton(onClick = {
                                applyValue(
                                    (textValue.toIntOrNull() ?: settingsState.scanIntervalSeconds) + 1
                                )
                            }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Increase")
                            }
                        }
                    }
                }

                // ── Auto-start on boot ──
                item {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Auto-start on boot", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Start the auto-connect service automatically when the device boots. The service runs until you close the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (settingsState.autoStartOnBoot) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settingsState.autoStartOnBoot,
                                onCheckedChange = { appSettings.autoStartOnBoot = it }
                            )
                        }
                    }
                }

                // ── Developer Logging ──
                item {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Developer logging", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Write detailed troubleshooting logs to a file. Logs are kept for 7 days. Enable this if you need to diagnose connection issues.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (settingsState.developerLogging) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Switch(
                                checked = settingsState.developerLogging,
                                onCheckedChange = { appSettings.developerLogging = it }
                            )
                        }
                    }
                }
                // ── App info (version & last install) ──
                item {
                    HorizontalDivider()
                    // Use LocalContext to read package info for version and last update time
                    val ctx = LocalContext.current
                    val pkgInfo = try {
                        val pm = ctx.packageManager
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(ctx.packageName, 0)
                        }
                    } catch (e: Exception) {
                        null
                    }

                    val versionName = pkgInfo?.versionName ?: "unknown"
                    val versionCode = pkgInfo?.let {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode.toString()
                        else it.versionCode.toString()
                    } ?: "?"
                    val lastUpdate = pkgInfo?.lastUpdateTime ?: 0L
                    val lastUpdateText = if (lastUpdate > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastUpdate)) else "unknown"

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("App", style = MaterialTheme.typography.titleMedium)
                        Text("Version: $versionName (code $versionCode)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Last installed/updated: $lastUpdateText", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun WifiScreenPreview() {
    WIFITheme {
        WifiScreen(
            state = WifiUiState(
                networks = listOf(
                    WifiNetwork(
                        "Cafe Open Wi-Fi",
                        "00:11:22:33:44:55",
                        "[ESS]",
                        -56
                    )
                ),
                statusMessage = "Connected to Cafe Open Wi-Fi, but sign-in/captcha is required.",
                needsPortalLogin = true
            ),
            hasPermissions = true,
            cooldownSsids = emptySet(),
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

