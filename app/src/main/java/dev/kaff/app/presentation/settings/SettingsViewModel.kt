package dev.kaff.app.presentation.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.data.platform.AndroidLocalDevice
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.repository.SettingsRepository
import dev.kaff.app.service.FloatingBubbleService
import dev.kaff.app.service.StandbyService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SettingsRepository,
    device: AndroidLocalDevice,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val defaultDeviceName: String = device.defaultName

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repository.update(transform) }
    }

    fun setDeviceName(name: String) = update { it.copy(deviceName = name) }
    fun setCameraTimeout(seconds: Int) = update { it.copy(cameraTimeoutSec = seconds) }
    fun setHaptics(on: Boolean) = update { it.copy(hapticsEnabled = on) }
    fun setSound(on: Boolean) = update { it.copy(soundEnabled = on) }
    fun setSensitivity(value: Float) = update { it.copy(gestureSensitivity = value) }
    fun setStrictPalm(on: Boolean) = update { it.copy(strictPalmCheck = on) }
    fun setShake(on: Boolean) = update { it.copy(shakeToWake = on) }

    /** Caller has already made sure the Nearby permissions are granted. */
    fun setStandby(on: Boolean) {
        update { it.copy(standbyMode = on) }
        if (on) StandbyService.start(context) else StandbyService.stop(context)
    }

    /** Returns false when the overlay permission is missing so the UI can open its settings page. */
    fun setBubble(on: Boolean): Boolean {
        if (on && !Settings.canDrawOverlays(context)) return false
        update { it.copy(floatingBubble = on) }
        if (on) FloatingBubbleService.start(context) else FloatingBubbleService.stop(context)
        return true
    }
}
