package dev.kaff.app.presentation.send

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.core.util.speedText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.presentation.common.CameraStage
import dev.kaff.app.presentation.common.DoneGroup
import dev.kaff.app.presentation.common.DoneSummary
import dev.kaff.app.presentation.common.RadioErrorPanel
import dev.kaff.app.presentation.common.TransferContent
import dev.kaff.app.presentation.common.TransferRow
import dev.kaff.app.presentation.common.itemCount
import dev.kaff.app.presentation.common.kindCount
import dev.kaff.app.presentation.common.message
import dev.kaff.app.ui.animation.CountdownRing
import dev.kaff.app.ui.animation.ParticleTrail
import dev.kaff.app.ui.animation.rememberRemainingMs
import dev.kaff.app.ui.components.BackgroundStyle
import dev.kaff.app.ui.components.FileStack
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HandPoses
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.components.SummaryChips
import dev.kaff.app.ui.components.rememberJpeg
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt

@Composable
fun SendRoute(
    onClose: () -> Unit,
    onChangeFiles: () -> Unit,
    onNewTransfer: () -> Unit,
    viewModel: SendViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SendScreen(
        state = state,
        analyzer = viewModel.analyzer,
        actions = SendActions(
            onClose = onClose,
            onChangeFiles = onChangeFiles,
            onManualSend = viewModel::onManualSend,
            onSelectPeer = viewModel::onSelectPeer,
            onRetry = viewModel::onRetry,
            onNewTransfer = onNewTransfer,
            onCancelRemaining = viewModel::onCancelRemaining,
            onWakeCamera = viewModel::onWakeCamera,
            onCameraSelected = viewModel::onCameraSelected,
        ),
    )
}

data class SendActions(
    val onClose: () -> Unit = {},
    val onChangeFiles: () -> Unit = {},
    val onManualSend: () -> Unit = {},
    val onSelectPeer: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNewTransfer: () -> Unit = {},
    val onCancelRemaining: () -> Unit = {},
    val onWakeCamera: () -> Unit = {},
    val onCameraSelected: (Boolean) -> Unit = {},
)

@Composable
fun SendScreen(state: SendUiState, analyzer: ImageAnalysis.Analyzer, actions: SendActions) {
    // Keeps per-file rows on screen while "cancel remaining" waits for the receiver's answer.
    var lastTransfer by remember { mutableStateOf<SendState.Transferring?>(null) }
    (state.session as? SendState.Transferring)?.let { lastTransfer = it }

    KaffBackground(style = BackgroundStyle.Camera) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(KaffIcons.Close, stringResource(R.string.action_close), actions.onClose)
                Spacer(Modifier.weight(1f))
                SendStatusPill(state)
            }
            when (val session = state.session) {
                is SendState.Transferring -> TransferContent(
                    sending = true,
                    rows = senderRows(state.files, session),
                    totalBytes = session.totalBytes,
                    startedAtMs = session.startedAtMs,
                    onCancelRemaining = actions.onCancelRemaining,
                    linkQuality = state.linkQuality,
                    modifier = Modifier.weight(1f),
                )
                is SendState.AwaitingReceipt -> TransferContent(
                    sending = true,
                    rows = senderRows(state.files, lastTransfer, allSent = !session.cancelling),
                    totalBytes = session.totalBytes,
                    startedAtMs = session.startedAtMs,
                    onCancelRemaining = actions.onCancelRemaining,
                    cancelling = true,
                    modifier = Modifier.weight(1f),
                )
                is SendState.Completed -> Delivered(state.files, session, actions.onNewTransfer, Modifier.weight(1f))
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GrabStage(state, analyzer, actions)
                    }
                    BottomPanel(state, actions)
                }
            }
        }
    }
}

@Composable
private fun senderRows(files: List<OutgoingFile>, transfer: SendState.Transferring?, allSent: Boolean = false): List<TransferRow> =
    files.mapIndexed { index, file ->
        val done = allSent || transfer?.isDone(index) == true
        TransferRow(
            name = file.meta.name,
            size = file.meta.size,
            kind = file.meta.kind,
            image = rememberJpeg(file.thumbnailJpeg),
            bytes = if (done) file.meta.size else transfer?.bytesOf(index) ?: 0,
            done = done,
        )
    }

