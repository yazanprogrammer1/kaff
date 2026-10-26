package dev.kaff.app.presentation.receive

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.transfer.ProtocolMessage
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.presentation.common.CameraStage
import dev.kaff.app.presentation.common.RadioErrorPanel
import dev.kaff.app.presentation.common.message
import dev.kaff.app.ui.animation.HeroHand
import dev.kaff.app.ui.animation.sharedHero
import dev.kaff.app.ui.animation.PulseRings
import dev.kaff.app.ui.animation.rememberRemainingMs
import dev.kaff.app.ui.components.BackgroundStyle
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HandPoses
import dev.kaff.app.ui.components.HeroText
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
import kotlin.math.roundToInt

@Composable
fun ReceiveRoute(onClose: () -> Unit, onCompleted: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val completed = state.session is ReceiveState.Completed
    LaunchedEffect(completed) { if (completed) onCompleted() }
    ReceiveScreen(
        state = state,
        analyzer = viewModel.analyzer,
        actions = ReceiveActions(
            onClose = onClose,
            onConfirmPairing = viewModel::onConfirmPairing,
            onDeclinePairing = viewModel::onDeclinePairing,
            onAccept = viewModel::onAccept,
            onReject = viewModel::onReject,
            onRetry = viewModel::onRetry,
            onWakeCamera = viewModel::onWakeCamera,
            onCameraSelected = viewModel::onCameraSelected,
        ),
    )
}

data class ReceiveActions(
    val onClose: () -> Unit = {},
    val onConfirmPairing: () -> Unit = {},
    val onDeclinePairing: () -> Unit = {},
    val onAccept: () -> Unit = {},
    val onReject: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onWakeCamera: () -> Unit = {},
    val onCameraSelected: (Boolean) -> Unit = {},
)

@Composable
fun ReceiveScreen(state: ReceiveUiState, analyzer: ImageAnalysis.Analyzer, actions: ReceiveActions) {
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
                val session = state.session
                if (session is ReceiveState.Offered) {
                    val remaining = rememberRemainingMs(session.offeredAtMs + session.watchMs)
                    val seconds = ((remaining + 999) / 1000).toInt()
                    StatusPill(pluralStringResource(R.plurals.receive_watching, seconds, seconds), tone = PillTone.Neutral, icon = KaffIcons.Eye)
                }
            }
            AnimatedContent(
                targetState = state.session,
                contentKey = { it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                label = "receive",
            ) { session ->
                when (session) {
                    ReceiveState.Idle, is ReceiveState.Advertising -> Waiting(state.deviceName, (session as? ReceiveState.Advertising)?.notice)
                    is ReceiveState.PairingRequest -> Pairing(session, actions)
                    is ReceiveState.Connected -> Connected(session)
                    is ReceiveState.Offered -> Offered(session, state, analyzer, actions)
                    is ReceiveState.Receiving -> Receiving(session)
                    is ReceiveState.Completed -> Box(Modifier.fillMaxSize())
                    is ReceiveState.Failed -> Failed(session.error, actions.onRetry)
                }
            }
        }
    }
}

@Composable
private fun Waiting(deviceName: String, notice: TransferError?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        HeroHand(Modifier.sharedHero("hero-hand"), size = 260.dp)
        HeroText(stringResource(R.string.receive_waiting_title), stringResource(R.string.receive_waiting_body))
        if (deviceName.isNotBlank()) StatusPill(stringResource(R.string.receive_visible_as, deviceName), tone = PillTone.Neutral, icon = KaffIcons.Wifi)
        notice?.let { StatusPill(stringResource(it.message()), tone = PillTone.Danger, icon = KaffIcons.Alert) }
    }
}

@Composable
private fun Pairing(session: ReceiveState.PairingRequest, actions: ReceiveActions) {
    val c = KaffTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
        KaffCard(highlighted = true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(KaffIcons.Lock, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.receive_pair_title, session.peer.name), style = MaterialTheme.typography.titleLarge, color = c.text)
            }
            Text(stringResource(R.string.receive_pair_code), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            // Digits are read one by one by TalkBack so they can be compared out loud.
            Text(
                session.authDigits,
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = MaterialTheme.typography.displaySmall.fontSize * 0.3f),
                color = c.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = session.authDigits.toList().joinToString(" ") },
            )
            Text(stringResource(R.string.receive_pair_hint), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        if (session.confirmed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.send_peer_connecting), color = c.textMuted, style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(stringResource(R.string.receive_pair_confirm), actions.onConfirmPairing, modifier = Modifier.weight(1f), height = 54.dp)
                SecondaryButton(stringResource(R.string.receive_reject), actions.onDeclinePairing, modifier = Modifier.weight(1f), accent = false)
            }
        }
    }
}

@Composable
private fun Connected(session: ReceiveState.Connected) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        HeroHand(size = 240.dp)
        HeroText(stringResource(R.string.receive_connected, session.peer.name), stringResource(R.string.receive_waiting_body))
        session.notice?.let { StatusPill(stringResource(it.message()), tone = PillTone.Danger, icon = KaffIcons.Alert) }
    }
}

