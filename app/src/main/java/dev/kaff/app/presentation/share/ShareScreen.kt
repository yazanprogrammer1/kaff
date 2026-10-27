package dev.kaff.app.presentation.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.PickSource
import dev.kaff.app.presentation.common.kindCount
import dev.kaff.app.ui.components.FileStack
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HeroText
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.components.SummaryChips
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion

@Composable
fun ShareRoute(
    onClose: () -> Unit,
    onToPhone: () -> Unit,
    onToDesktop: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Nothing usable was shared (or it was already handed on): there is nothing to choose.
    LaunchedEffect(state) { if (!state.loading && state.items.isEmpty()) onClose() }
    ShareScreen(
        state = state,
        onClose = onClose,
        onToPhone = {
            viewModel.fillBasket()
            onToPhone()
        },
        onToDesktop = {
            viewModel.fillBasket()
            onToDesktop()
        },
    )
}

@Composable
fun ShareScreen(state: ShareUiState, onClose: () -> Unit, onToPhone: () -> Unit, onToDesktop: () -> Unit) {
    val items = state.items
    KaffBackground(glowCenterY = 0.24f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                GlassIconButton(KaffIcons.Close, stringResource(R.string.action_close), onClose)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                FileStack(
                    cards = items.take(3).map { StackCard(rememberThumbnail(if (it.source != PickSource.App) it.uri else null), it.kind) },
                    count = items.size,
                    size = 128.dp,
                    glow = true,
                )
                HeroText(stringResource(R.string.share_title), stringResource(R.string.share_body))
                if (items.isNotEmpty()) {
                    SummaryChips(
                        labels = items.groupBy { it.kind }.map { (kind, list) -> kindCount(kind, list.size) },
                        total = fileSizeText(items.sumOf { it.size }),
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Appear(0) {
                    DestinationCard(
                        icon = KaffIcons.Hand,
                        title = stringResource(R.string.share_to_phone),
                        body = stringResource(R.string.share_to_phone_body),
                        primary = true,
                        enabled = items.isNotEmpty(),
                        onClick = onToPhone,
                    )
                }
                Appear(1) {
                    DestinationCard(
                        icon = KaffIcons.Desktop,
                        title = stringResource(R.string.share_to_desktop),
                        body = stringResource(R.string.share_to_desktop_body),
                        primary = false,
                        enabled = items.isNotEmpty(),
                        onClick = onToDesktop,
                    )
                }
            }
        }
    }
}

/** Staggered rise-in for the two choices. */
@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    val reduceMotion = LocalReduceMotion.current
    var visible by remember { mutableStateOf(reduceMotion) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(380, delayMillis = 90 * index)) + slideInVertically(tween(420, delayMillis = 90 * index)) { it / 3 },
    ) { content() }
}

@Composable
private fun DestinationCard(icon: ImageVector, title: String, body: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .then(if (primary) Modifier.shadow(22.dp, KaffShapes.card, ambientColor = c.primary, spotColor = c.primary) else Modifier)
            .clip(KaffShapes.card)
            .background(if (primary) c.primary else c.surface)
            .border(1.dp, if (primary) c.primary else c.primary.copy(alpha = 0.3f), KaffShapes.card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val fg = if (primary) c.onPrimary else c.text
        Box(
            Modifier
                .size(56.dp)
                .clip(KaffShapes.medium)
                .background(if (primary) c.onPrimary.copy(alpha = 0.12f) else c.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (primary) c.onPrimary else c.primary, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = if (primary) c.onPrimary.copy(alpha = 0.75f) else c.textMuted)
        }
        Icon(KaffIcons.ArrowForward, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
    }
}

@Preview(name = "Share · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun SharePreview() {
    val items = List(4) { PickItem("content://x/$it", "IMG_$it.jpg", 2_400_000L, "image/jpeg", PickSource.Media) }
    KaffTheme(darkTheme = true) { ShareScreen(ShareUiState(false, items), {}, {}, {}) }
}
