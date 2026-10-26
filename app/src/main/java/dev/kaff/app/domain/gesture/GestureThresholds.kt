package dev.kaff.app.domain.gesture

/**
 * Tunables for [GestureClassifier] and [GestureDebouncer]. All distances are ratios normalized by
 * palm size (wrist to middle-finger MCP), so they do not depend on how far the hand is.
 */
data class GestureThresholds(
    /** A finger is fully curled when distance(wrist, tip) / distance(wrist, pip) is below this. */
    val curledRatioMax: Float = 0.95f,
    /** A finger is fully extended when distance(wrist, tip) / distance(wrist, pip) is above this. */
    val extendedRatioMin: Float = 1.2f,
    /** Thumb counts as folded when distance(thumb tip, middle MCP) / palm is below this. */
    val thumbFoldMax: Float = 0.95f,
    /** Open palm needs distance(index tip, pinky tip) / palm above this. */
    val spreadMin: Float = 0.75f,
    /** Minimum normalized palm-plane cross product (sign checked against handedness). */
    val palmFacingMin: Float = 0.12f,
    val requirePalmFacing: Boolean = true,
    /** Raw score needed before a frame counts as a candidate for a gesture. */
    val minRawScore: Float = 0.5f,
    /** Smoothed confidence needed to confirm a gesture, and the lower bound that keeps it. */
    val enterConfidence: Float = 0.7f,
    val exitConfidence: Float = 0.45f,
    /** Consecutive candidate frames needed to confirm (6 frames is about 200 ms at 30 fps). */
    val stableFrames: Int = 6,
    /** Consecutive weak frames needed to drop a confirmed gesture. */
    val releaseFrames: Int = 4,
    /** Exponential smoothing factor for confidence (higher reacts faster). */
    val smoothingAlpha: Float = 0.4f,
) {
    init {
        require(curledRatioMax < extendedRatioMin) { "curledRatioMax must be below extendedRatioMin" }
        require(exitConfidence <= enterConfidence) { "exitConfidence must not exceed enterConfidence" }
        require(stableFrames >= 1 && releaseFrames >= 1) { "frame counts must be positive" }
        require(smoothingAlpha in 0f..1f) { "smoothingAlpha must be in 0..1" }
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 0.5f

        /**
         * Maps the Settings slider (0 = strict, 1 = sensitive) to thresholds. The default 0.5
         * yields the default values above.
         */
        fun forSensitivity(sensitivity: Float): GestureThresholds {
            val s = sensitivity.coerceIn(0f, 1f)
            val t = s - DEFAULT_SENSITIVITY // -0.5..0.5
            return GestureThresholds(
                curledRatioMax = 0.95f + 0.10f * t,
                extendedRatioMin = 1.2f - 0.10f * t,
                thumbFoldMax = 0.95f + 0.20f * t,
                spreadMin = 0.75f - 0.30f * t,
                palmFacingMin = 0.12f - 0.10f * t,
                minRawScore = 0.5f - 0.10f * t,
                enterConfidence = 0.7f - 0.20f * t,
                exitConfidence = 0.45f - 0.10f * t,
                stableFrames = (6 - 4 * t).toInt().coerceAtLeast(3),
            )
        }
    }
}
