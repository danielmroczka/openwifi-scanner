package com.dm.labs.wifi.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCaptivePortalSolutionRepositoryTest {

    @Test
    fun `finishRecording updates step count from stored steps`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val solutionId = repository.createSolution("Cafe", "https://portal.example.com")
        repository.addStep(solutionId, 0, "NAVIGATION", url = "https://portal.example.com")
        repository.addStep(solutionId, 1, "CLICK", cssSelector = "button.accept")

        repository.finishRecording(solutionId)

        assertEquals(2, dao.getSolution(solutionId)?.stepCount)
    }

    @Test
    fun `latest solution for ssid and host normalizes input urls`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        repository.createSolution("Airport", "https://www.portal.example.com/login")
        repository.createSolution("Airport", "https://other.example.com")

        val match = repository.getLatestSolutionForSsidAndHost("Airport", "portal.example.com")

        assertNotNull(match)
        assertEquals("https://www.portal.example.com/login", match?.portalUrl)
    }

    @Test
    fun `latest solution for ssid and host returns null for blank or unmatched host`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        repository.createSolution("Airport", "https://portal.example.com/login")

        assertNull(repository.getLatestSolutionForSsidAndHost("Airport", "   "))
        assertNull(repository.getLatestSolutionForSsidAndHost("Airport", "different.example.com"))
    }

    @Test
    fun `getSolutionWithSteps returns full detail when present`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val solutionId = repository.createSolution("Library", "https://portal.example.com")
        repository.addStep(solutionId, 0, "NAVIGATION", url = "https://portal.example.com")

        val detail = repository.getSolutionWithSteps(solutionId)

        assertNotNull(detail)
        assertEquals("Library", detail?.solution?.ssid)
        assertEquals(1, detail?.steps?.size)
    }

    @Test
    fun `deleteStep recalculates remaining step count`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val solutionId = repository.createSolution("Mall", "https://portal.example.com")
        repository.addStep(solutionId, 0, "NAVIGATION")
        repository.addStep(solutionId, 1, "CLICK")
        repository.finishRecording(solutionId)
        val stepToDelete = dao.getStepsForSolution(solutionId).last()

        repository.deleteStep(solutionId, stepToDelete.id)

        assertEquals(1, dao.getStepsForSolution(solutionId).size)
        assertEquals(1, dao.getSolution(solutionId)?.stepCount)
    }

    @Test
    fun `exportToJson returns null for missing solution`() = runTest {
        val repository = RoomCaptivePortalSolutionRepository(FakeCaptivePortalSolutionDao())

        assertNull(repository.exportToJson(999L))
    }

    @Test
    fun `exportToJson and exportAllToJson include solution metadata and steps`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val firstId = repository.createSolution("Cafe", "https://portal.example.com", "Guest portal")
        repository.addStep(firstId, 0, "NAVIGATION", url = "https://portal.example.com")
        repository.finishRecording(firstId)
        repository.createSolution("Hotel", "https://hotel.example.com", "Backup")

        val single = JSONObject(repository.exportToJson(firstId)!!)
        val all = JSONObject(repository.exportAllToJson())

        assertEquals("Cafe", single.getString("ssid"))
        assertEquals(1, single.getJSONArray("steps").length())
        assertEquals(2, all.getJSONArray("solutions").length())
    }

    @Test
    fun `importFromJson supports single-solution payload`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val json = """
            {
              "ssid": "Campus",
              "portalUrl": "https://portal.example.com",
              "description": "Imported solution",
              "steps": [
                {
                  "stepOrder": 0,
                  "type": "NAVIGATION",
                  "url": "https://portal.example.com"
                },
                {
                  "stepOrder": 1,
                  "type": "CLICK",
                  "cssSelector": "button.continue"
                }
              ]
            }
        """.trimIndent()

        val imported = repository.importFromJson(json)
        val solutions = dao.getAllSolutions()

        assertEquals(1, imported)
        assertEquals(1, solutions.size)
        assertEquals(2, solutions.first().stepCount)
        assertEquals(2, dao.getStepsForSolution(solutions.first().id).size)
    }

    @Test
    fun `importFromJson supports multi-solution payload`() = runTest {
        val dao = FakeCaptivePortalSolutionDao()
        val repository = RoomCaptivePortalSolutionRepository(dao)
        val json = """
            {
              "solutions": [
                {
                  "ssid": "Cafe",
                  "portalUrl": "https://cafe.example.com",
                  "description": "One",
                  "steps": []
                },
                {
                  "ssid": "Airport",
                  "portalUrl": "https://airport.example.com",
                  "description": "Two",
                  "steps": [
                    {
                      "type": "INPUT",
                      "inputValue": "email@example.com"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val imported = repository.importFromJson(json)
        val solutions = dao.getAllSolutions()

        assertEquals(2, imported)
        assertEquals(2, solutions.size)
        assertTrue(solutions.any { it.ssid == "Cafe" && it.stepCount == 0 })
        assertTrue(solutions.any { it.ssid == "Airport" && it.stepCount == 1 })
    }

    private class FakeCaptivePortalSolutionDao : CaptivePortalSolutionDao {
        private var nextSolutionId = 1L
        private var nextStepId = 1L
        private val solutions = linkedMapOf<Long, CaptivePortalSolutionEntity>()
        private val steps = linkedMapOf<Long, CaptivePortalStepEntity>()

        override suspend fun insertSolution(solution: CaptivePortalSolutionEntity): Long {
            val id = if (solution.id == 0L) nextSolutionId++ else solution.id
            solutions[id] = solution.copy(id = id)
            return id
        }

        override suspend fun insertStep(step: CaptivePortalStepEntity): Long {
            val id = if (step.id == 0L) nextStepId++ else step.id
            steps[id] = step.copy(id = id)
            return id
        }

        override suspend fun insertSteps(steps: List<CaptivePortalStepEntity>) {
            steps.forEach { insertStep(it) }
        }

        override suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity> =
            solutions.values.sortedByDescending { it.createdAt }

        override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> =
            solutions.values.filter { it.ssid == ssid }.sortedByDescending { it.createdAt }

        override suspend fun getSolution(solutionId: Long): CaptivePortalSolutionEntity? = solutions[solutionId]

        override suspend fun getStepsForSolution(solutionId: Long): List<CaptivePortalStepEntity> =
            steps.values.filter { it.solutionId == solutionId }.sortedBy { it.stepOrder }

        override suspend fun updateStepCount(solutionId: Long, count: Int) {
            val current = solutions[solutionId] ?: return
            solutions[solutionId] = current.copy(stepCount = count)
        }

        override suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) {
            val current = solutions[solutionId] ?: return
            solutions[solutionId] = current.copy(ssid = ssid, description = description, portalUrl = portalUrl)
        }

        override suspend fun updateStep(step: CaptivePortalStepEntity) {
            steps[step.id] = step
        }

        override suspend fun deleteStep(stepId: Long) {
            steps.remove(stepId)
        }

        override suspend fun deleteSolution(solutionId: Long) {
            solutions.remove(solutionId)
        }

        override suspend fun deleteStepsForSolution(solutionId: Long) {
            val ids = steps.values.filter { it.solutionId == solutionId }.map { it.id }
            ids.forEach(steps::remove)
        }
    }
}

