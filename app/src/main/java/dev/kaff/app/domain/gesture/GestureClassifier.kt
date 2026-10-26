package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.GestureReading
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.HandFrame
import dev.kaff.app.domain.model.HandIndex
import dev.kaff.app.domain.model.Handedness
import dev.kaff.app.domain.model.Landmark
import kotlin.math.sqrt

/**
 * Classifies a single frame into [GestureState]. Pure and stateless: stability over time is the
 * job of [GestureDebouncer].
 *
 * Every measurement is a ratio of distances (or a cross product normalized by palm size squared),
 * so the result does not change with in-plane rotation, scale, or position of the hand.
 */
class GestureClassifier(private val thresholds: GestureThresholds = GestureThresholds()) {

    fun classify(frame: HandFrame?): GestureReading {
        if (frame == null) return GestureReading.NONE
        // Normalized x (and MediaPipe z, which shares the x scale) are stretched by the aspect
        // ratio so distances are isotropic in pixels.
        val aspect = frame.imageWidth.toFloat() / frame.imageHeight.coerceAtLeast(1)
        val lm = frame.landmarks.map { Landmark(it.x * aspect, it.y, it.z * aspect) }
        val wrist = lm[HandIndex.WRIST]
        val palm = dist(wrist, lm[HandIndex.MIDDLE_MCP])
        if (palm < MIN_PALM_SIZE) return GestureReading.NONE

        val curl = ArrayList<Float>(HandIndex.LONG_FINGERS.size)
        val extension = ArrayList<Float>(HandIndex.LONG_FINGERS.size)
        HandIndex.LONG_FINGERS.forEach { finger ->
            val ratio = dist(wrist, lm[finger.tip]) / dist(wrist, lm[finger.pip]).coerceAtLeast(EPSILON)
            val tipToKnuckle = dist(lm[finger.tip], lm[finger.mcp]) / palm
            val ratioCurl = ramp(thresholds.extendedRatioMin, thresholds.curledRatioMax, ratio)
            val knuckleCurl = ramp(TIP_KNUCKLE_OPEN, TIP_KNUCKLE_CURLED, tipToKnuckle)
            curl += (ratioCurl + knuckleCurl) / 2f
            extension += ramp(thresholds.curledRatioMax, thresholds.extendedRatioMin, ratio)
        }

        val thumbToPalm = dist(lm[HandIndex.THUMB_TIP], lm[HandIndex.MIDDLE_MCP]) / palm
        val thumbFolded = ramp(thresholds.thumbFoldMax + THUMB_RAMP, thresholds.thumbFoldMax, thumbToPalm)
        val thumbOut = ramp(THUMB_OUT_LOW, THUMB_OUT_HIGH, dist(lm[HandIndex.THUMB_TIP], lm[HandIndex.INDEX_MCP]) / palm)

        val fistScore = blend(curl) * (THUMB_BASE + (1f - THUMB_BASE) * thumbFolded)

        val spread = dist(lm[HandIndex.INDEX_TIP], lm[HandIndex.PINKY_TIP]) / palm
        val spreadScore = ramp(thresholds.spreadMin - SPREAD_RAMP, thresholds.spreadMin, spread)
        val facing = palmFacingAmount(lm, frame.handedness, palm)
        val palmFacing = facing >= thresholds.palmFacingMin
        val facingScore = if (thresholds.requirePalmFacing) ramp(0f, thresholds.palmFacingMin, facing) else 1f
        val openScore = blend(extension) * spreadScore * facingScore * (THUMB_BASE + (1f - THUMB_BASE) * thumbOut)

        val state = when {
            fistScore >= thresholds.minRawScore && fistScore >= openScore -> GestureState.Fist
            openScore >= thresholds.minRawScore -> GestureState.OpenPalm
            else -> GestureState.None
        }
        val confidence = when (state) {
            GestureState.Fist -> fistScore
            GestureState.OpenPalm -> openScore
            GestureState.None -> 1f - maxOf(fistScore, openScore)
        }
        return GestureReading(state, confidence, fistScore, openScore, palmFacing)
    }

    /**
     * Signed area spanned by wrist->index MCP and wrist->pinky MCP, normalized by palm size squared.
     * Positive means the palm faces the camera. In display space (y down, selfie mirrored) a right
     * palm facing the camera has the index MCP left of the pinky MCP, which gives a positive cross.
     */
    private fun palmFacingAmount(lm: List<Landmark>, handedness: Handedness, palm: Float): Float {
        val w = lm[HandIndex.WRIST]
        val a = lm[HandIndex.INDEX_MCP]
        val b = lm[HandIndex.PINKY_MCP]
        val cross = (a.x - w.x) * (b.y - w.y) - (a.y - w.y) * (b.x - w.x)
        val normalized = cross / (palm * palm)
        return if (handedness == Handedness.Right) normalized else -normalized
    }

    private companion object {
        const val EPSILON = 1e-6f
        const val MIN_PALM_SIZE = 0.01f
        const val TIP_KNUCKLE_OPEN = 0.7f
        const val TIP_KNUCKLE_CURLED = 0.35f
        const val THUMB_RAMP = 0.3f
        const val THUMB_BASE = 0.7f
        const val THUMB_OUT_LOW = 0.3f
        const val THUMB_OUT_HIGH = 0.6f
        const val SPREAD_RAMP = 0.25f

        fun dist(a: Landmark, b: Landmark): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        /** Linear ramp from 0 at [from] to 1 at [to] (works for descending ranges too), clamped. */
        fun ramp(from: Float, to: Float, value: Float): Float =
            ((value - from) / (to - from)).coerceIn(0f, 1f)

        /** Weighs the weakest finger so one stray finger cannot be averaged away. */
        fun blend(scores: List<Float>): Float = 0.5f * scores.min() + 0.5f * scores.average().toFloat()
    }
}
