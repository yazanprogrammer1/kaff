package dev.kaff.app.presentation.lab

import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.domain.gesture.GestureSnapshot
import dev.kaff.app.domain.model.GestureReading
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.Handedness
import dev.kaff.app.domain.model.StableGesture
import dev.kaff.app.presentation.common.CameraStage
import dev.kaff.app.presentation.common.GestureCameraState
import dev.kaff.app.ui.components.BackgroundStyle
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.KaffTopBar
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import kotlin.math.roundToInt

@Composable
fun GestureLabRoute(onBack: () -> Unit, viewModel: GestureLabViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GestureLabScreen(
        state = state,
        analyzer = viewModel.analyzer,
        onBack = onBack,
        onWake = viewModel::onWake,
        onCameraSelected = viewModel::onCameraSelected,
    )
}

@Composable
fun GestureLabScreen(
    state: GestureCameraState,
    analyzer: ImageAnalysis.Analyzer,
    onBack: () -> Unit,
    onWake: () -> Unit,
    onCameraSelected: (Boolean) -> Unit,
) {
    val c = KaffTheme.colors
    KaffBackground(style = BackgroundStyle.Camera) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KaffTopBar(
                navigationIcon = KaffIcons.ArrowBack,
                navigationDescription = stringResource(R.string.action_back),
                onNavigate = onBack,
                title = stringResource(R.string.lab_title),
            ) {
                StatusPill(
                    text = stringResource(R.string.lab_fps_value, state.fps) + " " + stringResource(R.string.lab_fps),
                    tone = if (state.batterySaver) PillTone.Neutral else PillTone.Accent,
                )
            }
            CameraStage(
                analyzer = analyzer,
                state = state,
                onWake = onWake,
                onCameraSelected = onCameraSelected,
                glowState = state.snapshot.stable.state.takeIf { it != GestureState.None },
                previewAlpha = 0.85f,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f),
            )
            LabStats(state.snapshot)
            if (state.batterySaver) {
                StatusPill(stringResource(R.string.camera_battery_saver), tone = PillTone.Neutral, icon = KaffIcons.Bolt)
            }
            Text(stringResource(R.string.lab_hint), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
    }
}

@Composable
private fun LabStats(snapshot: GestureSnapshot) {
    val c = KaffTheme.colors
    val stable = snapshot.stable
    KaffCard(highlighted = stable.state != GestureState.None) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lab_stable), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(
                    gestureLabel(stable.state, snapshot.frame != null),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (stable.state == GestureState.None) c.text else c.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.lab_raw), style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Text(
                    gestureLabel(snapshot.reading.state, snapshot.frame != null),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textSubtle,
                )
            }
        }
        ScoreRow(stringResource(R.string.lab_confidence), stable.confidence)
        ScoreRow(stringResource(R.string.lab_fist_score), snapshot.reading.fistScore)
        ScoreRow(stringResource(R.string.lab_open_score), snapshot.reading.openScore)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val hand = snapshot.frame?.handedness
            if (hand != null) {
                StatusPill(
                    stringResource(R.string.lab_hand) + ": " +
                        stringResource(if (hand == Handedness.Left) R.string.lab_hand_left else R.string.lab_hand_right),
                    tone = PillTone.Neutral,
                    dot = false,
                )
                StatusPill(
                    stringResource(R.string.lab_palm_facing) + ": " +
                        stringResource(if (snapshot.reading.palmFacing) R.string.action_yes else R.string.action_no),
                    tone = if (snapshot.reading.palmFacing) PillTone.Accent else PillTone.Neutral,
                    dot = false,
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, value: Float) {
    val c = KaffTheme.colors
    val animated by animateFloatAsState(value.coerceIn(0f, 1f), label = "score")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSubtle, modifier = Modifier.weight(0.3f))
        Box(
            Modifier
                .weight(0.55f)
                .height(6.dp)
                .clip(KaffShapes.pill)
                .background(c.text.copy(alpha = 0.1f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(KaffShapes.pill)
                    .background(if (value >= 0.7f) c.primary else c.primarySoft.copy(alpha = 0.6f)),
            )
        }
        Text(
            stringResource(R.string.lab_percent, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            color = c.textMuted,
            modifier = Modifier.weight(0.15f),
        )
    }
}

@Composable
private fun gestureLabel(state: GestureState, handVisible: Boolean): String = stringResource(
    when (state) {
        GestureState.Fist -> R.string.lab_gesture_fist
        GestureState.OpenPalm -> R.string.lab_gesture_open
        GestureState.None -> if (handVisible) R.string.lab_gesture_none else R.string.lab_no_hand
    },
)

@Preview(name = "Lab · RTL", locale = "ar", showBackground = true, backgroundColor = 0xFF07100C)
@Preview(name = "Lab · EN", locale = "en", showBackground = true, backgroundColor = 0xFF07100C)
@Composable
private fun GestureLabPreview() {
    KaffTheme(darkTheme = true) {
        GestureLabScreen(
            state = GestureCameraState(
                fps = 29f,
                snapshot = GestureSnapshot(
                    frame = null,
                    reading = GestureReading(GestureState.Fist, 0.93f, fistScore = 0.93f, openScore = 0.04f),
                    stable = StableGesture(GestureState.Fist, 0.91f, 0L, false),
                ),
            ),
            analyzer = { it.close() },
            onBack = {},
            onWake = {},
            onCameraSelected = {},
        )
    }
}