@Composable
private fun OfferCard(peer: Peer, digits: String, offer: ProtocolMessage.Offer) {
    val c = KaffTheme.colors
    val first = offer.files.first()
    KaffCard(highlighted = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileTile(rememberJpeg(offer.thumbnailBytes()), first.kind, size = 48.dp, shape = KaffShapes.medium, bordered = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = if (offer.files.size > 1) {
                    stringResource(R.string.receive_offer_many, pluralStringResource(R.plurals.file_count, offer.files.size, offer.files.size), peer.name)
                } else {
                    stringResource(
                        when (first.kind) {
                            FileKind.Image -> R.string.receive_offer_image
                            FileKind.Video -> R.string.receive_offer_video
                            FileKind.Other -> R.string.receive_offer_file
                        },
                        peer.name,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    (if (offer.files.size == 1) first.name + " · " else "") + fileSizeText(offer.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(KaffIcons.ShieldCheck, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.receive_verified, digits), style = MaterialTheme.typography.bodyMedium, color = c.primarySoft)
        }
    }
}

@Composable
private fun Offered(session: ReceiveState.Offered, state: ReceiveUiState, analyzer: ImageAnalysis.Analyzer, actions: ReceiveActions) {
    val c = KaffTheme.colors
    val hold by animateFloatAsState(state.holdProgress, label = "hold")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OfferCard(session.peer, session.authDigits, session.offer)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            PulseRings(hold, Modifier.size(300.dp))
            CameraStage(
                analyzer = analyzer,
                state = state.camera,
                onWake = actions.onWakeCamera,
                onCameraSelected = actions.onCameraSelected,
                idlePose = HandPoses.OPEN,
                glowState = GestureState.OpenPalm,
                shape = CircleShape,
                modifier = Modifier.size(210.dp),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val snapshot = state.camera.snapshot
            Text(
                if (snapshot.stable.state == GestureState.OpenPalm) {
                    stringResource(R.string.gesture_palm_confidence, (snapshot.stable.confidence * 100).roundToInt())
                } else {
                    stringResource(R.string.receive_open_hand)
                },
                style = MaterialTheme.typography.headlineSmall,
                color = if (snapshot.stable.state == GestureState.OpenPalm) c.primary else c.text,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(KaffShapes.pill)
                    .background(c.text.copy(alpha = 0.1f)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(hold)
                        .clip(KaffShapes.pill)
                        .background(c.primary),
                )
            }
            Text(stringResource(R.string.receive_hold_hint), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(stringResource(R.string.receive_accept), actions.onAccept, modifier = Modifier.weight(1f), height = 54.dp)
            SecondaryButton(stringResource(R.string.receive_reject), actions.onReject, modifier = Modifier.weight(1f), accent = false)
        }
    }
}

@Composable
private fun Receiving(session: ReceiveState.Receiving) {
    val c = KaffTheme.colors
    val progress by animateFloatAsState(session.progress, label = "receive")
    val saving = session.progress >= 1f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        OfferCard(session.peer, session.authDigits, session.offer)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            PulseRings(progress, Modifier.size(280.dp))
            FileTile(rememberJpeg(session.offer.thumbnailBytes()), session.offer.files.first().kind, size = 120.dp)
        }
        Text(
            if (saving) stringResource(R.string.receive_saving) else stringResource(R.string.receive_receiving, (session.progress * 100).roundToInt()),
            style = MaterialTheme.typography.titleLarge,
            color = c.text,
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(KaffShapes.pill),
            color = c.primary,
            trackColor = c.text.copy(alpha = 0.1f),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun Failed(error: TransferError, onRetry: () -> Unit) {
    val c = KaffTheme.colors
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(KaffIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(56.dp))
        HeroText(stringResource(R.string.error_title), null)
        RadioErrorPanel(error, onRetry)
    }
}

private val previewOffer = ProtocolMessage.Offer(
    "t",
    listOf(FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "00")),
)

@Preview(name = "Receive · offer · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun ReceiveOfferPreview() {
    KaffTheme(darkTheme = true) {
        ReceiveScreen(
            ReceiveUiState(
                session = ReceiveState.Offered(Peer("A", "Pixel 8"), "4721", previewOffer, armed = true, offeredAtMs = SystemClock.elapsedRealtime(), watchMs = 8_000),
                holdProgress = 0.62f,
            ),
            analyzer = { it.close() },
            actions = ReceiveActions(),
        )
    }
}

@Preview(name = "Receive · pairing · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun ReceivePairingPreview() {
    KaffTheme(darkTheme = true) {
        ReceiveScreen(
            ReceiveUiState(session = ReceiveState.PairingRequest(Peer("A", "Pixel 8"), "4721")),
            analyzer = { it.close() },
            actions = ReceiveActions(),
        )
    }
}

@Preview(name = "Receive · waiting · light", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun ReceiveWaitingPreview() {
    KaffTheme(darkTheme = false) {
        ReceiveScreen(ReceiveUiState(session = ReceiveState.Advertising(), deviceName = "Galaxy A54"), analyzer = { it.close() }, actions = ReceiveActions())
    }
}
