package dev.kaff.app.domain.model

enum class GestureState { None, OpenPalm, Fist }

/** Raw per-frame classification. [confidence] is in 0..1 for [state]. */
data class GestureReading(
    val state: GestureState,
    val confidence: Float,
    val fistScore: Float = 0f,
    val openScore: Float = 0f,
    val palmFacing: Boolean = false,
) {
    companion object {
        val NONE = GestureReading(GestureState.None, 0f)
    }
}

/**
 * Debounced gesture. [justConfirmed] is true only on the frame where [state] became stable,
 * [stableSinceMs] is the timestamp of that frame (used for hold-to-confirm).
 */
data class StableGesture(
    val state: GestureState,
    val confidence: Float,
    val stableSinceMs: Long,
    val justConfirmed: Boolean,
) {
    companion object {
        val NONE = StableGesture(GestureState.None, 0f, 0L, false)
    }
}
