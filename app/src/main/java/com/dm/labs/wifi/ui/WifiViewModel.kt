package com.dm.labs.wifi.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dm.labs.wifi.approval.NetworkApprovalManager
import com.dm.labs.wifi.approval.PendingNetworkApproval
import com.dm.labs.wifi.approval.UserNetworkDecision
import com.dm.labs.wifi.autoconnect.AutoConnectRuntime
import com.dm.labs.wifi.data.CaptivePortalSolutionEntity
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.SolutionWithSteps
import com.dm.labs.wifi.data.WifiNetworkEntity
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.location.LocationProvider
import com.dm.labs.wifi.log.ScanLogManager
import com.dm.labs.wifi.model.AutoConnectStateSource
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import com.dm.labs.wifi.settings.AppSettings
import com.dm.labs.wifi.settings.AppSettingsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WifiUiState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val networks: List<WifiNetwork> = emptyList(),
    val connectedSsid: String? = null,
    val needsPortalLogin: Boolean = false,
    val statusMessage: String? = null,
    val autoConnectRunning: Boolean = false,
    val autoConnectMessage: String? = null,
    val lastScanTimestamp: Long = 0L,
    val blacklistedBssids: Set<String> = emptySet(),
    val whitelistedBssids: Set<String> = emptySet(),
    val blacklistedNetworks: List<WifiNetworkEntity> = emptyList(),
    val whitelistedNetworks: List<WifiNetworkEntity> = emptyList(),
    val pendingApproval: PendingNetworkApproval? = null,
    val solutions: List<CaptivePortalSolutionEntity> = emptyList(),
    val selectedSolutionDetail: SolutionWithSteps? = null
)

