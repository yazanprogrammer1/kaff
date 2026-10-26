package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.GestureReading
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.StableGesture

/**
 * Turns noisy per-frame readings into a stable gesture.
 *
 * - Confidence of each gesture is smoothed with an exponential moving average.
 * - A gesture is confirmed only after [GestureThresholds.stableFrames] consecutive frames that
 *   agree on it with smoothed confidence above `enterConfidence`.
 * - Hysteresis: a confirmed gesture survives until its smoothed confidence stays below
 *   `exitConfidence` for [GestureThresholds.releaseFrames] frames, so single-frame flicker never
 *   toggles the output.
 *
 * Not thread-safe: feed it from one thread (the analysis pipeline).
 */
class GestureDebouncer(private val thresholds: GestureThresholds = GestureThresholds()) {

    private val smoothed = FloatArray(GestureState.entries.size)
    private var candidate = GestureState.None
    private var candidateFrames = 0
    private var weakFrames = 0
    private var current = StableGesture.NONE

    val state: StableGesture get() = current

    fun update(reading: GestureReading, timestampMs: Long): StableGesture {
        smooth(reading)

        val held = current.state
        if (held != GestureState.None) {
            weakFrames = if (smoothedOf(held) < thresholds.exitConfidence) weakFrames + 1 else 0
        }

        val isCandidate = reading.state != GestureState.None &&
            reading.state != held &&
            smoothedOf(reading.state) >= thresholds.enterConfidence
        if (isCandidate && reading.state == candidate) {
            candidateFrames++
        } else if (isCandidate) {
            candidate = reading.state
            candidateFrames = 1
        } else {
            candidate = GestureState.None
            candidateFrames = 0
        }

        current = when {
            candidate != GestureState.None && candidateFrames >= thresholds.stableFrames -> {
                val confirmed = candidate
                candidate = GestureState.None
                candidateFrames = 0
                weakFrames = 0
                StableGesture(confirmed, smoothedOf(confirmed), timestampMs, justConfirmed = true)
            }
            held != GestureState.None && weakFrames >= thresholds.releaseFrames -> {
                weakFrames = 0
                StableGesture(GestureState.None, smoothedOf(GestureState.None), timestampMs, justConfirmed = false)
            }
            else -> current.copy(confidence = smoothedOf(held), justConfirmed = false)
        }
        return current
    }

    fun reset() {
        smoothed.fill(0f)
        candidate = GestureState.None
        candidateFrames = 0
        weakFrames = 0
        current = StableGesture.NONE
    }

    private fun smooth(reading: GestureReading) {
        val alpha = thresholds.smoothingAlpha
        GestureState.entries.forEach { state ->
            val target = when (state) {
                GestureState.Fist -> reading.fistScore
                GestureState.OpenPalm -> reading.openScore
                GestureState.None -> if (reading.state == GestureState.None) reading.confidence else 0f
            }
            smoothed[state.ordinal] += alpha * (target - smoothed[state.ordinal])
        }
    }

    private fun smoothedOf(state: GestureState): Float = smoothed[state.ordinal]
}
