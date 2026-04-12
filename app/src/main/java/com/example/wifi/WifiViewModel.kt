package com.example.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WifiUiState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val connectedSsid: String? = null,
    val needsPortalLogin: Boolean = false,
    val statusMessage: String? = null,
    val autoConnectRunning: Boolean = false,
    val autoConnectMessage: String? = null
)

class WifiViewModel(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val autoConnectStateSource: AutoConnectStateSource = AutoConnectRuntime
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiUiState())
    val uiState: StateFlow<WifiUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            autoConnectStateSource.state.collect { autoState ->
                _uiState.update {
                    it.copy(
                        autoConnectRunning = autoState.isRunning,
                        autoConnectMessage = autoState.message,
                        needsPortalLogin = it.needsPortalLogin || autoState.captivePortalDetected
                    )
                }
            }
        }
    }

    fun scanOpenNetworks() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isScanning = true, statusMessage = null)
            }

            val result = scanner.scanOpenNetworks()
            result
                .onSuccess { networks ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            networks = networks,
                            statusMessage = if (networks.isEmpty()) "No open networks found." else null
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            statusMessage = error.message ?: "Scan failed."
                        )
                    }
                }
        }
    }

    fun connectToNetwork(ssid: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isConnecting = true, connectedSsid = null, needsPortalLogin = false, statusMessage = null)
            }

            when (val result = connector.connectToOpenNetwork(ssid)) {
                ConnectAttemptResult.Connected -> {
                    when (captivePortalChecker.getStatus()) {
                        CaptivePortalStatus.CAPTIVE_PORTAL -> {
                            _uiState.update {
                                it.copy(
                                    isConnecting = false,
                                    connectedSsid = ssid,
                                    needsPortalLogin = true,
                                    statusMessage = "Connected to $ssid, but sign-in/captcha is required."
                                )
                            }
                        }

                        CaptivePortalStatus.OPEN_INTERNET -> {
                            _uiState.update {
                                it.copy(
                                    isConnecting = false,
                                    connectedSsid = ssid,
                                    needsPortalLogin = false,
                                    statusMessage = "Connected to $ssid with internet access."
                                )
                            }
                        }

                        CaptivePortalStatus.UNKNOWN -> {
                            _uiState.update {
                                it.copy(
                                    isConnecting = false,
                                    connectedSsid = ssid,
                                    needsPortalLogin = false,
                                    statusMessage = "Connected to $ssid. Internet status is unknown."
                                )
                            }
                        }
                    }
                }

                is ConnectAttemptResult.Failed -> {
                    _uiState.update {
                        it.copy(isConnecting = false, statusMessage = result.reason)
                    }
                }

                is ConnectAttemptResult.Unsupported -> {
                    _uiState.update {
                        it.copy(isConnecting = false, statusMessage = result.reason)
                    }
                }
            }
        }
    }
}

class WifiViewModelFactory(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val autoConnectStateSource: AutoConnectStateSource = AutoConnectRuntime
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WifiViewModel(scanner, connector, captivePortalChecker, autoConnectStateSource) as T
    }
}

