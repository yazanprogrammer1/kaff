package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.GestureReading
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.HandFrame
import dev.kaff.app.domain.model.StableGesture

/** Result of running one frame through the gesture pipeline. */
data class GestureSnapshot(
    val frame: HandFrame?,
    val reading: GestureReading,
    val stable: StableGesture,
    val timestampMs: Long = 0L,
) {
    /** How long the current stable gesture has been held, in camera time. */
    val heldMs: Long get() = if (stable.state == GestureState.None) 0L else (timestampMs - stable.stableSinceMs).coerceAtLeast(0L)

    companion object {
        val EMPTY = GestureSnapshot(null, GestureReading.NONE, StableGesture.NONE)
    }
}

/** Classifier + debouncer with swappable thresholds (driven by the sensitivity setting). */
class GestureEngine(thresholds: GestureThresholds = GestureThresholds()) {

    private var classifier = GestureClassifier(thresholds)
    private var debouncer = GestureDebouncer(thresholds)

    var thresholds: GestureThresholds = thresholds
        set(value) {
            if (value == field) return
            field = value
            classifier = GestureClassifier(value)
            debouncer = GestureDebouncer(value)
        }

    fun process(frame: HandFrame?, timestampMs: Long): GestureSnapshot {
        val reading = classifier.classify(frame)
        return GestureSnapshot(frame, reading, debouncer.update(reading, timestampMs), timestampMs)
    }

    fun reset() = debouncer.reset()
}
