package com.dm.labs.wifi.captive

import com.dm.labs.wifi.data.CaptivePortalSolutionEntity
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.SolutionWithSteps
import com.dm.labs.wifi.model.CaptivePortalChecker
import com.dm.labs.wifi.model.CaptivePortalStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptivePortalAutoSolverTest {

    @Test
    fun `replays saved solution first when ssid has steps`() = runTest {
        val repo = FakeSolutionRepo(
            solution = CaptivePortalSolutionEntity(id = 7L, ssid = "Cafe", portalUrl = "http://portal")
        )
        var replayCalled = false
        var httpCalled = false
        var interactiveCalled = false

        val solver = CaptivePortalAutoSolver(
            checker = FakeChecker(),
            solutionRepository = repo,
            replayLauncher = { ssid, id ->
                replayCalled = true
                assertEquals("Cafe", ssid)
                assertEquals(7L, id)
            },
            interactiveLauncher = { interactiveCalled = true },
            httpSolver = {
                httpCalled = true
                false
            },
            resolutionWaiter = { true }
        )

        val result = solver.trySolve("Cafe")

        assertTrue(result)
        assertTrue(replayCalled)
        assertFalse(httpCalled)
        assertFalse(interactiveCalled)
    }

    @Test
    fun `falls back to http solve when replay does not validate`() = runTest {
        val repo = FakeSolutionRepo(
            solution = CaptivePortalSolutionEntity(id = 8L, ssid = "MallWiFi", portalUrl = "http://portal")
        )
        var httpCalled = false
        var interactiveCalled = false

        val solver = CaptivePortalAutoSolver(
            checker = FakeChecker(),
            solutionRepository = repo,
            replayLauncher = { _, _ -> },
            interactiveLauncher = { interactiveCalled = true },
            httpSolver = {
                httpCalled = true
                true
            },
            resolutionWaiter = { false }
        )

        val result = solver.trySolve("MallWiFi")

        assertTrue(result)
        assertTrue(httpCalled)
        assertFalse(interactiveCalled)
    }

    @Test
    fun `launches interactive solver when replay and http fail`() = runTest {
        val repo = FakeSolutionRepo(solution = null)
        var interactiveCalled = false

        val solver = CaptivePortalAutoSolver(
            checker = FakeChecker(),
            solutionRepository = repo,
            replayLauncher = { _, _ -> },
            interactiveLauncher = { interactiveCalled = true },
            httpSolver = { false },
            resolutionWaiter = { false }
        )

        val result = solver.trySolve("Airport")

        assertFalse(result)
        assertTrue(interactiveCalled)
    }

    private class FakeChecker : CaptivePortalChecker {
        override fun getStatus(): CaptivePortalStatus = CaptivePortalStatus.UNKNOWN
    }

    private class FakeSolutionRepo(
        private val solution: CaptivePortalSolutionEntity?
    ) : CaptivePortalSolutionRepository {
        override suspend fun createSolution(ssid: String, portalUrl: String, description: String): Long = 1L

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
            solution?.let { listOf(it) } ?: emptyList()

        override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> =
            if (solution?.ssid == ssid) listOf(solution) else emptyList()

        override suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity? =
            if (solution?.ssid == ssid) solution else null

        override suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps? = null

        override suspend fun deleteSolution(solutionId: Long) = Unit

        override suspend fun updateSolutionInfo(
            solutionId: Long,
            ssid: String,
            description: String,
            portalUrl: String
        ) = Unit

        override suspend fun updateStep(step: CaptivePortalStepEntity) = Unit

        override suspend fun deleteStep(solutionId: Long, stepId: Long) = Unit

        override suspend fun exportToJson(solutionId: Long): String? = null

        override suspend fun exportAllToJson(): String = "{}"

        override suspend fun importFromJson(json: String): Int = 0
    }
}

