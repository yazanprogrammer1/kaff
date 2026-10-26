package dev.kaff.app.ui.animation

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kaff.app.core.util.formatNumber
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Milliseconds left until [deadlineElapsedMs] (SystemClock.elapsedRealtime base), updated per frame. */
@Composable
fun rememberRemainingMs(deadlineElapsedMs: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadlineElapsedMs) {
        while (now < deadlineElapsedMs) {
            withFrameMillis { now = SystemClock.elapsedRealtime() }
        }
    }
    return (deadlineElapsedMs - now).coerceAtLeast(0)
}

/** Circular countdown with the whole seconds left in the middle. */
@Composable
fun CountdownRing(
    remainingMs: Long,
    totalMs: Long,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val c = KaffTheme.colors
    val fraction = if (totalMs <= 0) 0f else (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
    Box(modifier.size(size).semantics { this.contentDescription = contentDescription }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(c.text.copy(alpha = 0.1f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(c.primary, -90f, 360f * fraction, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text(
            formatNumber(ceil(remainingMs / 1000.0), 0),
            style = MaterialTheme.typography.titleLarge,
            color = c.text,
        )
    }
}

/**
 * Soft particles drifting down from the lifted file, like dust left behind by the grab.
 * Disabled when reduce motion is on.
 */
@Composable
fun ParticleTrail(active: Boolean, modifier: Modifier = Modifier, count: Int = 18) {
    if (!active || LocalReduceMotion.current) return
    val c = KaffTheme.colors
    val transition = rememberInfiniteTransition(label = "particles")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
    val seeds = remember(count) { List(count) { i -> Triple((i * 37 % 100) / 100f, (i * 53 % 100) / 100f, 0.5f + (i * 17 % 50) / 100f) } }
    Canvas(modifier) {
        seeds.forEach { (xSeed, phase, speed) ->
            val p = (t * speed + phase) % 1f
            val x = size.width * (0.2f + 0.6f * xSeed) + sin((p * 2 * PI + xSeed * 6).toFloat()) * 10.dp.toPx()
            val y = size.height * (0.15f + 0.85f * p)
            val alpha = (1f - p) * 0.7f
            drawCircle(c.primarySoft.copy(alpha = alpha), radius = (1.2f + 2.2f * (1f - p)).dp.toPx(), center = Offset(x, y))
        }
    }
}

/**
 * Concentric rings around a target. [energy] 0..1 (e.g. palm hold progress) makes the rings
 * expand and brighten; a slow idle pulse keeps the area alive.
 */
@Composable
fun PulseRings(energy: Float, modifier: Modifier = Modifier) {
    val c = KaffTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "rings")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension / 2
        val e = energy.coerceIn(0f, 1f)
        drawCircle(c.primary.copy(alpha = 0.14f + 0.2f * e), base * (0.98f), center, style = Stroke(1.dp.toPx()))
        drawCircle(c.primary.copy(alpha = 0.28f + 0.3f * e), base * (0.78f + 0.06f * e), center, style = Stroke(1.dp.toPx()))
        drawCircle(c.primary.copy(alpha = 0.12f + 0.25f * e), base * (0.5f + 0.1f * e), center)
        if (!reduceMotion) {
            val r = base * (0.5f + 0.5f * pulse)
            drawCircle(c.primary.copy(alpha = (0.35f + 0.4f * e) * (1f - pulse)), r, center, style = Stroke((1.5f + 2f * e).dp.toPx()))
        }
        if (e > 0f) {
            // Progress arc around the outer ring for the palm hold.
            val stroke = 3.dp.toPx()
            drawArc(
                c.primary,
                startAngle = -90f,
                sweepAngle = 360f * e,
                useCenter = false,
                topLeft = Offset(center.x - base * 0.98f, center.y - base * 0.98f),
                size = Size(base * 1.96f, base * 1.96f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            repeat(8) { i ->
                val angle = (i / 8f) * 2 * PI + pulse * 2
                val d = base * (0.6f + 0.35f * e)
                drawCircle(
                    c.primarySoft.copy(alpha = 0.5f * e),
                    2.dp.toPx(),
                    Offset(center.x + (cos(angle) * d).toFloat(), center.y + (sin(angle) * d).toFloat()),
                )
            }
        }
    }
}
