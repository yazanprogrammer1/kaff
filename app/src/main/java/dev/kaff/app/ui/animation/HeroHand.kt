package dev.kaff.app.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kaff.app.ui.components.HandPoses
import dev.kaff.app.ui.components.HandSkeleton
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion

/**
 * The brand hero: concentric rings around a glowing disc with a hand skeleton that slowly closes
 * into a fist and opens again. Static open hand when the user prefers reduced motion.
 */
@Composable
fun HeroHand(modifier: Modifier = Modifier, size: Dp = 250.dp, animate: Boolean = true) {
    val c = KaffTheme.colors
    val reduceMotion = LocalReduceMotion.current || !animate
    val transition = rememberInfiniteTransition(label = "hero")
    val grip by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4200
                0f at 0
                0f at 1600
                1f at 2200 using FastOutSlowInEasing
                1f at 3000
                0f at 3600 using FastOutSlowInEasing
            },
        ),
        label = "grip",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Restart),
        label = "pulse",
    )
    val t = if (reduceMotion) 0f else grip

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val outer = this.size.minDimension / 2f
            drawCircle(c.primary.copy(alpha = 0.12f), outer - 1f, center, style = Stroke(1.dp.toPx()))
            drawCircle(c.primary.copy(alpha = 0.22f), outer - 22.dp.toPx(), center, style = Stroke(1.dp.toPx()))
            if (!reduceMotion) {
                // A ring that breathes outward, like a hand reaching toward the other phone.
                val r = outer * (0.55f + 0.45f * pulse)
                drawCircle(c.primary.copy(alpha = 0.25f * (1f - pulse)), r, center, style = Stroke(1.5.dp.toPx()))
            }
            val disc = outer - 46.dp.toPx()
            // Soft light spilling out of the disc (the design's 60px box-shadow), not a hard band.
            val halo = disc + 40.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    0f to c.primary.copy(alpha = 0.3f + 0.12f * t),
                    disc / halo to c.primary.copy(alpha = 0.22f + 0.1f * t),
                    1f to c.primary.copy(alpha = 0f),
                    center = center,
                    radius = halo,
                ),
                halo,
                center,
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(if (c.isDark) c.glow.copy(alpha = 1f) else c.glow, c.background),
                    center = center.copy(y = center.y - disc * 0.2f),
                    radius = disc * 1.2f,
                ),
                disc,
                center,
            )
            drawCircle(c.primary.copy(alpha = 0.5f), disc, center, style = Stroke(1.dp.toPx()))
        }
        HandSkeleton(
            landmarks = HandPoses.lerp(HandPoses.OPEN, HandPoses.FIST, t),
            imageAspect = HandPoses.ASPECT,
            fit = true,
            glow = t > 0.95f,
            boneWidth = 2.5.dp,
            jointRadius = 3.dp,
            tipRadius = 4.5.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(size * 0.22f),
        )
    }
}
