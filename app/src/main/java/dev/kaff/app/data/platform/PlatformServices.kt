package dev.kaff.app.data.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.core.di.ApplicationScope
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.FeedbackPlayer
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.repository.SettingsRepository
import dev.kaff.app.domain.transfer.FeedbackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemClockImpl @Inject constructor() : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

@Singleton
class AndroidLocalDevice @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : LocalDevice {
    /** The name the user gave the phone in system settings, else the model ("Pixel 8"). */
    val defaultName: String = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override val name: StateFlow<String> = settings.settings
        .map { it.deviceName.ifBlank { defaultName } }
        .stateIn(scope, SharingStarted.Eagerly, defaultName)
}

@Singleton
class AndroidFeedbackPlayer @Inject constructor(
    @ApplicationContext context: Context,
    settings: SettingsRepository,
    @ApplicationScope scope: CoroutineScope,
) : FeedbackPlayer {

    private val current = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    override fun play(kind: FeedbackKind) {
        val s = current.value
        if (s.hapticsEnabled) vibrate(kind)
        if (s.soundEnabled && kind == FeedbackKind.Success) chime()
    }

    private fun vibrate(kind: FeedbackKind) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (kind) {
                FeedbackKind.Grab -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                FeedbackKind.Accept -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                FeedbackKind.Attention -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                FeedbackKind.Success -> VibrationEffect.createWaveform(longArrayOf(0, 25, 70, 45), -1)
                FeedbackKind.Error -> VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1)
            }
        } else {
            when (kind) {
                FeedbackKind.Success, FeedbackKind.Error -> VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1)
                else -> VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            }
        }
        v.vibrate(effect)
    }

    private fun chime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        } catch (e: RuntimeException) {
            // Audio hardware busy or unavailable; the haptic already confirmed the arrival.
        }
    }
}
