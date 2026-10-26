package dev.kaff.app.presentation.send

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.durationText
import dev.kaff.app.core.util.speedText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.presentation.common.CameraStage
import dev.kaff.app.presentation.common.message
import dev.kaff.app.ui.animation.CountdownRing
import dev.kaff.app.ui.animation.ParticleTrail
import dev.kaff.app.ui.animation.rememberRemainingMs
import dev.kaff.app.ui.components.BackgroundStyle
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HandPoses
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.components.rememberJpeg
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt

@Composable
fun SendRoute(onClose: () -> Unit, viewModel: SendViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(SendViewModel.MAX_FILES),
    ) { uris -> viewModel.onFilesPicked(uris.map { it.toString() }) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onFilesPicked(uris.map { it.toString() })
    }
    SendScreen(
        state = state,
        analyzer = viewModel.analyzer,
        actions = SendActions(
            onClose = onClose,
            onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
            onManualSend = viewModel::onManualSend,
            onChangeFiles = viewModel::onChangeFiles,
            onSelectPeer = viewModel::onSelectPeer,
            onRetry = viewModel::onRetry,
            onSendAnother = viewModel::onSendAnother,
            onWakeCamera = viewModel::onWakeCamera,
            onCameraSelected = viewModel::onCameraSelected,
        ),
    )
}

data class SendActions(
    val onClose: () -> Unit = {},
    val onPickMedia: () -> Unit = {},
    val onPickFiles: () -> Unit = {},
    val onManualSend: () -> Unit = {},
    val onChangeFiles: () -> Unit = {},
    val onSelectPeer: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onSendAnother: () -> Unit = {},
    val onWakeCamera: () -> Unit = {},
    val onCameraSelected: (Boolean) -> Unit = {},
)

@Composable
fun SendScreen(state: SendUiState, analyzer: ImageAnalysis.Analyzer, actions: SendActions) {
    KaffBackground(style = BackgroundStyle.Camera) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(KaffIcons.Close, stringResource(R.string.action_close), actions.onClose)
                Spacer(Modifier.weight(1f))
                SendStatusPill(state)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.session is SendState.Completed -> Delivered(state.files, state.session)
                    state.files.isEmpty() -> PickFiles(state.preparing, state.fileError, actions)
                    else -> GrabStage(state, analyzer, actions)
                }
            }
            BottomPanel(state, actions)
        }
    }
}

@Composable
private fun SendStatusPill(state: SendUiState) {
    val session = state.session
    val grabbed = session is SendState.Armed || session is SendState.Transferring || session is SendState.AwaitingReceipt
    when {
        grabbed -> StatusPill(
            stringResource(
                when (state.files.firstOrNull()?.meta?.kind) {
                    FileKind.Image -> R.string.send_grabbed_image
                    FileKind.Video -> R.string.send_grabbed_video
                    else -> R.string.send_grabbed_file
                },
            ),
            glowingDot = true,
        )
        state.camera.batterySaver && state.files.isNotEmpty() ->
            StatusPill(stringResource(R.string.camera_battery_saver), tone = PillTone.Neutral, icon = KaffIcons.Bolt)
        session is SendState.Ready -> StatusPill(stringResource(R.string.send_connected))
        else -> Unit
    }
}

@Composable
private fun PickFiles(preparing: Boolean, fileError: Boolean, actions: SendActions) {
    val c = KaffTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (preparing) {
            CircularProgressIndicator(color = c.primary)
            Text(stringResource(R.string.send_preparing), style = MaterialTheme.typography.bodyLarge, color = c.textMuted)
            return@Column
        }
        IconBadge(KaffIcons.Hand, size = 72.dp)
        Text(stringResource(R.string.send_pick_title), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.send_pick_body), style = MaterialTheme.typography.bodyLarge, color = c.textMuted, textAlign = TextAlign.Center)
        if (fileError) StatusPill(stringResource(R.string.error_file_unreadable), tone = PillTone.Danger, icon = KaffIcons.Alert)
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.send_pick_media), actions.onPickMedia, trailingIcon = KaffIcons.Image)
        SecondaryButton(stringResource(R.string.send_pick_files), actions.onPickFiles)
    }
}

