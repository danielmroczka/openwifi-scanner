package com.example.wifi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class UserNetworkDecision {
    WHITELIST, BLACKLIST, SKIP
}

data class PendingNetworkApproval(
    val ssid: String,
    val bssid: String,
    val level: Int
)

object NetworkApprovalManager {
    private val _pending = MutableStateFlow<PendingNetworkApproval?>(null)
    val pending: StateFlow<PendingNetworkApproval?> = _pending.asStateFlow()

    private val _decision = MutableSharedFlow<UserNetworkDecision>(extraBufferCapacity = 1)

    fun requestApproval(network: PendingNetworkApproval) {
        _pending.value = network
    }

    suspend fun awaitDecision(timeoutMs: Long = 60_000L): UserNetworkDecision {
        return withTimeoutOrNull(timeoutMs) {
            _decision.first()
        } ?: run {
            _pending.value = null
            UserNetworkDecision.SKIP
        }
    }

    fun submitDecision(decision: UserNetworkDecision) {
        _pending.value = null
        _decision.tryEmit(decision)
    }

    fun clear() {
        _pending.value = null
    }
}

