package dev.kaff.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kaff.app.domain.model.HandFrame
import dev.kaff.app.domain.model.HandIndex
import dev.kaff.app.domain.model.Landmark
import dev.kaff.app.ui.theme.KaffTheme

/**
 * Hand poses for illustrations, traced from the brand artwork (viewBox 190 x 250) in MediaPipe
 * landmark order so the same renderer draws live and illustrated hands.
 */
object HandPoses {
    private const val W = 190f
    private const val H = 250f

    private fun pose(vararg xy: Float): List<Landmark> =
        xy.toList().chunked(2).map { (x, y) -> Landmark(x / W, y / H) }

    val OPEN: List<Landmark> = pose(
        100f, 230f,
        72f, 205f, 50f, 178f, 34f, 150f, 22f, 126f,
        78f, 150f, 72f, 108f, 68f, 80f, 64f, 56f,
        100f, 142f, 100f, 96f, 100f, 64f, 100f, 36f,
        122f, 148f, 128f, 106f, 131f, 78f, 134f, 54f,
        142f, 164f, 154f, 134f, 162f, 112f, 170f, 92f,
    )

    val FIST: List<Landmark> = pose(
        100f, 230f,
        74f, 208f, 62f, 184f, 70f, 160f, 88f, 150f,
        80f, 160f, 74f, 132f, 88f, 122f, 96f, 140f,
        100f, 154f, 100f, 124f, 112f, 116f, 116f, 138f,
        122f, 160f, 126f, 134f, 136f, 128f, 136f, 148f,
        140f, 172f, 146f, 150f, 154f, 146f, 152f, 162f,
    )

    const val ASPECT = W / H

    /**
     * Re-frames a hand to its own bounding box so it can be drawn at a steady size, wherever it is
     * in the camera frame. [imageAspect] is the source image's width / height. Returns the
     * landmarks in 0..1 of the box and the box's aspect ratio.
     */
    fun framed(landmarks: List<Landmark>, imageAspect: Float): Pair<List<Landmark>, Float> {
        val xs = landmarks.map { it.x * imageAspect }
        val ys = landmarks.map { it.y }
        val minX = xs.min()
        val minY = ys.min()
        val w = (xs.max() - minX).coerceAtLeast(1e-4f)
        val h = (ys.max() - minY).coerceAtLeast(1e-4f)
        return landmarks.mapIndexed { i, lm -> Landmark((xs[i] - minX) / w, (ys[i] - minY) / h, lm.z) } to w / h
    }

    /** Linear blend between two poses; 0 = [from], 1 = [to]. */
    fun lerp(from: List<Landmark>, to: List<Landmark>, t: Float): List<Landmark> =
        from.zip(to) { a, b -> Landmark(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t) }
}

/**
 * Draws a hand skeleton. [landmarks] are normalized to an image of [imageAspect] (width / height)
 * that is center-cropped to fill the canvas, exactly like a FILL_CENTER PreviewView.
 */
@Composable
fun HandSkeleton(
    landmarks: List<Landmark>?,
    imageAspect: Float,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
    boneColor: Color = KaffTheme.colors.boneColor,
    tipColor: Color = KaffTheme.colors.tipColor,
    boneWidth: Dp = 3.dp,
    jointRadius: Dp = 4.dp,
    tipRadius: Dp = 5.5.dp,
    fit: Boolean = false,
) {
    val glowAmount by animateFloatAsState(if (glow) 1f else 0f, tween(280), label = "skeletonGlow")
    Canvas(modifier) {
        val points = landmarks ?: return@Canvas
        if (points.size != HandFrame.LANDMARK_COUNT) return@Canvas
        val mapped = mapToCanvas(points, imageAspect, fit)
        val bone = boneWidth.toPx()
        if (glowAmount > 0f) {
            HandIndex.BONES.forEach { (a, b) ->
                drawLine(boneColor.copy(alpha = 0.28f * glowAmount), mapped[a], mapped[b], bone * 4.5f, StrokeCap.Round)
            }
        }
        HandIndex.BONES.forEach { (a, b) -> drawLine(boneColor, mapped[a], mapped[b], bone, StrokeCap.Round) }
        val joint = jointRadius.toPx()
        mapped.forEachIndexed { i, p ->
            if (i !in HandIndex.FINGERTIPS) drawCircle(boneColor, joint, p)
        }
        val tip = tipRadius.toPx()
        HandIndex.FINGERTIPS.forEach { i ->
            if (glowAmount > 0f) drawCircle(tipColor.copy(alpha = 0.35f * glowAmount), tip * 2.2f, mapped[i])
            drawCircle(tipColor, tip, mapped[i])
        }
    }
}

/**
 * Maps normalized landmarks to canvas pixels. Fill (default) center-crops like the camera preview;
 * [fit] letterboxes, used for illustrations that must stay fully visible.
 */
private fun DrawScope.mapToCanvas(points: List<Landmark>, imageAspect: Float, fit: Boolean): List<Offset> {
    val canvasAspect = size.width / size.height
    val widthIsLimiting = if (fit) imageAspect > canvasAspect else imageAspect < canvasAspect
    val imageW: Float
    val imageH: Float
    if (widthIsLimiting) {
        imageW = size.width
        imageH = size.width / imageAspect
    } else {
        imageH = size.height
        imageW = size.height * imageAspect
    }
    val left = (size.width - imageW) / 2f
    val top = (size.height - imageH) / 2f
    return points.map { Offset(left + it.x * imageW, top + it.y * imageH) }
}
