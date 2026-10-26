package dev.kaff.app.domain.gesture

import dev.kaff.app.domain.model.HandFrame
import dev.kaff.app.domain.model.Handedness
import dev.kaff.app.domain.model.Landmark
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds anatomically plausible 21-landmark hands from a tiny 3D kinematic model, so gesture
 * tests do not depend on recorded camera data.
 *
 * Hand space: wrist at the origin, palm size 1 (wrist to middle MCP), fingers pointing +y, palm
 * in the xy plane. The canonical hand is a right hand with its palm facing the camera in display
 * space (index on the left, pinky on the right).
 */
object SyntheticHand {

    /** Per-finger flexion in degrees at MCP, PIP and DIP. */
    data class Pose(
        val index: FloatArray,
        val middle: FloatArray,
        val ring: FloatArray,
        val pinky: FloatArray,
        val thumbFolded: Boolean,
        val splayScale: Float = 1f,
    )

    private val STRAIGHT = floatArrayOf(0f, 0f, 0f)
    private val CURLED = floatArrayOf(80f, 100f, 70f)
    private val CLAW = floatArrayOf(0f, 60f, 40f)

    val OPEN = Pose(STRAIGHT, STRAIGHT, STRAIGHT, STRAIGHT, thumbFolded = false)
    val FIST = Pose(CURLED, CURLED, CURLED, CURLED, thumbFolded = true)
    val POINTING = Pose(STRAIGHT, CURLED, CURLED, CURLED, thumbFolded = true)
    val PEACE = Pose(STRAIGHT, STRAIGHT, CURLED, CURLED, thumbFolded = true)
    val CLAW_HAND = Pose(CLAW, CLAW, CLAW, CLAW, thumbFolded = false)
    val PINCHED = Pose(STRAIGHT, STRAIGHT, STRAIGHT, STRAIGHT, thumbFolded = true, splayScale = -2.2f)

    private data class FingerSpec(val mcpX: Float, val mcpY: Float, val splayDeg: Float, val segments: FloatArray)

    private val FINGERS = listOf(
        FingerSpec(-0.32f, 0.95f, -12f, floatArrayOf(0.42f, 0.25f, 0.22f)),
        FingerSpec(0.00f, 1.00f, -2f, floatArrayOf(0.46f, 0.28f, 0.24f)),
        FingerSpec(0.28f, 0.93f, 8f, floatArrayOf(0.43f, 0.26f, 0.23f)),
        FingerSpec(0.52f, 0.80f, 18f, floatArrayOf(0.33f, 0.20f, 0.19f)),
    )

    private val THUMB_OPEN = listOf(-0.25f to 0.25f, -0.48f to 0.42f, -0.68f to 0.55f, -0.85f to 0.65f)
    private val THUMB_FOLDED = listOf(-0.25f to 0.25f, -0.38f to 0.45f, -0.25f to 0.62f, -0.05f to 0.70f)

    /**
     * @param rotationDeg in-plane rotation around the wrist (display space).
     * @param scale palm size in normalized image units.
     * @param backFacing shows the back of the hand (mirrors the geometry, keeps the label).
     */
    fun frame(
        pose: Pose,
        handedness: Handedness = Handedness.Right,
        rotationDeg: Float = 0f,
        scale: Float = 0.2f,
        centerX: Float = 0.5f,
        wristY: Float = 0.75f,
        backFacing: Boolean = false,
        timestampMs: Long = 0L,
        imageWidth: Int = 480,
        imageHeight: Int = 480,
    ): HandFrame {
        val local = ArrayList<Triple<Float, Float, Float>>(21)
        local += Triple(0f, 0f, 0f)
        (if (pose.thumbFolded) THUMB_FOLDED else THUMB_OPEN).forEach { (x, y) -> local += Triple(x, y, 0f) }

        val flexions = listOf(pose.index, pose.middle, pose.ring, pose.pinky)
        FINGERS.forEachIndexed { i, spec ->
            val splay = Math.toRadians((spec.splayDeg * pose.splayScale).toDouble())
            var x = spec.mcpX
            var y = spec.mcpY
            var z = 0f
            local += Triple(x, y, z)
            var flex = 0.0
            spec.segments.forEachIndexed { s, length ->
                flex += Math.toRadians(flexions[i][s].toDouble())
                val inPlane = cos(flex)
                x += (length * sin(splay) * inPlane).toFloat()
                y += (length * cos(splay) * inPlane).toFloat()
                z += (length * sin(flex)).toFloat()
                local += Triple(x, y, z)
            }
        }

        // A left hand, or the back of a right hand, is the mirror image of the canonical hand.
        val mirror = (handedness == Handedness.Left) != backFacing
        val theta = Math.toRadians(rotationDeg.toDouble())
        val aspect = imageWidth.toFloat() / imageHeight
        val landmarks = local.map { (lx, ly, lz) ->
            val hx = if (mirror) -lx else lx
            // Hand space is y-up; display space is y-down.
            val dx = hx * scale
            val dy = -ly * scale
            val rx = (dx * cos(theta) - dy * sin(theta)).toFloat()
            val ry = (dx * sin(theta) + dy * cos(theta)).toFloat()
            Landmark(x = centerX + rx / aspect, y = wristY + ry, z = lz * scale / aspect)
        }
        return HandFrame(landmarks, handedness, timestampMs, imageWidth, imageHeight)
    }
}
