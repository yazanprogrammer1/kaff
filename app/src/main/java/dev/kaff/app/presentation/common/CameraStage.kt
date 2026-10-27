package dev.kaff.app.presentation.common

import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kaff.app.R
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.Landmark
import dev.kaff.app.ui.components.HandCameraPreview
import dev.kaff.app.ui.components.HandPoses
import dev.kaff.app.ui.components.HandSkeleton
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

/**
 * Camera "portal": the dimmed live preview clipped to [shape], the live hand skeleton on top, and
 * a faint illustrated [idlePose] when no hand is visible. When the camera sleeps, the portal shows
 * a tap-to-wake state instead of the preview.
 */
@Composable
fun CameraStage(
    analyzer: ImageAnalysis.Analyzer,
    state: GestureCameraState,
    onWake: () -> Unit,
    onCameraSelected: (frontFacing: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = KaffShapes.hero,
    idlePose: List<Landmark>? = HandPoses.OPEN,
    glowState: GestureState? = null,
    previewAlpha: Float = 0.55f,
    showPreview: Boolean = true,
) {
    val c = KaffTheme.colors
    val frame = state.snapshot.frame
    val stable = state.snapshot.stable.state
    Box(
        if (showPreview) {
            modifier
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.primary.copy(alpha = 0.25f), shape)
        } else {
            modifier.clip(shape)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (state.active) {
            if (!LocalInspectionMode.current) {
                HandCameraPreview(
                    analyzer = analyzer,
                    active = true,
                    showPreview = showPreview,
                    onCameraSelected = onCameraSelected,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(previewAlpha),
                )
            }
            if (showPreview) {
                // Green tint so the preview reads as part of the brand surface, not a raw viewfinder.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(c.backgroundDeep.copy(alpha = 0.35f), c.glow.copy(alpha = 0.35f), c.background.copy(alpha = 0.6f)),
                            ),
                        ),
                )
            }
            if (frame != null) {
                HandSkeleton(
                    landmarks = frame.landmarks,
                    imageAspect = frame.imageWidth.toFloat() / frame.imageHeight,
                    glow = glowState != null && stable == glowState,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (idlePose != null) {
                HandSkeleton(
                    landmarks = idlePose,
                    imageAspect = HandPoses.ASPECT,
                    fit = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(40.dp)
                        .alpha(0.35f),
                )
            }
        } else {
            CameraAsleep(onWake)
        }

        AnimatedVisibility(
            visible = state.active && (state.thermalPaused || state.trackerFailed),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        ) {
            Text(
                stringResource(if (state.trackerFailed) R.string.camera_failed else R.string.camera_thermal),
                style = MaterialTheme.typography.labelSmall,
                color = c.textSubtle,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(KaffShapes.pill)
                    .background(c.background.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CameraAsleep(onWake: () -> Unit) {
    val c = KaffTheme.colors
    val label = stringResource(R.string.camera_wake)
    Column(
        Modifier
            .fillMaxSize()
            .clickable(onClickLabel = label, role = Role.Button, onClick = onWake)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(KaffIcons.Camera, contentDescription = null, tint = c.primary, modifier = Modifier.size(36.dp))
        Text(
            stringResource(R.string.camera_asleep),
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
            textAlign = TextAlign.Center,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.primarySoft)
    }
}
