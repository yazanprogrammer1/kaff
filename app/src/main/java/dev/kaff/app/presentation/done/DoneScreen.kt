package dev.kaff.app.presentation.done

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.R
import dev.kaff.app.core.util.durationText
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.core.util.speedText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.SaveLocation
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.model.TransferStats
import dev.kaff.app.domain.transfer.ProtocolMessage
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.presentation.receive.LastReceiveHolder
import dev.kaff.app.ui.animation.PulseRings
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.components.rememberJpeg
import dev.kaff.app.ui.components.rememberUriThumbnail
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import dev.kaff.app.ui.theme.LocalReduceMotion
import javax.inject.Inject

@HiltViewModel
class DoneViewModel @Inject constructor(holder: LastReceiveHolder) : ViewModel() {
    val result: ReceiveState.Completed? = holder.completed
}

@Composable
fun DoneRoute(onClose: () -> Unit, onTransferAnother: () -> Unit, viewModel: DoneViewModel = hiltViewModel()) {
    val result = viewModel.result
    if (result == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    DoneScreen(result, onOpen = { openFile(context, it) }, onTransferAnother = onTransferAnother, onClose = onClose)
}

private fun openFile(context: Context, file: SavedFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri.toUri(), file.meta.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.done_open_failed, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun DoneScreen(
    result: ReceiveState.Completed,
    onOpen: (SavedFile) -> Unit,
    onTransferAnother: () -> Unit,
    onClose: () -> Unit,
) {
    val c = KaffTheme.colors
    val first = result.saved.first()
    val kind = first.meta.kind
    KaffBackground(glowCenterY = 0.3f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.text)
                Spacer(Modifier.weight(1f))
                StatusPill(speedText(result.stats.bytesPerSecond), icon = KaffIcons.Bolt)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Materialize {
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        PulseRings(0f, Modifier.fillMaxSize())
                        val image = rememberUriThumbnail(first.uri.takeIf { kind != FileKind.Other })
                            ?: rememberJpeg(result.offer.thumbnailBytes())
                        FileTile(image, kind, size = 150.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-22).dp, y = (-16).dp)
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(c.primary)
                                .border(4.dp, c.background, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(KaffIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(26.dp)) }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val title = if (result.saved.size > 1) {
                        stringResource(R.string.done_title_many, pluralStringResource(R.plurals.file_count, result.saved.size, result.saved.size))
                    } else {
                        stringResource(
                            when (kind) {
                                FileKind.Image -> R.string.done_title_image
                                FileKind.Video -> R.string.done_title_video
                                FileKind.Other -> R.string.done_title_file
                            },
                        )
                    }
                    Text(title, style = MaterialTheme.typography.displaySmall, color = c.text)
                    Text(
                        stringResource(
                            R.string.done_meta,
                            if (result.saved.size > 1) result.peer.name else first.meta.name,
                            fileSizeText(result.stats.bytes),
                            durationText(result.stats.durationMs),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textMuted,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SavedRow(first.location, kind)
                PrimaryButton(
                    stringResource(
                        when (kind) {
                            FileKind.Image -> R.string.done_open_image
                            FileKind.Video -> R.string.done_open_video
                            FileKind.Other -> R.string.done_open_file
                        },
                    ),
                    onClick = { onOpen(first) },
                )
                SecondaryButton(stringResource(R.string.done_transfer_another), onTransferAnother, height = 52.dp)
                SecondaryButton(stringResource(R.string.action_close), onClose, accent = false, height = 48.dp)
            }
        }
    }
}

@Composable
private fun SavedRow(location: SaveLocation, kind: FileKind) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.large)
            .background(c.surface)
            .border(1.dp, c.border, KaffShapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(KaffIcons.Folder, contentDescription = null, tint = c.primary, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(
                    when (location) {
                        SaveLocation.Gallery -> R.string.done_saved_gallery
                        SaveLocation.Downloads -> R.string.done_saved_downloads
                        SaveLocation.AppFiles -> R.string.done_saved_app
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                color = c.text,
            )
            Text(
                when {
                    location == SaveLocation.AppFiles -> "Android/data/…/Kaff"
                    kind == FileKind.Image -> "Pictures / Kaff"
                    kind == FileKind.Video -> "Movies / Kaff"
                    else -> "Download / Kaff"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
    }
}

/** The received file "materializes": it scales up while going from blurred to sharp. */
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

@Preview(name = "Done · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Preview(name = "Done · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun DonePreview() {
    val meta = FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "00")
    KaffTheme(darkTheme = true) {
        DoneScreen(
            ReceiveState.Completed(
                Peer("A", "Pixel 8"),
                ProtocolMessage.Offer("t", listOf(meta)),
                listOf(SavedFile("content://preview", meta, SaveLocation.Gallery)),
                TransferStats(3_200_000, 100),
            ),
            onOpen = {},
            onTransferAnother = {},
            onClose = {},
        )
    }
}
