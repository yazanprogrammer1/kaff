package dev.kaff.app.presentation.done

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
import dev.kaff.app.presentation.common.DoneGroup
import dev.kaff.app.presentation.common.DoneSummary
import dev.kaff.app.presentation.common.itemCount
import dev.kaff.app.presentation.common.kindCount
import dev.kaff.app.presentation.common.kindSummary
import dev.kaff.app.presentation.common.locationLabel
import dev.kaff.app.presentation.common.openFile
import dev.kaff.app.presentation.common.openFolder
import dev.kaff.app.presentation.receive.LastReceiveHolder
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.components.rememberJpeg
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.theme.KaffTheme
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
    DoneScreen(
        result,
        onOpenFile = { openFile(context, it) },
        onOpenFolder = { openFolder(context, it) },
        onTransferAnother = onTransferAnother,
        onClose = onClose,
    )
}

/** Files that end up in the same folder are listed together, e.g. "3 photos · Pictures / Kaff". */
@Composable
private fun groupsOf(saved: List<SavedFile>, onOpenFolder: (SavedFile) -> Unit): List<DoneGroup> =
    saved.groupBy { it.locationLabel() }.map { (location, files) ->
        val kinds = files.map { it.meta.kind }
        val title = when {
            files.size == 1 && files.first().meta.mime == "application/pdf" -> stringResource(R.string.done_pdf)
            kinds.distinct().size == 1 -> kindCount(kinds.first(), files.size)
            else -> kindSummary(kinds, "، ")
        }
        DoneGroup(title, location) { onOpenFolder(files.first()) }
    }

@Composable
fun DoneScreen(
    result: ReceiveState.Completed,
    onOpenFile: (SavedFile) -> Unit,
    onOpenFolder: (SavedFile) -> Unit,
    onTransferAnother: () -> Unit,
    onClose: () -> Unit,
) {
    val c = KaffTheme.colors
    val first = result.saved.first()
    val single = result.saved.size == 1
    val offerThumbnail = rememberJpeg(result.offer.thumbnailBytes())
    val cards = result.saved.take(3).mapIndexed { index, file ->
        val image = rememberThumbnail(file.uri.takeIf { file.meta.kind != FileKind.Other && file.meta.kind != FileKind.App })
        StackCard(image ?: offerThumbnail.takeIf { index == 0 }, file.meta.kind)
    }
    KaffBackground(glowCenterY = 0.3f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = c.text)
                Spacer(Modifier.weight(1f))
                StatusPill(stringResource(R.string.done_speed_avg, speedText(result.stats.bytesPerSecond)), icon = KaffIcons.Bolt, dot = false)
            }
            val title = if (single) {
                stringResource(
                    when (first.meta.kind) {
                        FileKind.Image -> R.string.done_title_image
                        FileKind.Video -> R.string.done_title_video
                        else -> R.string.done_title_file
                    },
                )
            } else {
                stringResource(R.string.done_title_many, itemCount(result.saved.size))
            }
            val meta = if (single) {
                stringResource(R.string.done_meta, first.meta.name, fileSizeText(result.stats.bytes), durationText(result.stats.durationMs))
            } else {
                stringResource(R.string.done_meta_short, fileSizeText(result.stats.bytes), durationText(result.stats.durationMs))
            }
            DoneSummary(
                cards = cards,
                count = result.saved.size,
                title = title,
                meta = meta,
                groups = groupsOf(result.saved, onOpenFolder),
                note = result.skipped.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.done_skipped, it, it) },
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                stringResource(
                    when {
                        !single -> R.string.done_open_folder
                        first.meta.kind == FileKind.Image -> R.string.done_open_image
                        first.meta.kind == FileKind.Video -> R.string.done_open_video
                        else -> R.string.done_open_file
                    },
                ),
                onClick = { if (single) onOpenFile(first) else onOpenFolder(first) },
            )
            SecondaryButton(stringResource(R.string.done_new_transfer), onTransferAnother, height = 52.dp)
            SecondaryButton(stringResource(R.string.action_close), onClose, accent = false, height = 48.dp)
        }
    }
}

@Preview(name = "Done · basket · RTL", locale = "ar", device = "spec:width=390dp,height=900dp")
@Preview(name = "Done · basket · EN", locale = "en", device = "spec:width=390dp,height=900dp")
@Composable
internal fun DonePreview() {
    val image = FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "00")
    val video = FileMeta("VID_0193.mp4", 12_400_000, "video/mp4", "01")
    val pdf = FileMeta("Project_Report.pdf", 3_100_000, "application/pdf", "02")
    KaffTheme(darkTheme = true) {
        DoneScreen(
            ReceiveState.Completed(
                Peer("A", "Pixel 8"),
                ProtocolMessage.Offer("t", listOf(image, image, video, pdf)),
                listOf(
                    SavedFile("content://1", image, SaveLocation.Gallery),
                    SavedFile("content://2", image, SaveLocation.Gallery),
                    SavedFile("content://3", video, SaveLocation.Gallery),
                    SavedFile("content://4", pdf, SaveLocation.Downloads),
                ),
                // ~12.5 MB/s: the speed measured phone to phone over Wi-Fi Direct (also used for store images).
                TransferStats(21_900_000, 1_750),
            ),
            onOpenFile = {},
            onOpenFolder = {},
            onTransferAnother = {},
            onClose = {},
        )
    }
}
