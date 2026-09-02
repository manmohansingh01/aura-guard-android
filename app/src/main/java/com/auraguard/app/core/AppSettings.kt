package com.auraguard.app.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "aura_guard_settings")

/** User-configurable runtime settings, persisted locally via DataStore. Never synced to any server. */
data class AppSettings(
    val processingRate: ProcessingRate = ProcessingRate.MEDIUM,
    val detectionConfidenceThreshold: Float = 0.45f,
    val changeDetectionThreshold: Float = 0.35f,
    val audibleAlertsEnabled: Boolean = true,
    val demoModeEnabled: Boolean = false,
    val demoVideoUri: String? = null
)

/** Thin repository around Jetpack DataStore. Kept in `core` since every stage reads it. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val RATE = stringPreferencesKey("processing_rate")
        val DET_THRESHOLD = floatPreferencesKey("detection_threshold")
        val CHANGE_THRESHOLD = floatPreferencesKey("change_threshold")
        val AUDIBLE = booleanPreferencesKey("audible_alerts")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val DEMO_URI = stringPreferencesKey("demo_video_uri")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            processingRate = ProcessingRate.entries.firstOrNull { it.name == prefs[Keys.RATE] }
                ?: ProcessingRate.MEDIUM,
            detectionConfidenceThreshold = prefs[Keys.DET_THRESHOLD] ?: 0.45f,
            changeDetectionThreshold = prefs[Keys.CHANGE_THRESHOLD] ?: 0.35f,
            audibleAlertsEnabled = prefs[Keys.AUDIBLE] ?: true,
            demoModeEnabled = prefs[Keys.DEMO_MODE] ?: false,
            demoVideoUri = prefs[Keys.DEMO_URI]
        )
    }

    suspend fun setProcessingRate(rate: ProcessingRate) {
        context.dataStore.edit { it[Keys.RATE] = rate.name }
    }

    suspend fun setDetectionThreshold(value: Float) {
        context.dataStore.edit { it[Keys.DET_THRESHOLD] = value }
    }

    suspend fun setChangeThreshold(value: Float) {
        context.dataStore.edit { it[Keys.CHANGE_THRESHOLD] = value }
    }

    suspend fun setAudibleAlerts(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUDIBLE] = enabled }
    }

    suspend fun setDemoMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEMO_MODE] = enabled }
    }

    suspend fun setDemoVideoUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.DEMO_URI) else it[Keys.DEMO_URI] = uri
        }
    }
}
