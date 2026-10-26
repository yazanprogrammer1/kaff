package dev.kaff.app.domain.model

/** One MediaPipe hand landmark, normalized to the analyzed image (x, y in 0..1, y pointing down). */
data class Landmark(val x: Float, val y: Float, val z: Float = 0f)

/** The real hand of the user (already corrected for camera mirroring by the data layer). */
enum class Handedness { Left, Right }

/**
 * A single tracked hand in display space: the coordinates match what the user sees on screen,
 * so for the front camera they are mirrored like a selfie.
 */
data class HandFrame(
    val landmarks: List<Landmark>,
    val handedness: Handedness,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    init {
        require(landmarks.size == LANDMARK_COUNT) { "Expected $LANDMARK_COUNT landmarks, got ${landmarks.size}" }
    }

    companion object {
        const val LANDMARK_COUNT = 21
    }
}

/** MediaPipe hand landmark indices. */
object HandIndex {
    const val WRIST = 0
    const val THUMB_CMC = 1
    const val THUMB_MCP = 2
    const val THUMB_IP = 3
    const val THUMB_TIP = 4
    const val INDEX_MCP = 5
    const val INDEX_PIP = 6
    const val INDEX_TIP = 8
    const val MIDDLE_MCP = 9
    const val MIDDLE_PIP = 10
    const val MIDDLE_TIP = 12
    const val RING_MCP = 13
    const val RING_PIP = 14
    const val RING_TIP = 16
    const val PINKY_MCP = 17
    const val PINKY_PIP = 18
    const val PINKY_TIP = 20

    val FINGERTIPS = intArrayOf(THUMB_TIP, INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)

    /** Joints of one long finger. */
    data class Finger(val tip: Int, val pip: Int, val mcp: Int)

    val LONG_FINGERS = listOf(
        Finger(INDEX_TIP, INDEX_PIP, INDEX_MCP),
        Finger(MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP),
        Finger(RING_TIP, RING_PIP, RING_MCP),
        Finger(PINKY_TIP, PINKY_PIP, PINKY_MCP),
    )

    /** Bones of the MediaPipe hand topology, used by the skeleton overlay. */
    val BONES: List<Pair<Int, Int>> = listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
    )
}
