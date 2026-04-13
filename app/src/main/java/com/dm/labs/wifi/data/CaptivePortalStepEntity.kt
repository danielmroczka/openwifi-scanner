package com.dm.labs.wifi.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "captive_portal_steps",
    foreignKeys = [
        ForeignKey(
            entity = CaptivePortalSolutionEntity::class,
            parentColumns = ["id"],
            childColumns = ["solutionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("solutionId")]
)
data class CaptivePortalStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val solutionId: Long,
    val stepOrder: Int,
    val type: String, // NAVIGATION, CLICK, FORM_SUBMIT, INPUT
    val url: String = "",
    val cssSelector: String = "",
    val inputValue: String = "",
    val elementId: String = "",
    val elementName: String = "",
    val elementType: String = "",
    val formData: String = "", // JSON string of form key-value pairs
    val timestamp: Long = System.currentTimeMillis()
)

