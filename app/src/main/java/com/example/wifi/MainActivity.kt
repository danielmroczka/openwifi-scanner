package com.example.wifi

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wifi.ui.theme.WIFITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WIFITheme {
                val vm: WifiViewModel = viewModel(
                    factory = WifiViewModelFactory(
                        scanner = AndroidWifiScanner(applicationContext),
                        connector = AndroidWifiConnector(applicationContext),
                        captivePortalChecker = AndroidCaptivePortalChecker(applicationContext)
                    )
                )
                val captivePortalResolver = remember { AndroidCaptivePortalResolver(applicationContext) }
                val state by vm.uiState.collectAsState()
                var hasPermissions by remember { mutableStateOf(hasRequiredPermissions()) }

                val permissionsLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    hasPermissions = hasRequiredPermissions()
                }

                LaunchedEffect(hasPermissions) {
                    if (hasPermissions) {
                        AutoConnectService.start(this@MainActivity)
                        vm.scanOpenNetworks()
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WifiScreen(
                        state = state,
                        hasPermissions = hasPermissions,
                        onRequestPermissions = {
                            permissionsLauncher.launch(requiredPermissions())
                        },
                        onScan = vm::scanOpenNetworks,
                        onConnect = vm::connectToNetwork,
                        onStartAutoConnect = { AutoConnectService.start(this@MainActivity) },
                        onStopAutoConnect = { AutoConnectService.stop(this@MainActivity) },
                        onResolvePortal = captivePortalResolver::resolve,
                        modifier = Modifier.padding(innerPadding)
                    )
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Open Wi-Fi Scanner", style = MaterialTheme.typography.headlineSmall)

        if (!hasPermissions) {
            Text("Grant Wi-Fi and location permissions to scan nearby networks.")
            Button(onClick = onRequestPermissions) {
                Text("Grant Permissions")
            }
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    })
                }
            ) {
                Text("Open App Settings")
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onScan, enabled = !state.isScanning) {
                Text("Scan Open Networks")
            }
            if (state.isScanning) {
                CircularProgressIndicator()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartAutoConnect, enabled = !state.autoConnectRunning) {
                Text("Start Background Auto-Connect")
            }
            Button(onClick = onStopAutoConnect, enabled = state.autoConnectRunning) {
                Text("Stop")
            }
        }

        state.autoConnectMessage?.let {
            Text("Auto-connect: $it")
        }

        state.statusMessage?.let { Text(it) }

        if (state.needsPortalLogin) {
            Button(onClick = onResolvePortal) {
                Text("Resolve Captive Portal")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.networks) { network ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.isConnecting) { onConnect(network.ssid) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = network.ssid, style = MaterialTheme.typography.titleMedium)
                        Text(text = "Signal: ${network.level} dBm")
                        Text(text = "Tap to connect")
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
                networks = listOf(WifiNetwork("Cafe Open Wi-Fi", "[ESS]", -56)),
                statusMessage = "Connected to Cafe Open Wi-Fi, but sign-in/captcha is required.",
                needsPortalLogin = true
            ),
            hasPermissions = true,
            onRequestPermissions = {},
            onScan = {},
            onConnect = {},
            onStartAutoConnect = {},
            onStopAutoConnect = {},
            onResolvePortal = {}
        )
    }
}