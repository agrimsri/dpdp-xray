package com.dpdpxray.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class XraySettings(
    val firstPartyDomains: Set<String> = emptySet(),
    val stageMode: Boolean = false,
    val fastDemo: Boolean = false,
    val autoLoadModel: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("xray_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<XraySettings> = _settings.asStateFlow()

    private fun read() = XraySettings(
        firstPartyDomains = prefs.getStringSet("first_party", emptySet()).orEmpty(),
        stageMode = prefs.getBoolean("stage_mode", false),
        fastDemo = prefs.getBoolean("fast_demo", false),
        autoLoadModel = prefs.getBoolean("auto_load_model", true),
    )

    fun update(transform: (XraySettings) -> XraySettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putStringSet("first_party", next.firstPartyDomains)
            putBoolean("stage_mode", next.stageMode)
            putBoolean("fast_demo", next.fastDemo)
            putBoolean("auto_load_model", next.autoLoadModel)
        }
        _settings.value = next
    }
}
