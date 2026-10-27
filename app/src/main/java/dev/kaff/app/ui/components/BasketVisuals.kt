package dev.kaff.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kaff.app.core.util.formatNumber
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

/** One card of a basket stack. */
@Immutable
data class StackCard(val image: ImageBitmap?, val kind: FileKind)

/**
 * The basket as a fanned stack of cards: the first item in front, up to two more behind it, and a
 * count badge when there is more than one item.
 */
@Composable
fun FileStack(cards: List<StackCard>, count: Int, modifier: Modifier = Modifier, size: Dp = 150.dp, glow: Boolean = false) {
    val c = KaffTheme.colors
    val shape = RoundedCornerShape(size * 0.15f)
    Box(modifier.size(width = size * 1.55f, height = size * 1.2f), contentAlignment = Alignment.Center) {
        // Back cards: absolute left/right so the fan looks the same in RTL and LTR.
        cards.getOrNull(2)?.let { card ->
            FileTile(card.image, card.kind, size = size * 0.9f, shape = shape, borderColor = c.primary.copy(alpha = 0.7f), modifier = Modifier.offset(x = size * 0.3f, y = size * 0.06f).rotate(8f))
        }
        cards.getOrNull(1)?.let { card ->
            FileTile(card.image, card.kind, size = size * 0.9f, shape = shape, borderColor = c.primary.copy(alpha = 0.7f), modifier = Modifier.offset(x = -size * 0.3f, y = size * 0.08f).rotate(-8f))
        }
        val front = cards.firstOrNull()
        Box {
            FileTile(
                front?.image,
                front?.kind ?: FileKind.Other,
                size = size,
                shape = shape,
                modifier = Modifier
                    .rotate(-3f)
                    .shadow(if (glow) 34.dp else 16.dp, shape, ambientColor = c.primary, spotColor = c.primary),
            )
            if (count > 1) {
                Box(
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .offset(x = -size * 0.12f, y = -size * 0.1f)
                        .size(size * 0.34f)
                        .clip(CircleShape)
                        .background(c.primary)
                        .border(4.dp, c.background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatNumber(count.toDouble(), 0), style = MaterialTheme.typography.titleLarge, color = c.onPrimary)
                }
            }
        }
    }
}

/** Three small overlapping tiles, used in the basket bar and the offer card. */
@Composable
fun MiniStack(cards: List<StackCard>, modifier: Modifier = Modifier, tile: Dp = 44.dp) {
    val c = KaffTheme.colors
    val shown = cards.take(3)
    val angles = listOf(-8f, -2f, 6f)
    Box(modifier.size(width = tile + tile * 0.7f, height = tile + 8.dp), contentAlignment = Alignment.CenterStart) {
        shown.asReversed().forEachIndexed { reversedIndex, card ->
            val i = shown.lastIndex - reversedIndex
            FileTile(
                card.image,
                card.kind,
                size = tile,
                shape = KaffShapes.small,
                borderColor = c.surface,
                modifier = Modifier
                    .padding(start = tile * 0.35f * i)
                    .rotate(angles[i]),
            )
        }
    }
}

/** Content chips: one per kind ("3 photos") plus the total size, highlighted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryChips(labels: List<String>, total: String, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { Chip(it, accent = false) }
        Chip(total, accent = true)
    }
}

@Composable
private fun Chip(text: String, accent: Boolean) {
    val c = KaffTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (accent) c.primarySoft else c.textSubtle,
        maxLines = 1,
        modifier = Modifier
            .clip(KaffShapes.medium)
            .background(if (accent) c.primary.copy(alpha = 0.12f) else c.surface)
            .border(1.dp, if (accent) c.primary.copy(alpha = 0.35f) else c.border, KaffShapes.medium)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