/** The file floats above the camera portal and lifts off with a spring when the fist lands. */
@Composable
private fun GrabStage(state: SendUiState, analyzer: ImageAnalysis.Analyzer, actions: SendActions) {
    val c = KaffTheme.colors
    val session = state.session
    val lifted = session is SendState.Armed || session is SendState.Transferring || session is SendState.AwaitingReceipt
    val reduceMotion = LocalReduceMotion.current
    val springSpec = if (reduceMotion) spring<Float>(stiffness = Spring.StiffnessHigh) else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
    val lift by animateDpAsState(if (lifted) (-28).dp else 0.dp, if (reduceMotion) spring() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "lift")
    val scale by animateFloatAsState(if (lifted) 1.08f else 0.94f, springSpec, label = "scale")
    val rotation by animateFloatAsState(if (lifted) -5f else 0f, springSpec, label = "tilt")
    val first = state.files.first()
    val snapshot = state.camera.snapshot

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopCenter) {
            CameraStage(
                analyzer = analyzer,
                state = state.camera,
                onWake = actions.onWakeCamera,
                onCameraSelected = actions.onCameraSelected,
                idlePose = HandPoses.FIST,
                glowState = GestureState.Fist,
                shape = CircleShape,
                modifier = Modifier
                    .padding(top = 96.dp)
                    .size(250.dp),
            )
            ParticleTrail(lifted, Modifier.size(width = 200.dp, height = 260.dp).padding(top = 60.dp))
            Box(
                Modifier
                    .offset { IntOffset(0, lift.roundToPx()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    },
            ) {
                FileTile(
                    image = rememberJpeg(first.thumbnailJpeg),
                    kind = first.meta.kind,
                    size = 150.dp,
                    modifier = Modifier.shadow(if (lifted) 32.dp else 12.dp, KaffShapes.hero, ambientColor = c.primary, spotColor = c.primary),
                )
                if (state.files.size > 1) {
                    Text(
                        "+${state.files.size - 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 8.dp, y = (-8).dp)
                            .clip(KaffShapes.pill)
                            .background(c.primary)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        val confidence = (snapshot.stable.confidence * 100).roundToInt()
        Text(
            when {
                snapshot.stable.state == GestureState.Fist -> stringResource(R.string.gesture_fist_confidence, confidence)
                snapshot.frame != null -> stringResource(R.string.lab_gesture_none)
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.primarySoft,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!lifted) {
            TextButton(onClick = actions.onChangeFiles, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.send_change_files), color = c.textSubtle, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Delivered(files: List<OutgoingFile>, session: SendState.Completed) {
    val c = KaffTheme.colors
    val first = files.firstOrNull()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box {
            FileTile(rememberJpeg(first?.thumbnailJpeg), first?.meta?.kind ?: FileKind.Other, size = 150.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 14.dp, y = 14.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(c.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(KaffIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(24.dp)) }
        }
        Text(stringResource(R.string.send_done_title), style = MaterialTheme.typography.displaySmall, color = c.text)
        Text(
            stringResource(R.string.send_done_body, session.peer.name, speedText(session.stats.bytesPerSecond)) +
                " · " + durationText(session.stats.durationMs),
            style = MaterialTheme.typography.bodyLarge,
            color = c.textMuted,
        )
    }
}

@Composable
private fun BottomPanel(state: SendUiState, actions: SendActions) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        AnimatedContent(
            targetState = state.session,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sendPanel",
        ) { session ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (session) {
                    SendState.Idle -> Unit
                    is SendState.Searching -> {
                        PeerCard(title = stringResource(R.string.send_searching), subtitle = stringResource(R.string.send_searching_hint), busy = true)
                        session.notice?.let { Notice(it) }
                    }
                    is SendState.Connecting -> {
                        PeerCard(stringResource(R.string.send_peer_connecting), stringResource(R.string.send_peer_subtitle, session.peer.name), busy = true)
                        OtherPeers(session.peers.filterNot { it == session.peer }, actions.onSelectPeer)
                    }
                    is SendState.AwaitingPairing -> PeerCard(
                        stringResource(R.string.send_peer_pairing),
                        stringResource(R.string.send_pairing_code, session.authDigits),
                        busy = true,
                        digits = session.authDigits,
                    )
                    is SendState.Ready -> {
                        PeerCard(stringResource(R.string.send_peer_ready), stringResource(R.string.send_peer_subtitle, session.peer.name), connected = true)
                        session.notice?.let { Notice(it) }
                        if (state.files.isNotEmpty()) {
                            Instruction(stringResource(R.string.send_make_fist), stringResource(R.string.send_make_fist_hint))
                            TextButton(onClick = actions.onManualSend, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                                Text(stringResource(R.string.send_manual), color = c.primarySoft, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    is SendState.Armed -> {
                        PeerCard(stringResource(R.string.send_peer_ready), stringResource(R.string.send_peer_subtitle, session.peer.name), connected = true)
                        ArmedCountdown(session)
                    }
                    is SendState.Transferring -> Progress(stringResource(R.string.send_sending, (session.progress * 100).roundToInt()), session.progress)
                    is SendState.AwaitingReceipt -> Progress(stringResource(R.string.send_waiting_receipt), null)
                    is SendState.Completed -> PrimaryButton(stringResource(R.string.send_again), actions.onSendAnother)
                    is SendState.Failed -> {
                        Notice(session.error)
                        PrimaryButton(stringResource(R.string.action_retry), actions.onRetry, trailingIcon = KaffIcons.Refresh)
                    }
                }
            }
        }
        if (state.session !is SendState.Completed) {
            SecondaryButton(stringResource(R.string.action_cancel), actions.onClose, accent = false, height = 52.dp)
        }
    }
}

@Composable
private fun PeerCard(
    title: String,
    subtitle: String,
    busy: Boolean = false,
    connected: Boolean = false,
    digits: String? = null,
) {
    val c = KaffTheme.colors
    KaffCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(KaffIcons.Phone)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            when {
                connected -> StatusPill(stringResource(R.string.send_connected), dot = false)
                digits != null -> Text(digits, style = MaterialTheme.typography.headlineSmall, color = c.primary)
                busy -> CircularProgressIndicator(color = c.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun OtherPeers(peers: List<Peer>, onSelect: (String) -> Unit) {
    if (peers.isEmpty()) return
    val c = KaffTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        peers.take(3).forEach { peer ->
            Text(
                peer.name,
                style = MaterialTheme.typography.labelMedium,
                color = c.textSubtle,
                maxLines = 1,
                modifier = Modifier
                    .clip(KaffShapes.pill)
                    .background(c.surface)
                    .clickable { onSelect(peer.endpointId) }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun ArmedCountdown(session: SendState.Armed) {
    val c = KaffTheme.colors
    val remaining = rememberRemainingMs(session.armedAtMs + session.windowMs)
    val seconds = ((remaining + 999) / 1000).toInt()
    val hint = pluralStringResource(R.plurals.send_countdown, seconds, seconds)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CountdownRing(remaining, session.windowMs, contentDescription = hint)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.send_open_hand_other), style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
    }
}

@Composable
private fun Instruction(title: String, body: String) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.text)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
    }
}

@Composable
private fun Progress(label: String, progress: Float?) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = c.text)
        val barModifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(KaffShapes.pill)
        if (progress == null) {
            LinearProgressIndicator(modifier = barModifier, color = c.primary, trackColor = c.text.copy(alpha = 0.1f))
        } else {
            val animated by animateFloatAsState(progress, label = "progress")
            LinearProgressIndicator(
                progress = { animated },
                modifier = barModifier,
                color = c.primary,
                trackColor = c.text.copy(alpha = 0.1f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun Notice(error: TransferError) {
    StatusPill(stringResource(error.message()), tone = PillTone.Danger, icon = KaffIcons.Alert)
}

private val previewFile = OutgoingFile(
    "content://preview/IMG_2841.jpg",
    FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "00"),
    null,
)
private val previewPeer = Peer("B", "Samsung A54")

@Preview(name = "Send · armed · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendArmedPreview() {
    KaffTheme(darkTheme = true) {
        SendScreen(
            SendUiState(
                session = SendState.Armed(previewPeer, "4721", "t", SystemClock.elapsedRealtime(), 5_000),
                files = listOf(previewFile),
            ),
            analyzer = { it.close() },
            actions = SendActions(),
        )
    }
}

@Preview(name = "Send · pick · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendPickPreview() {
    KaffTheme(darkTheme = true) {
        SendScreen(SendUiState(session = SendState.Searching()), analyzer = { it.close() }, actions = SendActions())
    }
}

@Preview(name = "Send · pairing · light", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendPairingPreview() {
    KaffTheme(darkTheme = false) {
        SendScreen(
            SendUiState(session = SendState.AwaitingPairing(previewPeer, "4721"), files = listOf(previewFile, previewFile)),
            analyzer = { it.close() },
            actions = SendActions(),
        )
    }
}
