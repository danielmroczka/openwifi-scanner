package com.dm.labs.wifi.data

import org.json.JSONArray
import org.json.JSONObject

data class SolutionWithSteps(
    val solution: CaptivePortalSolutionEntity,
    val steps: List<CaptivePortalStepEntity>
)

interface CaptivePortalSolutionRepository {
    suspend fun createSolution(ssid: String, portalUrl: String, description: String = ""): Long
    suspend fun addStep(
        solutionId: Long,
        stepOrder: Int,
        type: String,
        url: String = "",
        cssSelector: String = "",
        inputValue: String = "",
        elementId: String = "",
        elementName: String = "",
        elementType: String = "",
        formData: String = ""
    )
    suspend fun finishRecording(solutionId: Long)
    suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity>
    suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity>
    suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity?
    suspend fun getLatestSolutionForSsidAndHost(ssid: String, portalHost: String): CaptivePortalSolutionEntity?
    suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps?
    suspend fun deleteSolution(solutionId: Long)
    suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String)
    suspend fun updateStep(step: CaptivePortalStepEntity)
    suspend fun deleteStep(solutionId: Long, stepId: Long)
    suspend fun exportToJson(solutionId: Long): String?
    suspend fun exportAllToJson(): String
    suspend fun importFromJson(json: String): Int
}

class RoomCaptivePortalSolutionRepository(
    private val dao: CaptivePortalSolutionDao
) : CaptivePortalSolutionRepository {

    override suspend fun createSolution(ssid: String, portalUrl: String, description: String): Long {
        return dao.insertSolution(
            CaptivePortalSolutionEntity(
                ssid = ssid,
                portalUrl = portalUrl,
                description = description
            )
        )
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
        dao.insertStep(
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
        val steps = dao.getStepsForSolution(solutionId)
        dao.updateStepCount(solutionId, steps.size)
    }

    override suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity> {
        return dao.getAllSolutions()
    }

    override suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity> {
        return dao.getSolutionsForSsid(ssid)
    }

    override suspend fun getLatestSolutionForSsid(ssid: String): CaptivePortalSolutionEntity? {
        return dao.getSolutionsForSsid(ssid).firstOrNull()
    }

    override suspend fun getLatestSolutionForSsidAndHost(
        ssid: String,
        portalHost: String
    ): CaptivePortalSolutionEntity? {
        val normalizedHost = normalizeHost(portalHost) ?: return null
        return dao.getSolutionsForSsid(ssid).firstOrNull { solution ->
            normalizeHost(solution.portalUrl) == normalizedHost
        }
    }

    override suspend fun getSolutionWithSteps(solutionId: Long): SolutionWithSteps? {
        val solution = dao.getSolution(solutionId) ?: return null
        val steps = dao.getStepsForSolution(solutionId)
        return SolutionWithSteps(solution, steps)
    }

    override suspend fun deleteSolution(solutionId: Long) {
        dao.deleteSolutionWithSteps(solutionId)
    }

    override suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String) {
        dao.updateSolutionInfo(solutionId, ssid, description, portalUrl)
    }

    override suspend fun updateStep(step: CaptivePortalStepEntity) {
        dao.updateStep(step)
    }

    override suspend fun deleteStep(solutionId: Long, stepId: Long) {
        dao.deleteStep(stepId)
        // Recalculate step count
        val remaining = dao.getStepsForSolution(solutionId)
        dao.updateStepCount(solutionId, remaining.size)
    }

    override suspend fun exportToJson(solutionId: Long): String? {
        val data = getSolutionWithSteps(solutionId) ?: return null
        return solutionToJson(data).toString(2)
    }

    override suspend fun exportAllToJson(): String {
        val solutions = dao.getAllSolutions()
        val arr = JSONArray()
        for (sol in solutions) {
            val steps = dao.getStepsForSolution(sol.id)
            arr.put(solutionToJson(SolutionWithSteps(sol, steps)))
        }
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("solutions", arr)
        return root.toString(2)
    }

    override suspend fun importFromJson(json: String): Int {
        val root = JSONObject(json)
        val arr = if (root.has("solutions")) {
            root.getJSONArray("solutions")
        } else {
            // Single solution
            val singleArr = JSONArray()
            singleArr.put(root)
            singleArr
        }

        var imported = 0
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val ssid = obj.getString("ssid")
            val portalUrl = obj.optString("portalUrl", "")
            val description = obj.optString("description", "Imported solution")

            val solutionId = dao.insertSolution(
                CaptivePortalSolutionEntity(
                    ssid = ssid,
                    portalUrl = portalUrl,
                    description = description
                )
            )

            val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
            val steps = mutableListOf<CaptivePortalStepEntity>()
            for (j in 0 until stepsArr.length()) {
                val stepObj = stepsArr.getJSONObject(j)
                steps.add(
                    CaptivePortalStepEntity(
                        solutionId = solutionId,
                        stepOrder = stepObj.optInt("stepOrder", j),
                        type = stepObj.getString("type"),
                        url = stepObj.optString("url", ""),
                        cssSelector = stepObj.optString("cssSelector", ""),
                        inputValue = stepObj.optString("inputValue", ""),
                        elementId = stepObj.optString("elementId", ""),
                        elementName = stepObj.optString("elementName", ""),
                        elementType = stepObj.optString("elementType", ""),
                        formData = stepObj.optString("formData", "")
                    )
                )
            }
            if (steps.isNotEmpty()) {
                dao.insertSteps(steps)
            }
            dao.updateStepCount(solutionId, steps.size)
            imported++
        }
        return imported
    }

    private fun solutionToJson(data: SolutionWithSteps): JSONObject {
        val obj = JSONObject()
        obj.put("ssid", data.solution.ssid)
        obj.put("portalUrl", data.solution.portalUrl)
        obj.put("description", data.solution.description)
        obj.put("createdAt", data.solution.createdAt)
        obj.put("stepCount", data.solution.stepCount)

        val stepsArr = JSONArray()
        for (step in data.steps) {
            val stepObj = JSONObject()
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
            java.net.URL(candidate).host.lowercase().removePrefix("www.").ifBlank { null }
        }.getOrNull()
    }
}

