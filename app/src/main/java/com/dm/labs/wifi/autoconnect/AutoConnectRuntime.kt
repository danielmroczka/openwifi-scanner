package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.model.AutoConnectStateSource
import com.dm.labs.wifi.model.BackgroundAutoConnectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AutoConnectRuntime : AutoConnectStateSource {
    private val _state = MutableStateFlow(BackgroundAutoConnectState())
    override val state: StateFlow<BackgroundAutoConnectState> = _state.asStateFlow()

    fun update(state: BackgroundAutoConnectState) {
        _state.value = state
    }

    fun reset() {
        _state.value = BackgroundAutoConnectState()
    }
}

