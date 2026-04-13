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

    // Suppress exact-duplicate messages that occur frequently to avoid noisy logs
    // (e.g. repeated scans every few seconds with identical content). If the
    // same message is logged again within SUPPRESSION_MS, it will be ignored.
    private const val SUPPRESSION_MS = 60_000L // 1 minute
    @Volatile
    private var lastMessage: String? = null
    @Volatile
    private var lastMessageTs: Long = 0L

    fun log(message: String) {
        val now = System.currentTimeMillis()
        val lm = lastMessage
        if (lm != null && lm == message && now - lastMessageTs < SUPPRESSION_MS) {
            // skip noisy duplicate
            return
        }
        lastMessage = message
        lastMessageTs = now
        _logs.update { current ->
            // keep recent logs at head; cap list to reasonable length
            (listOf(ScanLog(message = message)) + current).take(500)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}

