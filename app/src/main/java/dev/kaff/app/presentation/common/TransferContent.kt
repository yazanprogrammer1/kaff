package dev.kaff.app.presentation.common

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.core.util.formatNumber
import dev.kaff.app.core.util.speedText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One file line on the transfer screen. */
@Immutable
data class TransferRow(
    val name: String,
    val size: Long,
    val kind: FileKind,
    val image: ImageBitmap?,
    val bytes: Long,
    val done: Boolean,
    val skipped: Boolean = false,
)

/** Elapsed time since [startedAtMs] (elapsedRealtime base), refreshed twice a second. */
@Composable
private fun rememberElapsed(startedAtMs: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return (now - startedAtMs).coerceAtLeast(1)
}

/**
 * Shared by both phones: overall ring, speed, time left, and a row per file. The sender and the
 * receiver see the same numbers, from their own side of the link.
 */
@Composable
fun TransferContent(
    sending: Boolean,
    rows: List<TransferRow>,
    totalBytes: Long,
    startedAtMs: Long,
    onCancelRemaining: () -> Unit,
    modifier: Modifier = Modifier,
    cancelling: Boolean = false,
) {
    val c = KaffTheme.colors
    val bytes = rows.sumOf { it.bytes.coerceAtMost(it.size) }.coerceAtMost(totalBytes)
    val progress = if (totalBytes <= 0) 0f else bytes.toFloat() / totalBytes
    val animated by animateFloatAsState(progress, label = "overall")
    val elapsed = rememberElapsed(startedAtMs)
    val rate = bytes * 1000.0 / elapsed
    val doneCount = rows.count { it.done }
    val verifying = !cancelling && progress >= 0.999f && doneCount < rows.size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (sending) R.string.transfer_sending else R.string.transfer_receiving),
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            if (bytes > 0) StatusPill(speedText(rate), icon = KaffIcons.Bolt)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val percent = (progress * 100).roundToInt()
            Box(
                Modifier
                    .size(200.dp)
                    .semantics {
                        contentDescription = "$percent%"
                        liveRegion = LiveRegionMode.Polite
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(200.dp)) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arc = Size(size.width - stroke, size.height - stroke)
                    drawCircle(Brush.radialGradient(listOf(c.glow, c.background.copy(alpha = 0f))), size.minDimension / 2)
                    drawArc(c.text.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(c.primary, -90f, 360f * animated, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.transfer_percent, formatNumber(percent.toDouble(), 0)),
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp, fontWeight = FontWeight.ExtraBold),
                        color = c.text,
                    )
                    Text(
                        stringResource(R.string.transfer_items_progress, formatNumber(doneCount.toDouble(), 0), itemCount(rows.size)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                    )
                }
            }
        }

        Text(
            when {
                cancelling -> stringResource(R.string.transfer_cancelling)
                verifying -> stringResource(R.string.transfer_verifying)
                else -> {
                    val left = if (rate <= 0) null else ceil((totalBytes - bytes) / rate).toInt()
                    stringResource(R.string.transfer_bytes, fileSizeText(bytes), fileSizeText(totalBytes)) + " · " +
                        (if (left == null || elapsed < 1200) stringResource(R.string.transfer_eta_calc) else pluralStringResource(R.plurals.transfer_eta, left, left))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            itemsIndexed(rows, key = { index, _ -> index }) { _, row -> FileRowCard(row) }
        }

        Text(
            stringResource(R.string.transfer_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (cancelling) {
            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            SecondaryButton(stringResource(R.string.transfer_cancel_remaining), onCancelRemaining, accent = false, height = 52.dp)
        }
    }
}

@Composable
private fun FileRowCard(row: TransferRow) {
    val c = KaffTheme.colors
    val fraction = if (row.size <= 0) 0f else (row.bytes.toFloat() / row.size).coerceIn(0f, 1f)
    val active = !row.done && !row.skipped && fraction > 0f
    val animated by animateFloatAsState(if (row.done) 1f else fraction, label = "file")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.large)
            .background(c.surface)
            .border(1.dp, if (active) c.primary.copy(alpha = 0.45f) else c.border, KaffShapes.large)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileTile(row.image, row.kind, size = 44.dp, shape = KaffShapes.small, bordered = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (row.skipped) c.textMuted else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(0.01f))
                Text(fileSizeText(row.size), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(KaffShapes.pill)
                    .background(c.text.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animated)
                        .clip(KaffShapes.pill)
                        .background(if (row.skipped) c.textMuted else c.primary),
                )
            }
        }
        Box(Modifier.size(width = 40.dp, height = 24.dp), contentAlignment = Alignment.Center) {
            when {
                row.done -> Icon(KaffIcons.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
                active -> Text(
                    stringResource(R.string.transfer_percent, formatNumber((fraction * 100).toDouble(), 0)),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.primarySoft,
                )
            }
        }
    }
}
