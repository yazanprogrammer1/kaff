package dev.kaff.app.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kaff.app.ui.animation.PulseRings
import dev.kaff.app.ui.components.FileStack
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion

/** A line in the "where did it go" list, e.g. "3 photos · Pictures / Kaff". */
@Immutable
data class DoneGroup(val title: String, val subtitle: String, val onClick: (() -> Unit)? = null)

/**
 * The success hero shared by both phones: the stack materializes (scale + blur to sharp), a check
 * lands on it, then the title, the numbers and the grouped destinations.
 */
@Composable
fun DoneSummary(
    cards: List<StackCard>,
    count: Int,
    title: String,
    meta: String,
    groups: List<DoneGroup>,
    modifier: Modifier = Modifier,
    note: String? = null,
) {
    val c = KaffTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Materialize {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                PulseRings(0f, Modifier.size(250.dp))
                Box {
                    FileStack(cards, count = 1, size = 132.dp, glow = true)
                    Box(
                        Modifier
                            .align(AbsoluteAlignment.BottomRight)
                            .offset(x = (-30).dp, y = (-6).dp)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(c.primary)
                            .border(4.dp, c.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(KaffIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(26.dp)) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = c.text, textAlign = TextAlign.Center)
            Text(meta, style = MaterialTheme.typography.bodyLarge, color = c.textMuted, textAlign = TextAlign.Center)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger.copy(alpha = 0.85f), textAlign = TextAlign.Center)
        }
        if (groups.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(KaffShapes.card)
                    .background(c.surface)
                    .border(1.dp, c.border, KaffShapes.card),
            ) {
                groups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = c.border)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .then(if (group.onClick != null) Modifier.clickable(role = Role.Button, onClick = group.onClick) else Modifier)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(KaffIcons.Folder, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.title, style = MaterialTheme.typography.titleSmall, color = c.text)
                            Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                        }
                        Icon(KaffIcons.Check, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** The received stack "materializes": it scales up while going from blurred to sharp. */
@Composable
private fun Materialize(content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) progress.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessVeryLow))
    }
    val p = progress.value
    Box(
        Modifier
            .graphicsLayer {
                scaleX = 0.8f + 0.2f * p
                scaleY = 0.8f + 0.2f * p
                alpha = p.coerceIn(0f, 1f)
            }
            .blur((24 * (1f - p.coerceIn(0f, 1f))).dp),
    ) { content() }
}
