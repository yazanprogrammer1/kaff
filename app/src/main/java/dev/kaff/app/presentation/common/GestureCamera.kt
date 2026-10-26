package dev.kaff.app.presentation.common

import androidx.compose.runtime.Immutable
import dev.kaff.app.data.hand.HandTracker
import dev.kaff.app.domain.gesture.GestureEngine
import dev.kaff.app.domain.gesture.GestureSnapshot
import dev.kaff.app.domain.model.AppSettings
import dev.kaff.app.domain.model.PowerState
import dev.kaff.app.domain.model.StableGesture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class GestureCameraState(
    /** Camera should be bound. False after the auto-timeout or when explicitly put to sleep. */
    val active: Boolean = true,
    val snapshot: GestureSnapshot = GestureSnapshot.EMPTY,
    val fps: Float = 0f,
    val batterySaver: Boolean = false,
    val thermalPaused: Boolean = false,
    val trackerFailed: Boolean = false,
)

/**
 * Camera + gesture pipeline shared by the Lab, Send and Receive screens.
 *
 * Owns the [HandTracker] lifecycle, applies the gesture thresholds from settings, throttles on
 * battery saver, pauses on severe thermal status, and turns the camera off after
 * [AppSettings.cameraTimeoutSec] without a visible hand.
 */
class GestureCamera(
    val tracker: HandTracker,
    private val scope: CoroutineScope,
    settings: Flow<AppSettings>,
    power: Flow<PowerState>,
    private val analysisDispatcher: CoroutineDispatcher,
    private val autoTimeout: Boolean = true,
) {
    private val engine = GestureEngine()
    private val _state = MutableStateFlow(GestureCameraState())
    val state: StateFlow<GestureCameraState> = _state.asStateFlow()

    private val _confirmations = MutableSharedFlow<StableGesture>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once each time a gesture becomes stable. */
    val confirmations: SharedFlow<StableGesture> = _confirmations.asSharedFlow()

    @Volatile private var timeoutMs = AppSettings.DEFAULT_CAMERA_TIMEOUT_SEC * 1000L

    /** Bumped on every sign of use; the timeout restarts from the latest bump. */
    private val activity = MutableStateFlow(0L)

    init {
        scope.launch {
            combine(settings, power) { s, p -> s to p }.collect { (s, p) ->
                engine.thresholds = s.gestureThresholds
                timeoutMs = s.cameraTimeoutSec * 1000L
                tracker.minFrameIntervalMs = if (p.batterySaver) BATTERY_SAVER_INTERVAL_MS else 0L
                _state.update { it.copy(batterySaver = p.batterySaver, thermalPaused = p.thermalThrottled) }
                applyPause()
                bumpActivity()
            }
        }
        scope.launch {
            activity.collectLatest {
                if (autoTimeout && _state.value.active) {
                    delay(timeoutMs)
                    sleep()
                }
            }
        }
        scope.launch(analysisDispatcher) {
            tracker.frames.collect { tracked ->
                val snapshot = engine.process(tracked.hand, tracked.timestampMs)
                if (tracked.hand != null) bumpActivity()
                _state.update { it.copy(snapshot = snapshot, fps = tracker.fps.value) }
                if (snapshot.stable.justConfirmed) _confirmations.tryEmit(snapshot.stable)
            }
        }
        scope.launch { tracker.failed.collect { failed -> _state.update { it.copy(trackerFailed = failed) } } }
    }

    /** Turns the camera back on (tap to wake) and restarts the timeout. */
    fun wake() {
        _state.update { it.copy(active = true) }
        applyPause()
        bumpActivity()
    }

    /** Turns the camera off immediately (completion, cancel, backgrounding). */
    fun sleep() {
        engine.reset()
        _state.update { it.copy(active = false, snapshot = GestureSnapshot.EMPTY) }
        applyPause()
    }

    /** Clears the debouncer so a held gesture must be made again. */
    fun resetGesture() {
        engine.reset()
        _state.update { it.copy(snapshot = GestureSnapshot.EMPTY) }
    }

    fun close() {
        tracker.close()
    }

    private fun applyPause() {
        val s = _state.value
        tracker.paused = !s.active || s.thermalPaused
    }

    private fun bumpActivity() = activity.update { it + 1 }

    private companion object {
        /** About 15 fps instead of the camera's 30 when battery saver is on. */
        const val BATTERY_SAVER_INTERVAL_MS = 66L
    }
}
