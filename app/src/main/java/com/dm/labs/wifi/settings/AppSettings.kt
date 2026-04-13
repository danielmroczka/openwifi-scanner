package com.dm.labs.wifi.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettingsState(
    val scanIntervalSeconds: Int = DEFAULT_SCAN_INTERVAL_SECONDS
) {
    val scanIntervalMs: Long get() = scanIntervalSeconds * 1_000L

    companion object {
        const val DEFAULT_SCAN_INTERVAL_SECONDS = 5
    }
}

class AppSettings private constructor(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    var scanIntervalSeconds: Int
        get() = _state.value.scanIntervalSeconds
        set(value) {
            val clamped = value.coerceIn(MIN_SCAN_INTERVAL, MAX_SCAN_INTERVAL)
            prefs.edit().putInt(KEY_SCAN_INTERVAL, clamped).apply()
            _state.value = _state.value.copy(scanIntervalSeconds = clamped)
        }

    private fun load(): AppSettingsState {
        return AppSettingsState(
            scanIntervalSeconds = prefs.getInt(
                KEY_SCAN_INTERVAL,
                AppSettingsState.DEFAULT_SCAN_INTERVAL_SECONDS
            )
        )
    }

    companion object {
        private const val PREFS_NAME = "wifi_app_settings"
        private const val KEY_SCAN_INTERVAL = "scan_interval_seconds"

        const val MIN_SCAN_INTERVAL = 2
        const val MAX_SCAN_INTERVAL = 60

        @Volatile
        private var INSTANCE: AppSettings? = null

        fun getInstance(context: Context): AppSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettings(
                    context.applicationContext.getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
                ).also { INSTANCE = it }
            }
        }
    }
}

