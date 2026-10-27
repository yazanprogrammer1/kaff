package dev.kaff.app.presentation.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kaff.app.R
import dev.kaff.app.core.util.KaffPermissions
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
    /** False while the camera is intentionally off (e.g. still searching): no "tap to wake". */
    wakeable: Boolean = true,
) {
    val c = KaffTheme.colors
    val frame = state.snapshot.frame
    val stable = state.snapshot.stable.state
    val context = LocalContext.current
    var cameraAllowed by remember { mutableStateOf(KaffPermissions.granted(context, Manifest.permission.CAMERA)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { cameraAllowed = KaffPermissions.granted(context, Manifest.permission.CAMERA) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
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
        if (!cameraAllowed && !LocalInspectionMode.current) {
            // A "this time only" grant can expire between screens; ask again instead of a dead camera.
            CameraPermissionPrompt { askCamera.launch(Manifest.permission.CAMERA) }
        } else if (state.active) {
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
            val glow = glowState != null && stable == glowState
            if (frame != null && showPreview) {
                HandSkeleton(
                    landmarks = frame.landmarks,
                    imageAspect = frame.imageWidth.toFloat() / frame.imageHeight,
                    glow = glow,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (frame != null) {
                // No preview to line up with: draw the hand framed and steady, like the design.
                val (framed, aspect) = HandPoses.framed(frame.landmarks, frame.imageWidth.toFloat() / frame.imageHeight)
                HandSkeleton(
                    landmarks = framed,
                    imageAspect = aspect,
                    fit = true,
                    glow = glow,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                )
            } else if (idlePose != null) {
                HandSkeleton(
                    landmarks = idlePose,
                    imageAspect = HandPoses.ASPECT,
                    fit = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (showPreview) 40.dp else 18.dp)
                        .alpha(if (showPreview) 0.35f else 0.55f),
                )
            }
        } else if (showPreview) {
            CameraAsleep(onWake)
        } else {
            SkeletonAsleep(idlePose, onWake, wakeable)
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
private fun CameraPermissionPrompt(onAllow: () -> Unit) {
    val c = KaffTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .clickable(role = Role.Button, onClick = onAllow)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(KaffIcons.Camera, contentDescription = null, tint = c.primary, modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.camera_permission_needed), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
        Text(stringResource(R.string.camera_permission_allow), style = MaterialTheme.typography.labelMedium, color = c.primarySoft)
    }
}

/** Skeleton-only stages stay calm when the camera is off: a faint hand and a small wake pill. */
@Composable
private fun SkeletonAsleep(idlePose: List<Landmark>?, onWake: () -> Unit, wakeable: Boolean) {
    val c = KaffTheme.colors
    val label = stringResource(R.string.camera_wake)
    Box(
        Modifier
            .fillMaxSize()
            .then(if (wakeable) Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onWake) else Modifier),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (idlePose != null) {
            HandSkeleton(
                landmarks = idlePose,
                imageAspect = HandPoses.ASPECT,
                fit = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .alpha(if (wakeable) 0.3f else 0.5f),
            )
        }
        if (wakeable) {
            Row(
                Modifier
                    .clip(KaffShapes.pill)
                    .background(c.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KaffIcons.Camera, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = c.primarySoft)
            }
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