class WifiViewModel(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val autoConnectStateSource: AutoConnectStateSource = AutoConnectRuntime,
    private val repository: WifiNetworkRepository? = null,
    private val solutionRepository: CaptivePortalSolutionRepository? = null,
    private val appContext: Context? = null,
    private val appSettings: AppSettings? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiUiState())
    val uiState: StateFlow<WifiUiState> = _uiState.asStateFlow()

    private var periodicScanJob: Job? = null

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
        viewModelScope.launch {
            NetworkApprovalManager.pending.collect { pending ->
                _uiState.update { it.copy(pendingApproval = pending) }
            }
        }
        refreshLists()
    }

    fun startPeriodicScan() {
        if (periodicScanJob?.isActive == true) return
        periodicScanJob = viewModelScope.launch {
            while (isActive) {
                scanOpenNetworksInternal()
                val intervalMs = appSettings?.state?.value?.scanIntervalMs
                    ?: (AppSettingsState.DEFAULT_SCAN_INTERVAL_SECONDS * 1_000L)
                delay(intervalMs)
            }
        }
    }

    fun stopPeriodicScan() {
        periodicScanJob?.cancel()
        periodicScanJob = null
    }

    fun scanOpenNetworks() {
        viewModelScope.launch {
            scanOpenNetworksInternal()
        }
    }

    private suspend fun scanOpenNetworksInternal() {
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
                        statusMessage = if (networks.isEmpty()) "No open networks found." else null,
                        lastScanTimestamp = System.currentTimeMillis()
                    )
                }
                ScanLogManager.log("Scanned ${networks.size} open networks.")
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        statusMessage = error.message ?: "Scan failed."
                    )
                }
                ScanLogManager.log("Scan failed: ${error.message}")
            }
    }

    fun connectToNetwork(ssid: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectedSsid = null,
                    needsPortalLogin = false,
                    statusMessage = null
                )
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

    fun handleApprovalDecision(decision: UserNetworkDecision) {
        viewModelScope.launch {
            val pending = NetworkApprovalManager.pending.value ?: return@launch
            val repo = repository
            val location = appContext?.let { LocationProvider.getLastKnownLocation(it) }
            when (decision) {
                UserNetworkDecision.WHITELIST -> {
                    repo?.setWhitelisted(
                        pending.bssid,
                        pending.ssid,
                        true,
                        location?.latitude,
                        location?.longitude
                    )
                }

                UserNetworkDecision.BLACKLIST -> {
                    repo?.setBlacklisted(
                        pending.bssid,
                        pending.ssid,
                        true,
                        location?.latitude,
                        location?.longitude
                    )
                }

                UserNetworkDecision.SKIP -> { /* nothing to persist */
                }
            }
            NetworkApprovalManager.submitDecision(decision)
            refreshLists()
        }
    }

    fun toggleBlacklist(bssid: String, ssid: String) {
        viewModelScope.launch {
            val repo = repository ?: return@launch
            val current = repo.isBlacklisted(bssid)
            val location = appContext?.let { LocationProvider.getLastKnownLocation(it) }
            repo.setBlacklisted(bssid, ssid, !current, location?.latitude, location?.longitude)
            refreshLists()
        }
    }

    fun toggleWhitelist(bssid: String, ssid: String) {
        viewModelScope.launch {
            val repo = repository ?: return@launch
            val current = repo.isWhitelisted(bssid)
            val location = appContext?.let { LocationProvider.getLastKnownLocation(it) }
            repo.setWhitelisted(bssid, ssid, !current, location?.latitude, location?.longitude)
            refreshLists()
        }
    }

    fun removeNetwork(bssid: String) {
        viewModelScope.launch {
            val repo = repository ?: return@launch
            repo.deleteNetwork(bssid)
            refreshLists()
        }
    }

    fun refreshLists() {
        viewModelScope.launch {
            val repo = repository ?: return@launch
            val blacklisted = repo.getBlacklistedNetworks()
            val whitelisted = repo.getWhitelistedNetworks()
            _uiState.update {
                it.copy(
                    blacklistedBssids = blacklisted.map { n -> n.bssid }.toSet(),
                    whitelistedBssids = whitelisted.map { n -> n.bssid }.toSet(),
                    blacklistedNetworks = blacklisted,
                    whitelistedNetworks = whitelisted
                )
            }
        }
        refreshSolutions()
    }

    fun refreshSolutions() {
        viewModelScope.launch {
            val solRepo = solutionRepository ?: return@launch
            val solutions = solRepo.getAllSolutions()
            _uiState.update { it.copy(solutions = solutions) }
        }
    }

    fun deleteSolution(solutionId: Long) {
        viewModelScope.launch {
            solutionRepository?.deleteSolution(solutionId)
            _uiState.update { it.copy(selectedSolutionDetail = null) }
            refreshSolutions()
        }
    }

    fun loadSolutionDetail(solutionId: Long) {
        viewModelScope.launch {
            val detail = solutionRepository?.getSolutionWithSteps(solutionId)
            _uiState.update { it.copy(selectedSolutionDetail = detail) }
        }
    }

    fun closeSolutionDetail() {
        _uiState.update { it.copy(selectedSolutionDetail = null) }
        refreshSolutions()
    }

    fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) {
        viewModelScope.launch {
            solutionRepository?.updateSolutionInfo(solutionId, ssid, description, portalUrl)
            loadSolutionDetail(solutionId)
            refreshSolutions()
        }
    }

    fun updateStep(step: CaptivePortalStepEntity) {
        viewModelScope.launch {
            solutionRepository?.updateStep(step)
            loadSolutionDetail(step.solutionId)
        }
    }

    fun deleteStep(solutionId: Long, stepId: Long) {
        viewModelScope.launch {
            solutionRepository?.deleteStep(solutionId, stepId)
            loadSolutionDetail(solutionId)
        }
    }

    suspend fun exportSolutionToJson(solutionId: Long): String? {
        return solutionRepository?.exportToJson(solutionId)
    }

    suspend fun exportAllSolutionsToJson(): String {
        return solutionRepository?.exportAllToJson() ?: "{}"
    }

    suspend fun importSolutionsFromJson(json: String): Int {
        val count = solutionRepository?.importFromJson(json) ?: 0
        refreshSolutions()
        return count
    }
}

class WifiViewModelFactory(
    private val scanner: WifiScanner,
    private val connector: WifiConnector,
    private val captivePortalChecker: CaptivePortalChecker,
    private val autoConnectStateSource: AutoConnectStateSource = AutoConnectRuntime,
    private val repository: WifiNetworkRepository? = null,
    private val solutionRepository: CaptivePortalSolutionRepository? = null,
    private val appContext: Context? = null,
    private val appSettings: AppSettings? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WifiViewModel(
            scanner,
            connector,
            captivePortalChecker,
            autoConnectStateSource,
            repository,
            solutionRepository,
            appContext,
            appSettings
        ) as T
    }
}