@Composable
private fun SendStatusPill(state: SendUiState) {
    val session = state.session
    val grabbed = session is SendState.Armed || session is SendState.Transferring || session is SendState.AwaitingReceipt
    when {
        grabbed -> StatusPill(
            stringResource(
                if (state.files.size > 1) {
                    R.string.send_grabbed_basket
                } else {
                    when (state.files.firstOrNull()?.meta?.kind) {
                        FileKind.Image -> R.string.send_grabbed_image
                        FileKind.Video -> R.string.send_grabbed_video
                        else -> R.string.send_grabbed_file
                    }
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

/** The basket floats above the hand; a confirmed fist lifts it off with a spring and a trail. */
@Composable
private fun GrabStage(state: SendUiState, analyzer: ImageAnalysis.Analyzer, actions: SendActions) {
    val c = KaffTheme.colors
    val lifted = state.session is SendState.Armed
    val reduceMotion = LocalReduceMotion.current
    val bounce = if (reduceMotion) spring<Float>(stiffness = Spring.StiffnessHigh) else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)
    val lift by animateDpAsState(if (lifted) (-26).dp else 0.dp, if (reduceMotion) spring() else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "lift")
    val scale by animateFloatAsState(if (lifted) 1.06f else 0.96f, bounce, label = "scale")
    val snapshot = state.camera.snapshot
    val files = state.files
    val preparing = state.preparing

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            // Soft glow behind the stack, like the design's radial light.
            Canvas(Modifier.size(320.dp)) {
                drawCircle(
                    Brush.radialGradient(listOf(c.primary.copy(alpha = if (lifted) 0.3f else 0.18f), c.primary.copy(alpha = 0f))),
                    radius = size.minDimension / 2,
                    center = Offset(size.width / 2, size.height * 0.42f),
                )
            }
            CameraStage(
                analyzer = analyzer,
                state = state.camera,
                onWake = actions.onWakeCamera,
                onCameraSelected = actions.onCameraSelected,
                idlePose = HandPoses.FIST,
                glowState = GestureState.Fist,
                showPreview = false,
                wakeable = state.session is SendState.Ready || state.session is SendState.Armed,
                modifier = Modifier
                    .padding(top = 170.dp)
                    .size(210.dp),
            )
            ParticleTrail(lifted, Modifier.size(width = 220.dp, height = 300.dp).padding(top = 80.dp))
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .offset { IntOffset(0, lift.roundToPx()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                when {
                    files.isNotEmpty() -> FileStack(
                        cards = files.take(3).map { StackCard(rememberJpeg(it.thumbnailJpeg), it.meta.kind) },
                        count = files.size,
                        size = 132.dp,
                        glow = lifted,
                    )
                    preparing != null -> Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.primary)
                    }
                    else -> Unit
                }
            }
        }
        Text(
            when {
                preparing != null -> stringResource(R.string.send_preparing_basket) + "  ${preparing.first}/${preparing.second}"
                snapshot.stable.state == GestureState.Fist -> stringResource(R.string.gesture_fist_confidence, (snapshot.stable.confidence * 100).roundToInt())
                snapshot.frame != null -> stringResource(R.string.lab_gesture_none)
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.primarySoft,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Delivered(files: List<OutgoingFile>, session: SendState.Completed, onNewTransfer: () -> Unit, modifier: Modifier) {
    val delivered = files.filterIndexed { index, _ -> index !in session.skipped }
    val groups = delivered.groupBy { it.meta.kind }.map { (kind, list) ->
        DoneGroup(kindCount(kind, list.size), session.peer.name)
    }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            StatusPill(stringResource(R.string.done_speed_avg, speedText(session.stats.bytesPerSecond)), icon = KaffIcons.Bolt, dot = false)
        }
        DoneSummary(
            cards = delivered.take(3).map { StackCard(rememberJpeg(it.thumbnailJpeg), it.meta.kind) },
            count = delivered.size,
            title = stringResource(R.string.send_done_count, itemCount(delivered.size)),
            meta = stringResource(R.string.done_meta_short, fileSizeText(session.stats.bytes), durationText(session.stats.durationMs)),
            groups = groups,
            note = session.skipped.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.done_skipped, it, it) },
        )
        PrimaryButton(stringResource(R.string.done_new_transfer), onNewTransfer)
    }
}

@Composable
private fun BottomPanel(state: SendUiState, actions: SendActions) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (state.files.isNotEmpty()) {
            SummaryChips(
                labels = state.files.groupBy { it.meta.kind }.map { (kind, list) -> kindCount(kind, list.size) },
                total = fileSizeText(state.files.sumOf { it.meta.size }),
            )
        }
        if (state.fileError) StatusPill(stringResource(R.string.error_file_unreadable), tone = PillTone.Danger, icon = KaffIcons.Alert)
        AnimatedContent(
            targetState = state.session,
            contentKey = { it::class },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "sendPanel",
        ) { session ->
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (session) {
                    SendState.Idle, is SendState.Transferring, is SendState.AwaitingReceipt, is SendState.Completed -> Unit
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
                        Instruction(
                            stringResource(if (state.files.size > 1) R.string.send_make_fist_basket else R.string.send_make_fist),
                            stringResource(R.string.send_make_fist_hint),
                        )
                    }
                    is SendState.Armed -> {
                        PeerCard(stringResource(R.string.send_peer_ready), stringResource(R.string.send_peer_subtitle, session.peer.name), connected = true)
                        ArmedCountdown(session, basket = state.files.size > 1)
                    }
                    is SendState.Failed -> RadioErrorPanel(session.error, actions.onRetry)
                }
            }
        }
        val session = state.session
        if (session !is SendState.Armed && session !is SendState.Failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = actions.onChangeFiles, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.send_change_files), color = c.textSubtle, style = MaterialTheme.typography.labelMedium)
                }
                if (session is SendState.Ready && state.files.isNotEmpty()) {
                    TextButton(onClick = actions.onManualSend, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.send_manual), color = c.primarySoft, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
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
private fun ArmedCountdown(session: SendState.Armed, basket: Boolean) {
    val c = KaffTheme.colors
    val remaining = rememberRemainingMs(session.armedAtMs + session.windowMs)
    val seconds = ((remaining + 999) / 1000).toInt()
    val hint = pluralStringResource(if (basket) R.plurals.send_drop_hint else R.plurals.send_countdown, seconds, seconds)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CountdownRing(remaining, session.windowMs, contentDescription = hint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(if (basket) R.string.send_drop_title else R.string.send_open_hand_other),
                style = MaterialTheme.typography.titleLarge,
                color = c.text,
            )
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
private fun Notice(error: TransferError) {
    StatusPill(stringResource(error.message()), tone = PillTone.Danger, icon = KaffIcons.Alert)
}

private fun previewFile(name: String, mime: String, size: Long) = OutgoingFile("content://preview/$name", FileMeta(name, size, mime, "00"), null)

private val previewBasket = listOf(
    previewFile("IMG_2841.jpg", "image/jpeg", 3_200_000),
    previewFile("IMG_2843.jpg", "image/jpeg", 2_900_000),
    previewFile("IMG_2850.jpg", "image/jpeg", 3_100_000),
    previewFile("VID_0193.mp4", "video/mp4", 12_400_000),
    previewFile("Project_Report.pdf", "application/pdf", 3_100_000),
)
private val previewPeer = Peer("B", "Samsung A54")

@Preview(name = "Send · armed basket · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendArmedPreview() {
    KaffTheme(darkTheme = true) {
        SendScreen(
            SendUiState(
                session = SendState.Armed(previewPeer, "4721", "t", SystemClock.elapsedRealtime(), 5_000),
                files = previewBasket,
            ),
            analyzer = { it.close() },
            actions = SendActions(),
        )
    }
}

@Preview(name = "Send · transferring · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendTransferPreview() {
    KaffTheme(darkTheme = true) {
        SendScreen(
            SendUiState(
                session = SendState.Transferring(
                    previewPeer, "4721", "t", 24_700_000, SystemClock.elapsedRealtime() - 2_000,
                    payloadIds = listOf(1, 2, 3, 4, 5),
                    sentBytes = mapOf(1L to 3_200_000L, 2L to 2_900_000L, 3L to 3_100_000L, 4L to 4_700_000L),
                    completed = setOf(1, 2, 3),
                ),
                files = previewBasket,
            ),
            analyzer = { it.close() },
            actions = SendActions(),
        )
    }
}

@Preview(name = "Send · pairing · light", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun SendPairingPreview() {
    KaffTheme(darkTheme = false) {
        SendScreen(
            SendUiState(session = SendState.AwaitingPairing(previewPeer, "4721"), files = previewBasket.take(1)),
            analyzer = { it.close() },
            actions = SendActions(),
        )
    }
}
