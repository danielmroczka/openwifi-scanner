package com.dm.labs.wifi.ui

import com.dm.labs.wifi.data.CaptivePortalSolutionEntity
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.SolutionWithSteps
import com.dm.labs.wifi.data.WifiNetworkEntity
import com.dm.labs.wifi.data.WifiNetworkRepository
import com.dm.labs.wifi.model.AutoConnectStateSource
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import com.dm.labs.wifi.model.ConnectAttemptResult
import com.dm.labs.wifi.model.WifiConnector
import com.dm.labs.wifi.model.WifiNetwork
import com.dm.labs.wifi.model.WifiScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WifiViewModelRepositoryFlowsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val autoConnectState = MutableStateFlow(BackgroundAutoConnectState())
    private val autoConnectSource = object : AutoConnectStateSource {
        override val state: StateFlow<BackgroundAutoConnectState> = autoConnectState.asStateFlow()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scan groups duplicate ssids and keeps strongest access point`() = runTest(dispatcher) {
        val viewModel = createViewModel(
            scanner = object : WifiScanner {
                override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = Result.success(
                    listOf(
                        WifiNetwork("Cafe", "bssid-weak", "[ESS]", -75),
                        WifiNetwork("Cafe", "bssid-strong", "[ESS]", -41),
                        WifiNetwork("Library", "bssid-library", "[ESS]", -55)
                    )
                )
            }
        )

        viewModel.scanOpenNetworks()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.networks.size)
        assertEquals("bssid-strong", state.networks.first { it.ssid == "Cafe" }.bssid)
        assertEquals(listOf("bssid-weak", "bssid-strong"), state.bssidGroups["Cafe"])
    }

    @Test
    fun `refreshLists publishes repository blacklist and whitelist collections`() = runTest(dispatcher) {
        val wifiRepository = FakeWifiNetworkRepository().apply {
            setWhitelisted("fav-1", "Cafe", true)
            setBlacklisted("block-1", "Airport", true)
        }
        val viewModel = createViewModel(repository = wifiRepository)

        viewModel.refreshLists()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(setOf("fav-1"), state.whitelistedBssids)
        assertEquals(setOf("block-1"), state.blacklistedBssids)
        assertEquals(1, state.whitelistedNetworks.size)
        assertEquals(1, state.blacklistedNetworks.size)
    }

    @Test
    fun `toggle blacklist and whitelist update repository-backed ui state`() = runTest(dispatcher) {
        val wifiRepository = FakeWifiNetworkRepository()
        val viewModel = createViewModel(repository = wifiRepository)

        viewModel.toggleWhitelist("fav-2", "Hotel")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.whitelistedBssids.contains("fav-2"))

        viewModel.toggleBlacklist("fav-2", "Hotel")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.blacklistedBssids.contains("fav-2"))
        assertTrue(viewModel.uiState.value.whitelistedBssids.isEmpty())
    }

    @Test
    fun `load and close solution detail updates selected solution`() = runTest(dispatcher) {
        val solutionRepository = FakeSolutionRepository().apply {
            seedSolution(
                SolutionWithSteps(
                    solution = CaptivePortalSolutionEntity(
                        id = 1L,
                        ssid = "Cafe",
                        portalUrl = "https://portal.example.com",
                        description = "Guest portal",
                        stepCount = 1
                    ),
                    steps = listOf(
                        CaptivePortalStepEntity(
                            id = 10L,
                            solutionId = 1L,
                            stepOrder = 0,
                            type = "NAVIGATION",
                            url = "https://portal.example.com"
                        )
                    )
                )
            )
        }
        val viewModel = createViewModel(solutionRepository = solutionRepository)

        viewModel.loadSolutionDetail(1L)
        advanceUntilIdle()
        assertEquals(1L, viewModel.uiState.value.selectedSolutionDetail?.solution?.id)

        viewModel.closeSolutionDetail()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedSolutionDetail)
    }

    @Test
    fun `load and close network detail updates selected network`() = runTest(dispatcher) {
        val solutionRepository = FakeSolutionRepository().apply {
            seedSolution(
                SolutionWithSteps(
                    solution = CaptivePortalSolutionEntity(
                        id = 2L,
                        ssid = "Library",
                        portalUrl = "https://login.example.com",
                        stepCount = 0
                    ),
                    steps = emptyList()
                )
            )
        }
        val viewModel = createViewModel(solutionRepository = solutionRepository)
        val network = WifiNetworkEntity(bssid = "library-bssid", ssid = "Library", isWhitelisted = true)

        viewModel.loadNetworkDetail(network)
        advanceUntilIdle()
        assertEquals("Library", viewModel.uiState.value.selectedNetworkDetail?.first?.ssid)
        assertEquals(1, viewModel.uiState.value.selectedNetworkDetail?.second?.size)

        viewModel.closeNetworkDetail()
        assertNull(viewModel.uiState.value.selectedNetworkDetail)
    }

    @Test
    fun `delete solution clears detail and refreshes solutions list`() = runTest(dispatcher) {
        val solutionRepository = FakeSolutionRepository().apply {
            seedSolution(
                SolutionWithSteps(
                    solution = CaptivePortalSolutionEntity(
                        id = 3L,
                        ssid = "Campus",
                        portalUrl = "https://campus.example.com",
                        stepCount = 0
                    ),
                    steps = emptyList()
                )
            )
        }
        val viewModel = createViewModel(solutionRepository = solutionRepository)

        viewModel.loadSolutionDetail(3L)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.solutions.size)

        viewModel.deleteSolution(3L)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedSolutionDetail)
        assertTrue(viewModel.uiState.value.solutions.isEmpty())
    }

    @Test
    fun `import and export wrappers delegate to solution repository`() = runTest(dispatcher) {
        val solutionRepository = FakeSolutionRepository()
        val viewModel = createViewModel(solutionRepository = solutionRepository)

        val imported = viewModel.importSolutionsFromJson("{\"solutions\":[]}")
        advanceUntilIdle()
        val exported = viewModel.exportAllSolutionsToJson()

        assertEquals(2, imported)
        assertEquals("{\"exported\":true}", exported)
    }

    @Test
    fun `export all falls back to empty object when no solution repository exists`() = runTest(dispatcher) {
        val viewModel = createViewModel(solutionRepository = null)

        val exported = viewModel.exportAllSolutionsToJson()

        assertEquals("{}", exported)
    }

    private fun createViewModel(
        scanner: WifiScanner = object : WifiScanner {
            override suspend fun scanOpenNetworks(): Result<List<WifiNetwork>> = Result.success(emptyList())
        },
        repository: WifiNetworkRepository? = null,
        solutionRepository: CaptivePortalSolutionRepository? = null
    ): WifiViewModel = WifiViewModel(
        scanner = scanner,
        connector = object : WifiConnector {
            override suspend fun connectToOpenNetwork(ssid: String): ConnectAttemptResult = ConnectAttemptResult.Connected
            override fun disconnectCurrentNetwork() = Unit
        },
        captivePortalChecker = object : CaptivePortalChecker {
            override fun getStatus(): CaptivePortalStatus = CaptivePortalStatus.OPEN_INTERNET
        },
        autoConnectStateSource = autoConnectSource,
        repository = repository,
        solutionRepository = solutionRepository
    )

    private class FakeWifiNetworkRepository : WifiNetworkRepository {
        private val networks = linkedMapOf<String, WifiNetworkEntity>()

        override suspend fun getNetwork(bssid: String): WifiNetworkEntity? = networks[bssid]

        override suspend fun getBlacklistedNetworks(): List<WifiNetworkEntity> =
            networks.values.filter { it.isBlacklisted }

        override suspend fun getWhitelistedNetworks(): List<WifiNetworkEntity> =
            networks.values.filter { it.isWhitelisted }

        override suspend fun getAllNetworks(): List<WifiNetworkEntity> = networks.values.toList()

        override suspend fun recordNetwork(bssid: String, ssid: String, latitude: Double?, longitude: Double?) {
            networks[bssid] = (networks[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)).copy(
                ssid = ssid,
                latitude = latitude,
                longitude = longitude
            )
        }

        override suspend fun setBlacklisted(
            bssid: String,
            ssid: String,
            blacklisted: Boolean,
            latitude: Double?,
            longitude: Double?
        ) {
            val current = networks[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)
            networks[bssid] = current.copy(
                ssid = ssid,
                isBlacklisted = blacklisted,
                isWhitelisted = if (blacklisted) false else current.isWhitelisted,
                latitude = latitude ?: current.latitude,
                longitude = longitude ?: current.longitude
            )
        }

        override suspend fun setWhitelisted(
            bssid: String,
            ssid: String,
            whitelisted: Boolean,
            latitude: Double?,
            longitude: Double?
        ) {
            val current = networks[bssid] ?: WifiNetworkEntity(bssid = bssid, ssid = ssid)
            networks[bssid] = current.copy(
                ssid = ssid,
                isWhitelisted = whitelisted,
                isBlacklisted = if (whitelisted) false else current.isBlacklisted,
                latitude = latitude ?: current.latitude,
                longitude = longitude ?: current.longitude
            )
        }

        override suspend fun deleteNetwork(bssid: String) {
            networks.remove(bssid)
        }

        override suspend fun isBlacklisted(bssid: String): Boolean = networks[bssid]?.isBlacklisted == true

        override suspend fun isWhitelisted(bssid: String): Boolean = networks[bssid]?.isWhitelisted == true

        override suspend fun updateLocation(bssid: String, latitude: Double, longitude: Double) {
            val current = networks[bssid] ?: return
            networks[bssid] = current.copy(latitude = latitude, longitude = longitude)
        }
    }

    private class FakeSolutionRepository : CaptivePortalSolutionRepository {
        private val details = linkedMapOf<Long, SolutionWithSteps>()
        private var nextId = 100L
        var lastImportPayload: String? = null

        fun seedSolution(detail: SolutionWithSteps) {
            details[detail.solution.id] = detail
        }

        override suspend fun createSolution(ssid: String, portalUrl: String, description: String): Long {
            val id = nextId++
            details[id] = SolutionWithSteps(
                solution = CaptivePortalSolutionEntity(id = id, ssid = ssid, portalUrl = portalUrl, description = description),
                steps = emptyList()
            )
            return id
        }

        override suspend fun addStep(
            solutionId: Long,
            stepOrder: Int,
            type: String,
            url: String,
            cssSelector: String,
            inputValue: String,
            elementId: String,
            elementName: String,
            elementType: String,
            formData: String
        ) = Unit

        override suspend fun finishRecording(solutionId: Long) = Unit

        override suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity> =
            details.values.map { detail -> detail.solution.copy(stepCount = detail.steps.size) }

        override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> =
            details.values.map { it.solution.copy(stepCount = it.steps.size) }.filter { it.ssid == ssid }

        override suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity? =
            getSolutionsForSsid(ssid).firstOrNull()

        override suspend fun getLatestSolutionForSsidAndHost(ssid: String, portalHost: String): CaptivePortalSolutionEntity? =
            getSolutionsForSsid(ssid).firstOrNull()

        override suspend fun getLatestSolutionForSsidBssidHost(
            ssid: String,
            bssid: String,
            portalHost: String
        ): CaptivePortalSolutionEntity? = getSolutionsForSsid(ssid).firstOrNull()

        override suspend fun getLatestSolutionForSsidHost(
            ssid: String,
            portalHost: String
        ): CaptivePortalSolutionEntity? = getSolutionsForSsid(ssid).firstOrNull()

        override suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps? = details[solutionId]

        override suspend fun deleteSolution(solutionId: Long) {
            details.remove(solutionId)
        }

        override suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) {
            val detail = details[solutionId] ?: return
            details[solutionId] = detail.copy(
                solution = detail.solution.copy(ssid = ssid, description = description, portalUrl = portalUrl)
            )
        }

        override suspend fun updateSolutionBssidAndHost(solutionId: Long, bssid: String?, portalHost: String?) {
            val detail = details[solutionId] ?: return
            details[solutionId] = detail.copy(
                solution = detail.solution.copy(bssid = bssid, portalHost = portalHost)
            )
        }

        override suspend fun updateStep(step: CaptivePortalStepEntity) = Unit

        override suspend fun deleteStep(solutionId: Long, stepId: Long) = Unit

        override suspend fun exportToJson(solutionId: Long): String? = if (details.containsKey(solutionId)) "{\"solutionId\":$solutionId}" else null

        override suspend fun exportAllToJson(): String = "{\"exported\":true}"

        override suspend fun importFromJson(json: String): Int {
            lastImportPayload = json
            seedSolution(
                SolutionWithSteps(
                    solution = CaptivePortalSolutionEntity(
                        id = 999L,
                        ssid = "Imported",
                        portalUrl = "https://imported.example.com",
                        description = "Imported",
                        stepCount = 0
                    ),
                    steps = emptyList()
                )
            )
            seedSolution(
                SolutionWithSteps(
                    solution = CaptivePortalSolutionEntity(
                        id = 1000L,
                        ssid = "Imported 2",
                        portalUrl = "https://imported2.example.com",
                        description = "Imported",
                        stepCount = 0
                    ),
                    steps = emptyList()
                )
            )
            return 2
        }
    }
}

