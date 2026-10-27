package dev.kaff.app.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.ui.animation.HeroHand
import dev.kaff.app.ui.animation.sharedHero
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HeroText
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

@Composable
fun HomeRoute(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDesktop: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onLab: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state, onSend, onReceive, onSettings, onHistory, onLab, onDesktop)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onLab: () -> Unit,
    onDesktop: () -> Unit = {},
) {
    val c = KaffTheme.colors
    KaffBackground {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(KaffShapes.small)
                        .background(c.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(KaffIcons.Hand, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.size(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = c.text)
                Spacer(Modifier.weight(1f))
                GlassIconButton(KaffIcons.Lab, stringResource(R.string.home_lab), onLab)
                Spacer(Modifier.size(8.dp))
                GlassIconButton(KaffIcons.History, stringResource(R.string.home_history), onHistory)
                Spacer(Modifier.size(8.dp))
                GlassIconButton(KaffIcons.Settings, stringResource(R.string.action_settings), onSettings)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StatusPill(
                    stringResource(if (state.standbyOn) R.string.home_status_standby else R.string.home_status_camera_off),
                )
                HeroHand(Modifier.sharedHero("hero-hand"))
                HeroText(stringResource(R.string.home_tagline), stringResource(R.string.home_subtitle))
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WakeChip(KaffIcons.Shake, stringResource(R.string.wake_shake), onSettings, Modifier.weight(1f))
                    WakeChip(KaffIcons.Tile, stringResource(R.string.wake_tile), onSettings, Modifier.weight(1f))
                    WakeChip(KaffIcons.Bubble, stringResource(R.string.wake_bubble), onSettings, Modifier.weight(1f))
                }
                PrimaryButton(stringResource(R.string.home_start_send), onSend, trailingIcon = KaffIcons.ArrowForward)
                SecondaryButton(stringResource(R.string.home_receive), onReceive)
                DesktopLink(onDesktop)
            }
        }
    }
}

/** Quiet third entry: the browser-based transfer with a computer. */
@Composable
private fun DesktopLink(onClick: () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(KaffShapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KaffIcons.Desktop, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.home_desktop), style = MaterialTheme.typography.titleSmall, color = c.primarySoft)
    }
}

@Composable
private fun WakeChip(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = KaffTheme.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(KaffShapes.medium)
            .background(c.surface)
            .border(1.dp, c.border, KaffShapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
        // Shrinks instead of clipping at large font scales ("هز الجوال" must stay readable).
        BasicText(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(color = c.textSubtle, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.bodyMedium.fontSize),
        )
    }
}

@Preview(name = "Home · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Preview(name = "Home · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun HomePreview() {
    KaffTheme(darkTheme = true) { HomeScreen(HomeUiState(), {}, {}, {}, {}, {}) }
}

@Preview(name = "Home · Light", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun HomeLightPreview() {
    KaffTheme(darkTheme = false) { HomeScreen(HomeUiState(standbyOn = true), {}, {}, {}, {}, {}) }
}
