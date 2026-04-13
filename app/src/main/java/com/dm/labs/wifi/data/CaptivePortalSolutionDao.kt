package com.dm.labs.wifi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface CaptivePortalSolutionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSolution(solution: CaptivePortalSolutionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: CaptivePortalStepEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<CaptivePortalStepEntity>)

    @Query("SELECT * FROM captive_portal_solutions ORDER BY createdAt DESC")
    suspend fun getAllSolutions(): List<CaptivePortalSolutionEntity>

    @Query("SELECT * FROM captive_portal_solutions WHERE ssid = :ssid ORDER BY createdAt DESC")
    suspend fun getSolutionsForSsid(ssid: String): List<CaptivePortalSolutionEntity>

    @Query("SELECT * FROM captive_portal_solutions WHERE id = :solutionId")
    suspend fun getSolution(solutionId: Long): CaptivePortalSolutionEntity?

    @Query("SELECT * FROM captive_portal_steps WHERE solutionId = :solutionId ORDER BY stepOrder ASC")
    suspend fun getStepsForSolution(solutionId: Long): List<CaptivePortalStepEntity>

    @Query("UPDATE captive_portal_solutions SET stepCount = :count WHERE id = :solutionId")
    suspend fun updateStepCount(solutionId: Long, count: Int)

    @Query("UPDATE captive_portal_solutions SET ssid = :ssid, description = :description, portalUrl = :portalUrl WHERE id = :solutionId")
    suspend fun updateSolutionInfo(solutionId: Long, ssid: String, description: String, portalUrl: String)

    @Update
    suspend fun updateStep(step: CaptivePortalStepEntity)

    @Query("DELETE FROM captive_portal_steps WHERE id = :stepId")
    suspend fun deleteStep(stepId: Long)

    @Query("DELETE FROM captive_portal_solutions WHERE id = :solutionId")
    suspend fun deleteSolution(solutionId: Long)

    @Query("DELETE FROM captive_portal_steps WHERE solutionId = :solutionId")
    suspend fun deleteStepsForSolution(solutionId: Long)

    @Transaction
    suspend fun deleteSolutionWithSteps(solutionId: Long) {
        deleteStepsForSolution(solutionId)
        deleteSolution(solutionId)
    }
}

