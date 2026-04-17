package com.dm.labs.wifi.captive

import com.dm.labs.wifi.data.CaptivePortalSolutionEntity
import com.dm.labs.wifi.data.CaptivePortalSolutionRepository
import com.dm.labs.wifi.data.CaptivePortalStepEntity
import com.dm.labs.wifi.data.SolutionWithSteps
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptivePortalRecorderTest {

    @Test
    fun `buffers steps before solution id then flushes in recorded order`() = runTest {
        val repo = FakeRecorderRepo(createDelayMs = 100)
        val recorder = CaptivePortalRecorder(repo, this)

        recorder.startRecording("Cafe", CaptivePortalSolverActivity.PORTAL_CHECK_URL)
        recorder.onClick("#accept", "http://portal", "accept", "accept", "button")
        recorder.onFormSubmit("http://portal/submit", "{}", "form#login")

        advanceUntilIdle()
        recorder.stopRecording()
        advanceUntilIdle()

        assertTrue(repo.finishCalled)
        assertEquals(2, repo.steps.size)
        assertEquals(0, repo.steps[0].stepOrder)
        assertEquals("CLICK", repo.steps[0].type)
        assertEquals(1, repo.steps[1].stepOrder)
        assertEquals("FORM_SUBMIT", repo.steps[1].type)
    }

    private class FakeRecorderRepo(
        private val createDelayMs: Long = 0
    ) : CaptivePortalSolutionRepository {
        val steps = mutableListOf<CaptivePortalStepEntity>()
        var finishCalled = false

        override suspend fun createSolution(ssid: String, portalUrl: String, description: String): Long {
            if (createDelayMs > 0) {
                delay(createDelayMs)
            }
            return 42L
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
        ) {
            steps.add(
                CaptivePortalStepEntity(
                    solutionId = solutionId,
                    stepOrder = stepOrder,
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
        }

        override suspend fun finishRecording(solutionId: Long) {
            finishCalled = true
        }

        override suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity> = emptyList()

        override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> = emptyList()

        override suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity? = null

        override suspend fun getLatestSolutionForSsidAndHost(
            ssid: String,
            portalHost: String
        ): CaptivePortalSolutionEntity? = null

        override suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps? = null

        override suspend fun deleteSolution(solutionId: Long) = Unit

        override suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) = Unit

        override suspend fun updateStep(step: CaptivePortalStepEntity) = Unit

        override suspend fun deleteStep(solutionId: Long, stepId: Long) = Unit

        override suspend fun exportToJson(solutionId: Long): String? = null

        override suspend fun exportAllToJson(): String = "{}"

        override suspend fun importFromJson(json: String): Int = 0
    }
}

