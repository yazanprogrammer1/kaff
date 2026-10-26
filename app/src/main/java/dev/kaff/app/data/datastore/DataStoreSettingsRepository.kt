package dev.kaff.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.model.ThemeMode
import dev.kaff.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .map { it.toSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = this[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: defaults.themeMode,
            deviceName = this[DEVICE_NAME] ?: defaults.deviceName,
            cameraTimeoutSec = (this[CAMERA_TIMEOUT] ?: defaults.cameraTimeoutSec)
                .coerceIn(AppSettings.CAMERA_TIMEOUT_RANGE),
            hapticsEnabled = this[HAPTICS] ?: defaults.hapticsEnabled,
            soundEnabled = this[SOUND] ?: defaults.soundEnabled,
            gestureSensitivity = (this[SENSITIVITY] ?: defaults.gestureSensitivity).coerceIn(0f, 1f),
            strictPalmCheck = this[STRICT_PALM] ?: defaults.strictPalmCheck,
            shakeToWake = this[SHAKE] ?: defaults.shakeToWake,
            standbyMode = this[STANDBY] ?: defaults.standbyMode,
            floatingBubble = this[BUBBLE] ?: defaults.floatingBubble,
        )
    }

    private fun MutablePreferences.write(settings: AppSettings) {
        this[THEME] = settings.themeMode.name
        this[DEVICE_NAME] = settings.deviceName.trim().take(MAX_NAME_LENGTH)
        this[CAMERA_TIMEOUT] = settings.cameraTimeoutSec.coerceIn(AppSettings.CAMERA_TIMEOUT_RANGE)
        this[HAPTICS] = settings.hapticsEnabled
        this[SOUND] = settings.soundEnabled
        this[SENSITIVITY] = settings.gestureSensitivity.coerceIn(0f, 1f)
        this[STRICT_PALM] = settings.strictPalmCheck
        this[SHAKE] = settings.shakeToWake
        this[STANDBY] = settings.standbyMode
        this[BUBBLE] = settings.floatingBubble
    }

    private companion object {
        const val MAX_NAME_LENGTH = 32
        val THEME = stringPreferencesKey("theme_mode")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val CAMERA_TIMEOUT = intPreferencesKey("camera_timeout_sec")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SOUND = booleanPreferencesKey("sound")
        val SENSITIVITY = floatPreferencesKey("gesture_sensitivity")
        val STRICT_PALM = booleanPreferencesKey("strict_palm")
        val SHAKE = booleanPreferencesKey("shake_to_wake")
        val STANDBY = booleanPreferencesKey("standby_mode")
        val BUBBLE = booleanPreferencesKey("floating_bubble")
    }
}
