package dev.kaff.app.presentation.history

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.RecordFile
import dev.kaff.app.presentation.common.itemCount
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.theme.KaffShapes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.TransferDirection
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.repository.HistoryRepository
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.HeroText
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.KaffTopBar
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.theme.KaffTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRepository) : ViewModel() {
    val records: StateFlow<List<TransferRecord>?> =
        history.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { history.clear() }
    }
}

@Composable
fun HistoryRoute(onBack: () -> Unit, onOpenFile: (RecordFile) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    HistoryScreen(records, onBack, viewModel::clear, onOpenFile)
}

@Composable
fun HistoryScreen(records: List<TransferRecord>?, onBack: () -> Unit, onClear: () -> Unit, onOpenFile: (RecordFile) -> Unit = {}) {
    KaffBackground(glowCenterY = 0.1f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            KaffTopBar(KaffIcons.ArrowBack, stringResource(R.string.action_back), onBack, title = stringResource(R.string.history_title)) {
                if (!records.isNullOrEmpty()) GlassIconButton(KaffIcons.Trash, stringResource(R.string.history_clear), onClear)
            }
            when {
                records == null -> Unit
                records.isEmpty() -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    IconBadge(KaffIcons.History, size = 72.dp)
                    HeroText(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
                }
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(records, key = { it.id }) { HistoryRow(it, onOpenFile) }
                }
            }
        }
    }
}

/** Files of a record; entries from before version 2 only know their first saved file. */
private fun TransferRecord.openableFiles(): List<RecordFile> = files.ifEmpty {
    listOfNotNull(savedUri?.let { RecordFile(it, firstFileName, firstMime, if (fileCount == 1) totalBytes else 0) })
}

@Composable
private fun HistoryRow(record: TransferRecord, onOpenFile: (RecordFile) -> Unit) {
    val c = KaffTheme.colors
    val kind = FileMeta(record.firstFileName, 0, record.firstMime, "").kind
    val files = record.openableFiles()
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val first = files.firstOrNull()
    KaffCard(
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .clip(KaffShapes.card)
            .clickable(enabled = files.isNotEmpty(), role = Role.Button) {
                if (files.size == 1) onOpenFile(files.single()) else expanded = !expanded
            }
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                val thumb = rememberThumbnail(first?.uri?.takeIf { kind == FileKind.Image || kind == FileKind.Video })
                FileTile(thumb, kind, size = 52.dp, shape = KaffShapes.medium, bordered = false)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (record.success) c.primary else c.danger),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (record.desktop) KaffIcons.Desktop else KaffIcons.Phone,
                        contentDescription = null,
                        tint = c.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(
                        if (record.direction == TransferDirection.Sent) R.string.history_sent else R.string.history_received,
                        record.peerName,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text,
                    maxLines = 1,
                )
                Text(
                    (if (record.fileCount > 1) itemCount(record.fileCount) else record.firstFileName) + " · " + fileSizeText(record.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(record.createdAtMs).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            when {
                !record.success -> StatusPill(stringResource(R.string.history_failed), tone = PillTone.Danger, dot = false)
                files.size == 1 -> Text(stringResource(R.string.desktop_open), style = MaterialTheme.typography.labelLarge, color = c.primarySoft)
                files.size > 1 -> Icon(
                    KaffIcons.ArrowForward,
                    contentDescription = null,
                    tint = c.primarySoft,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(if (expanded) 90f else -90f),
                )
            }
        }
        if (expanded && files.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files, key = { it.uri }) { file -> FileChip(file) { onOpenFile(file) } }
            }
        }
    }
}

@Composable
private fun FileChip(file: RecordFile, onClick: () -> Unit) {
    val c = KaffTheme.colors
    val kind = FileMeta(file.name, file.size, file.mime, "").kind
    Column(
        Modifier
            .width(96.dp)
            .clip(KaffShapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FileTile(rememberThumbnail(file.uri.takeIf { kind == FileKind.Image || kind == FileKind.Video }), kind, size = 88.dp, shape = KaffShapes.medium, bordered = false)
        Text(file.name, style = MaterialTheme.typography.labelSmall, color = c.textSubtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Preview(name = "History · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Composable
private fun HistoryPreview() {
    KaffTheme(darkTheme = true) {
        HistoryScreen(
            listOf(
                TransferRecord(1, TransferDirection.Received, "Pixel 8", 1, 3_200_000, "IMG_2841.jpg", "image/jpeg", true, 100, 0),
                TransferRecord(2, TransferDirection.Sent, "Galaxy A54", 2, 12_000_000, "trip.mp4", "video/mp4", false, 900, 0),
            ),
            onBack = {},
            onClear = {},
        )
    }
}

@Preview(name = "History · empty · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun HistoryEmptyPreview() {
    KaffTheme(darkTheme = true) { HistoryScreen(emptyList(), onBack = {}, onClear = {}) }
}
