package com.dm.labs.wifi.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ScanLog(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String
)

object ScanLogManager {
    private val _logs = MutableStateFlow<List<ScanLog>>(emptyList())
    val logs: StateFlow<List<ScanLog>> = _logs.asStateFlow()

    fun log(message: String) {
        _logs.update { current ->
            listOf(ScanLog(message = message)) + current
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

