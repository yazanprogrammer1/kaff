package dev.kaff.app.domain.model

import dev.kaff.app.domain.gesture.GestureThresholds

/** Kaff is designed dark-first; light and system-following are opt-in. */
enum class ThemeMode { Dark, Light, System }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.Dark,
    /** Name shown to nearby devices. Blank means "use the phone model". */
    val deviceName: String = "",
    /** Camera turns itself off after this many seconds without a visible hand. */
    val cameraTimeoutSec: Int = DEFAULT_CAMERA_TIMEOUT_SEC,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = false,
    /** 0 = strict, 1 = sensitive. */
    val gestureSensitivity: Float = GestureThresholds.DEFAULT_SENSITIVITY,
    /**
     * Require the palm (not the back of the hand) to face the camera. Off by default: measured on
     * device, hands arriving at an angle from the other phone were rejected, and the pairing code
     * (not the hand) is what protects the transfer.
     */
    val strictPalmCheck: Boolean = false,
    val shakeToWake: Boolean = false,
    val standbyMode: Boolean = false,
    val floatingBubble: Boolean = false,
) {
    val gestureThresholds: GestureThresholds
        get() = GestureThresholds.forSensitivity(gestureSensitivity).copy(requirePalmFacing = strictPalmCheck)

    companion object {
        const val DEFAULT_CAMERA_TIMEOUT_SEC = 10
        val CAMERA_TIMEOUT_RANGE = 5..60
    }
}

/** Device conditions that make the gesture pipeline throttle itself. */
data class PowerState(
    val batterySaver: Boolean = false,
    /** Thermal status is severe or worse: analysis pauses until the phone cools down. */
    val thermalThrottled: Boolean = false,
)
