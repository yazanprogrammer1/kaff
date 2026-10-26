package dev.kaff.app.presentation.history

import android.text.format.DateUtils
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
import dev.kaff.app.domain.model.FileKind
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
fun HistoryRoute(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    HistoryScreen(records, onBack, viewModel::clear)
}

@Composable
fun HistoryScreen(records: List<TransferRecord>?, onBack: () -> Unit, onClear: () -> Unit) {
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
                    items(records, key = { it.id }) { HistoryRow(it) }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(record: TransferRecord) {
    val c = KaffTheme.colors
    val kind = FileMeta(record.firstFileName, 0, record.firstMime, "").kind
    KaffCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                when (kind) {
                    FileKind.Image -> KaffIcons.Image
                    FileKind.Video -> KaffIcons.Video
                    FileKind.Other -> KaffIcons.File
                },
                tint = if (record.success) c.primary else c.danger,
            )
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
                    record.firstFileName + " · " + fileSizeText(record.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    maxLines = 1,
                )
                Text(
                    DateUtils.getRelativeTimeSpanString(record.createdAtMs).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            if (!record.success) StatusPill(stringResource(R.string.history_failed), tone = PillTone.Danger, dot = false)
        }
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
