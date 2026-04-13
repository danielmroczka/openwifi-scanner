package com.dm.labs.wifi.autoconnect

import com.dm.labs.wifi.model.BackgroundAutoConnectState

internal class CaptivePortalRecoveryHandler(
    private val portalSolver: suspend (String?) -> Boolean,
    private val disconnectCurrentNetwork: () -> Unit,
    private val putOnCooldown: (String) -> Unit,
    private val scanLog: (String) -> Unit,
    private val devInfo: (String) -> Unit,
    private val devWarn: (String) -> Unit
) {
    suspend fun recover(
        state: BackgroundAutoConnectState,
        attempts: Int,
        pushState: (BackgroundAutoConnectState) -> Unit
    ): BackgroundAutoConnectState {
        pushState(state.copy(message = "Solving captive portal on ${state.currentSsid}..."))
        devInfo("Attempting captive portal solve on ${state.currentSsid}...")

        val solved = portalSolver(state.currentSsid)
        if (solved) {
            scanLog("Captive portal solved on ${state.currentSsid}.")
            devInfo("Captive portal solved successfully on ${state.currentSsid}")
            val successState = state.copy(
                captivePortalDetected = false,
                hasValidatedInternet = true,
                message = "Connected to ${state.currentSsid} with internet."
            )
            pushState(successState)
            return successState
        }

        disconnectCurrentNetwork()
        val ssid = state.currentSsid ?: "unknown"
        putOnCooldown(ssid)
        scanLog("Captive portal solve failed on $ssid - network on 1h cooldown.")
        devWarn("Captive portal solve failed on $ssid. Disconnected, put on cooldown.")

        val retryState = BackgroundAutoConnectState(
            isRunning = true,
            attempts = attempts,
            message = "Portal solve failed on $ssid. Retrying..."
        )
        pushState(retryState)
        return retryState
    }
}

