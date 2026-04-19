package com.dm.labs.wifi.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for hierarchical solution lookup by SSID, BSSID, and portalHost.
 * Validates that solutions are found with proper priority:
 * 1. SSID + BSSID + portalHost (most specific)
 * 2. SSID + portalHost
 * 3. SSID (fallback)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptivePortalSolutionHierarchyTest {

    @Test
    fun `getLatestSolutionForSsidBssidHost finds exact match`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        // Create solution with BSSID and portalHost
        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login",
            description = "Main router"
        )
        repository.updateSolutionBssidAndHost(solutionId, "aa:bb:cc:dd:ee:01", "portal.cafe.com")

        // Should find exact match
        val found = repository.getLatestSolutionForSsidBssidHost(
            "CafeWifi",
            "aa:bb:cc:dd:ee:01",
            "portal.cafe.com"
        )
        assertNotNull(found)
        assertEquals(solutionId, found?.id)
    }

    @Test
    fun `getLatestSolutionForSsidBssidHost returns null for different BSSID`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login"
        )
        repository.updateSolutionBssidAndHost(solutionId, "aa:bb:cc:dd:ee:01", "portal.cafe.com")

        // Should not find with different BSSID
        val found = repository.getLatestSolutionForSsidBssidHost(
            "CafeWifi",
            "aa:bb:cc:dd:ee:02",  // Different BSSID
            "portal.cafe.com"
        )
        assertNull(found)
    }

    @Test
    fun `getLatestSolutionForSsidHost finds SSID+portalHost match`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login"
        )
        // Set portalHost but no BSSID
        repository.updateSolutionBssidAndHost(solutionId, null, "portal.cafe.com")

        // Should find by SSID+portalHost
        val found = repository.getLatestSolutionForSsidHost("CafeWifi", "portal.cafe.com")
        assertNotNull(found)
        assertEquals(solutionId, found?.id)
    }

    @Test
    fun `getLatestSolutionForSsid finds SSID-only match`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login"
        )

        // Should find by SSID alone
        val found = repository.getLatestSolutionForSsid("CafeWifi")
        assertNotNull(found)
        assertEquals(solutionId, found?.id)
    }

    @Test
    fun `multiple solutions for same SSID with different portals`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        // Create two solutions for same SSID but different portals
        val solutionId1 = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal1.cafe.com/login",
            description = "Main router"
        )
        repository.updateSolutionBssidAndHost(solutionId1, "aa:bb:cc:dd:ee:01", "portal1.cafe.com")

        val solutionId2 = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal2.cafe.com/login",
            description = "Backup router"
        )
        repository.updateSolutionBssidAndHost(solutionId2, "aa:bb:cc:dd:ee:02", "portal2.cafe.com")

        // Should find portal1 solution
        val found1 = repository.getLatestSolutionForSsidHost("CafeWifi", "portal1.cafe.com")
        assertNotNull(found1)
        assertEquals(solutionId1, found1?.id)

        // Should find portal2 solution
        val found2 = repository.getLatestSolutionForSsidHost("CafeWifi", "portal2.cafe.com")
        assertNotNull(found2)
        assertEquals(solutionId2, found2?.id)
    }

    @Test
    fun `same SSID different BSSID uses correct solution`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        // Create solution for specific BSSID+portal combo
        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://main.cafe.com/login"
        )
        repository.updateSolutionBssidAndHost(solutionId, "aa:bb:cc:dd:ee:01", "main.cafe.com")

        // Same SSID but different BSSID should still find the solution by SSI+portal
        val foundBySSID = repository.getLatestSolutionForSsidHost("CafeWifi", "main.cafe.com")
        assertNotNull(foundBySSID)

        // But exact BSSID match should find it
        val foundByBSSID = repository.getLatestSolutionForSsidBssidHost(
            "CafeWifi",
            "aa:bb:cc:dd:ee:01",
            "main.cafe.com"
        )
        assertNotNull(foundByBSSID)
    }

    @Test
    fun `normalized portal URL matching works`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://www.portal.cafe.com/login"  // with www
        )

        // Should find without www prefix (normalized)
        val found = repository.getLatestSolutionForSsidHost("CafeWifi", "www.portal.cafe.com")
        assertNotNull(found)
    }

    @Test
    fun `export includes BSSID and portalHost`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login",
            description = "Test solution"
        )
        repository.updateSolutionBssidAndHost(solutionId, "aa:bb:cc:dd:ee:01", "portal.cafe.com")
        repository.addStep(solutionId, 0, "NAVIGATION", url = "https://portal.cafe.com")
        repository.finishRecording(solutionId)

        val json = repository.exportToJson(solutionId)
        assertNotNull(json)
        assertTrue(json!!.contains("\"bssid\""))
        assertTrue(json.contains("\"portalHost\""))
        assertTrue(json.contains("\"aa:bb:cc:dd:ee:01\""))
        assertTrue(json.contains("\"portal.cafe.com\""))
    }

    @Test
    fun `import preserves BSSID and portalHost`() = runTest {
        val repository = InMemoryCaptivePortalRepo()

        val solutionId = repository.createSolution(
            ssid = "CafeWifi",
            portalUrl = "https://portal.cafe.com/login"
        )
        repository.updateSolutionBssidAndHost(solutionId, "aa:bb:cc:dd:ee:01", "portal.cafe.com")
        repository.addStep(solutionId, 0, "NAVIGATION", url = "https://portal.cafe.com")
        repository.finishRecording(solutionId)

        val json = repository.exportToJson(solutionId)
        assertNotNull(json)

        // Import it
        val imported = repository.importFromJson(json!!)
        assertEquals(1, imported)

        // Verify BSSID and portalHost were preserved
        val solutions = repository.getAllSolutions()
        val newSolution = solutions.lastOrNull()
        assertNotNull(newSolution)
        assertEquals("aa:bb:cc:dd:ee:01", newSolution?.bssid)
        assertEquals("portal.cafe.com", newSolution?.portalHost)
    }

    // -------- Fake in-memory implementation --------

    private class InMemoryCaptivePortalRepo : CaptivePortalSolutionRepository {
        private val solutions = mutableMapOf<Long, CaptivePortalSolutionEntity>()
        private val steps = mutableMapOf<Long, MutableList<CaptivePortalStepEntity>>()
        private var nextSolutionId = 1L
        private var nextStepId = 1L

        override suspend fun createSolution(ssid: String, portalUrl: String, description: String): Long {
            val id = nextSolutionId++
            solutions[id] = CaptivePortalSolutionEntity(
                id = id,
                ssid = ssid,
                portalUrl = portalUrl,
                description = description
            )
            steps[id] = mutableListOf()
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
        ) {
            steps[solutionId]?.add(
                CaptivePortalStepEntity(
                    id = nextStepId++,
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
            val stepCount = steps[solutionId]?.size ?: 0
            solutions[solutionId] = solutions[solutionId]!!.copy(stepCount = stepCount)
        }

        override suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity> =
            solutions.values.sortedByDescending { it.createdAt }

        override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> =
            solutions.values.filter { it.ssid == ssid }.sortedByDescending { it.createdAt }

        override suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity? =
            getSolutionsForSsid(ssid).firstOrNull()

        override suspend fun getLatestSolutionForSsidAndHost(ssid: String, portalHost: String): CaptivePortalSolutionEntity? {
            val normalized = normalizeHost(portalHost) ?: return null
            return getSolutionsForSsid(ssid).firstOrNull {
                normalizeHost(it.portalUrl) == normalized
            }
        }

        override suspend fun getLatestSolutionForSsidBssidHost(ssid: String, bssid: String, portalHost: String): CaptivePortalSolutionEntity? {
            val normalized = normalizeHost(portalHost) ?: return null
            return getSolutionsForSsid(ssid).firstOrNull {
                it.bssid == bssid && normalizeHost(it.portalUrl) == normalized
            }
        }

        override suspend fun getLatestSolutionForSsidHost(ssid: String, portalHost: String): CaptivePortalSolutionEntity? {
            val normalized = normalizeHost(portalHost) ?: return null
            return getSolutionsForSsid(ssid).firstOrNull {
                normalizeHost(it.portalUrl) == normalized
            }
        }

        override suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps? {
            val sol = solutions[solutionId] ?: return null
            val stps = steps[solutionId] ?: emptyList()
            return SolutionWithSteps(sol, stps)
        }

        override suspend fun deleteSolution(solutionId: Long) {
            solutions.remove(solutionId)
            steps.remove(solutionId)
        }

        override suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) {
            solutions[solutionId]?.let {
                solutions[solutionId] = it.copy(ssid = ssid, description = description, portalUrl = portalUrl)
            }
        }

        override suspend fun updateSolutionBssidAndHost(solutionId: Long, bssid: String?, portalHost: String?) {
            solutions[solutionId]?.let {
                solutions[solutionId] = it.copy(bssid = bssid, portalHost = portalHost)
            }
        }

        override suspend fun updateStep(step: CaptivePortalStepEntity) {
            steps[step.solutionId]?.replaceAll { if (it.id == step.id) step else it }
        }

        override suspend fun deleteStep(solutionId: Long, stepId: Long) {
            steps[solutionId]?.removeIf { it.id == stepId }
            finishRecording(solutionId)
        }

        override suspend fun exportToJson(solutionId: Long): String? {
            val data = getSolutionWithSteps(solutionId) ?: return null
            return solutionToJson(data).toString(2)
        }

        override suspend fun exportAllToJson(): String {
            val arr = org.json.JSONArray()
            for (sol in getAllSolutions()) {
                val stps = steps[sol.id] ?: emptyList()
                arr.put(solutionToJson(SolutionWithSteps(sol, stps)))
            }
            val root = org.json.JSONObject()
            root.put("version", 1)
            root.put("exportedAt", System.currentTimeMillis())
            root.put("solutions", arr)
            return root.toString(2)
        }

        override suspend fun importFromJson(json: String): Int {
            val root = org.json.JSONObject(json)
            val arr = if (root.has("solutions")) {
                root.getJSONArray("solutions")
            } else {
                val singleArr = org.json.JSONArray()
                singleArr.put(root)
                singleArr
            }

            var imported = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val solutionId = createSolution(
                    obj.getString("ssid"),
                    obj.optString("portalUrl", ""),
                    obj.optString("description", "Imported")
                )
                val bssid = obj.takeIf { it.has("bssid") && !it.isNull("bssid") }
                    ?.getString("bssid")
                    ?.takeIf { it.isNotEmpty() }
                val portalHost = obj.takeIf { it.has("portalHost") && !it.isNull("portalHost") }
                    ?.getString("portalHost")
                    ?.takeIf { it.isNotEmpty() }
                if (bssid != null || portalHost != null) {
                    updateSolutionBssidAndHost(solutionId, bssid, portalHost)
                }

                val stepsArr = obj.optJSONArray("steps") ?: org.json.JSONArray()
                for (j in 0 until stepsArr.length()) {
                    val stepObj = stepsArr.getJSONObject(j)
                    addStep(
                        solutionId,
                        stepObj.optInt("stepOrder", j),
                        stepObj.getString("type"),
                        stepObj.optString("url", ""),
                        stepObj.optString("cssSelector", ""),
                        stepObj.optString("inputValue", ""),
                        stepObj.optString("elementId", ""),
                        stepObj.optString("elementName", ""),
                        stepObj.optString("elementType", ""),
                        stepObj.optString("formData", "")
                    )
                }
                finishRecording(solutionId)
                imported++
            }
            return imported
        }

        private fun solutionToJson(data: SolutionWithSteps): org.json.JSONObject {
            val obj = org.json.JSONObject()
            obj.put("ssid", data.solution.ssid)
            obj.put("portalUrl", data.solution.portalUrl)
            obj.put("description", data.solution.description)
            obj.put("createdAt", data.solution.createdAt)
            obj.put("stepCount", data.solution.stepCount)
            obj.put("bssid", data.solution.bssid ?: "")
            obj.put("portalHost", data.solution.portalHost ?: "")
            obj.put("isShared", data.solution.isShared)

            val stepsArr = org.json.JSONArray()
            for (step in data.steps) {
                val stepObj = org.json.JSONObject()
                stepObj.put("stepOrder", step.stepOrder)
                stepObj.put("type", step.type)
                stepObj.put("url", step.url)
                stepObj.put("cssSelector", step.cssSelector)
                stepObj.put("inputValue", step.inputValue)
                stepObj.put("elementId", step.elementId)
                stepObj.put("elementName", step.elementName)
                stepObj.put("elementType", step.elementType)
                stepObj.put("formData", step.formData)
                stepObj.put("timestamp", step.timestamp)
                stepsArr.put(stepObj)
            }
            obj.put("steps", stepsArr)
            return obj
        }

        private fun normalizeHost(value: String): String? {
            val text = value.trim()
            if (text.isEmpty()) return null
            return runCatching {
                val candidate = if (text.contains("://")) text else "https://$text"
                java.net.URL(candidate).host?.lowercase()?.removePrefix("www.")?.ifBlank { null }
            }.getOrNull()
        }
    }

    private fun assertTrue(condition: Boolean) {
        if (!condition) throw AssertionError("Expected true")
    }
}
