package dev.kaff.app.presentation.picker

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.core.util.formatNumber
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.MediaAccess
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.PickSource
import dev.kaff.app.presentation.common.Basket
import dev.kaff.app.presentation.common.itemCount
import dev.kaff.app.presentation.common.kindCount
import dev.kaff.app.presentation.common.kindSummary
import dev.kaff.app.ui.components.BackgroundStyle
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.MiniStack
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import kotlin.math.roundToLong

data class PickerActions(
    val onBack: () -> Unit = {},
    val onReady: () -> Unit = {},
    val onTab: (PickTab) -> Unit = {},
    val onToggle: (PickItem) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onGrantMedia: () -> Unit = {},
    val onSystemPicker: () -> Unit = {},
    val onAddFiles: () -> Unit = {},
)

@Composable
fun PickerRoute(onBack: () -> Unit, onReady: () -> Unit, viewModel: PickerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Basket.MAX_ITEMS)) {
        viewModel.addUris(it, asDocuments = false)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.addUris(it, asDocuments = true)
    }
    // Permission changed in system settings (e.g. "select more photos")? Show it on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val limitMessage = stringResource(R.string.picker_limit, Basket.MAX_ITEMS)
    LaunchedEffect(state.limitHit) {
        if (state.limitHit) Toast.makeText(context, limitMessage, Toast.LENGTH_SHORT).show()
    }
    PickerScreen(
        state = state,
        actions = PickerActions(
            onBack = onBack,
            onReady = onReady,
            onTab = viewModel::selectTab,
            onToggle = viewModel::toggle,
            onSelectAll = viewModel::selectAllVisible,
            onQuery = viewModel::setQuery,
            onToggleSearch = viewModel::toggleSearch,
            onGrantMedia = { mediaPermission.launch(KaffPermissions.media.toTypedArray()) },
            onSystemPicker = { systemPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
            onAddFiles = { documents.launch(arrayOf("*/*")) },
        ),
    )
}

@Composable
fun PickerScreen(state: PickerUiState, actions: PickerActions) {
    val c = KaffTheme.colors
    KaffBackground(style = BackgroundStyle.Camera) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(state, actions)
            Tabs(state, actions.onTab)
            if (state.tab != PickTab.Files && state.access != MediaAccess.None) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    val count = state.visible?.size ?: 0
                    Text(
                        stringResource(R.string.picker_recent, tabCount(state.tab, count)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    val visible = state.visible.orEmpty().filterNot { it.unsupported }
                    if (visible.isNotEmpty()) {
                        val all = visible.all { it.uri in state.selectedUris }
                        TextButton(onClick = actions.onSelectAll) {
                            Text(
                                stringResource(if (all) R.string.picker_clear_all else R.string.picker_select_all),
                                style = MaterialTheme.typography.labelMedium,
                                color = c.primarySoft,
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                Content(state, actions)
                BasketBar(
                    state.basket,
                    actions.onReady,
                    Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun Header(state: PickerUiState, actions: PickerActions) {
    val c = KaffTheme.colors
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassIconButton(KaffIcons.ArrowBack, stringResource(R.string.action_back), actions.onBack)
            Text(
                stringResource(R.string.picker_title),
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(if (state.searching) KaffIcons.Close else KaffIcons.Search, stringResource(R.string.picker_search), actions.onToggleSearch)
        }
        AnimatedVisibility(state.searching) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                singleLine = true,
                leadingIcon = { Icon(KaffIcons.Search, contentDescription = null, tint = c.textMuted) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = KaffShapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.primary,
                    unfocusedBorderColor = c.border,
                    cursorColor = c.primary,
                    focusedTextColor = c.text,
                    unfocusedTextColor = c.text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Tabs(state: PickerUiState, onTab: (PickTab) -> Unit) {
    val c = KaffTheme.colors
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PickTab.entries) { tab ->
            val active = tab == state.tab
            val count = state.countByTab[tab] ?: 0
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(KaffShapes.pill)
                    .background(if (active) c.primary else c.text.copy(alpha = 0.05f))
                    .border(1.dp, if (active) c.primary else c.text.copy(alpha = 0.1f), KaffShapes.pill)
                    .clickable(role = Role.Tab) { onTab(tab) }
                    .semantics { selected = active }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(tab.label()), style = MaterialTheme.typography.titleSmall, color = if (active) c.onPrimary else c.textSubtle)
                if (count > 0) {
                    Box(
                        Modifier
                            .widthIn(min = 22.dp)
                            .clip(KaffShapes.pill)
                            .background(if (active) c.onPrimary else c.primary.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            formatNumber(count.toDouble(), 0),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (active) c.primary else c.primarySoft,
                        )
                    }
                }
            }
        }
    }
}

private fun PickTab.label(): Int = when (this) {
    PickTab.Photos -> R.string.tab_photos
    PickTab.Videos -> R.string.tab_videos
    PickTab.Apps -> R.string.tab_apps
    PickTab.Files -> R.string.tab_files
    PickTab.Audio -> R.string.tab_audio
}

@Composable
private fun tabCount(tab: PickTab, count: Int): String = when (tab) {
    PickTab.Photos -> kindCount(FileKind.Image, count)
    PickTab.Videos -> kindCount(FileKind.Video, count)
    PickTab.Apps -> kindCount(FileKind.App, count)
    PickTab.Files -> kindCount(FileKind.Other, count)
    PickTab.Audio -> kindCount(FileKind.Audio, count)
}

/** Space kept free under the content for the floating basket bar. */
private val BAR_SPACE = 200.dp

@Composable
private fun Content(state: PickerUiState, actions: PickerActions) {
    val c = KaffTheme.colors
    val items = state.visible
    when {
        state.tab == PickTab.Files -> FilesTab(state, actions)
        state.access == MediaAccess.None -> AccessCard(actions)
        items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.primary) }
        else -> {
            val grid = state.tab == PickTab.Photos || state.tab == PickTab.Videos
            LazyVerticalGrid(
                columns = if (grid) GridCells.Fixed(3) else if (state.tab == PickTab.Apps) GridCells.Adaptive(88.dp) else GridCells.Fixed(1),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = BAR_SPACE),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.access == MediaAccess.Partial && state.tab != PickTab.Apps) {
                    item(span = { GridItemSpan(maxLineSpan) }) { PartialBanner(actions.onGrantMedia) }
                }
                if (items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(if (state.query.isBlank()) R.string.picker_empty else R.string.picker_no_results),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        )
                    }
                }
                items(items, key = { it.uri }) { item ->
                    val selected = item.uri in state.selectedUris
                    when (state.tab) {
                        PickTab.Photos, PickTab.Videos -> MediaCell(item, selected) { actions.onToggle(item) }
                        PickTab.Apps -> AppCell(item, selected) { actions.onToggle(item) }
                        PickTab.Audio, PickTab.Files -> ListRow(item, selected) { actions.onToggle(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val c = KaffTheme.colors
    Box(
        modifier
            .size(26.dp)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.background(c.primary) else Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(KaffIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Modifier.selectableItem(item: PickItem, selected: Boolean, onClick: () -> Unit): Modifier {
    val state = stringResource(if (selected) R.string.picker_selected else R.string.picker_not_selected)
    return this
        .clickable(enabled = !item.unsupported, role = Role.Checkbox, onClick = onClick)
        .semantics {
            contentDescription = "${item.name}, $state"
            this.selected = selected
        }
}

@Composable
private fun MediaCell(item: PickItem, selected: Boolean, onClick: () -> Unit) {
    val c = KaffTheme.colors
    val inset by animateDpAsState(if (selected) 6.dp else 0.dp, label = "inset")
    Box(
        Modifier
            .aspectRatio(1f)
            .selectableItem(item, selected, onClick),
    ) {
        FileTile(
            rememberThumbnail(item.uri),
            item.kind,
            size = 200.dp,
            shape = KaffShapes.medium,
            bordered = selected,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset),
        )
        if (selected) Box(Modifier.fillMaxSize().padding(inset).clip(KaffShapes.medium).background(c.background.copy(alpha = 0.28f)))
        SelectionMark(selected, Modifier.align(AbsoluteAlignment.TopLeft).padding(6.dp + inset))
        if (item.durationMs > 0) {
            Text(
                durationLabel(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(AbsoluteAlignment.BottomRight)
                    .padding(6.dp + inset)
                    .clip(KaffShapes.pill)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun durationLabel(ms: Long): String {
    val total = (ms / 1000.0).roundToLong()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun AppCell(item: PickItem, selected: Boolean, onClick: () -> Unit) {
    val c = KaffTheme.colors
    Column(
        Modifier
            .clip(KaffShapes.medium)
            .background(if (selected) c.primary.copy(alpha = 0.12f) else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, KaffShapes.medium)
            .selectableItem(item, selected, onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            FileTile(rememberThumbnail(null, item.packageName), FileKind.App, size = 56.dp, shape = KaffShapes.medium, bordered = false)
            if (selected) SelectionMark(true, Modifier.align(AbsoluteAlignment.TopLeft).padding(2.dp))
        }
        Text(
            item.name.removeSuffix(".apk"),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.unsupported) c.textMuted else c.textSubtle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (item.unsupported) stringResource(R.string.picker_app_split) else fileSizeText(item.size),
            style = MaterialTheme.typography.labelSmall,
            color = if (item.unsupported) c.textMuted.copy(alpha = 0.7f) else c.textMuted,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ListRow(item: PickItem, selected: Boolean, onClick: () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.large)
            .background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border, KaffShapes.large)
            .selectableItem(item, selected, onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileTile(rememberThumbnail(item.uri.takeIf { item.kind != FileKind.Other }), item.kind, size = 44.dp, shape = KaffShapes.small, bordered = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(fileSizeText(item.size), item.durationMs.takeIf { it > 0 }?.let { durationLabel(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
        SelectionMark(selected)
    }
}

@Composable
private fun FilesTab(state: PickerUiState, actions: PickerActions) {
    val c = KaffTheme.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = BAR_SPACE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(KaffShapes.card)
                    .background(c.surface)
                    .border(1.dp, c.primary.copy(alpha = 0.3f), KaffShapes.card)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconBadge(KaffIcons.File)
                    Text(stringResource(R.string.picker_files_body), style = MaterialTheme.typography.bodyMedium, color = c.textSubtle, modifier = Modifier.weight(1f))
                }
                PrimaryButton(stringResource(R.string.picker_files_add), actions.onAddFiles, trailingIcon = KaffIcons.Plus, height = 52.dp)
            }
        }
        items(state.visible.orEmpty(), key = { it.uri }) { item -> ListRow(item, true) { actions.onToggle(item) } }
    }
}

@Composable
private fun AccessCard(actions: PickerActions) {
    val c = KaffTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(bottom = BAR_SPACE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        IconBadge(KaffIcons.Image, size = 72.dp)
        Text(stringResource(R.string.picker_access_title), style = MaterialTheme.typography.headlineSmall, color = c.text, textAlign = TextAlign.Center)
        Text(stringResource(R.string.picker_access_body), style = MaterialTheme.typography.bodyLarge, color = c.textMuted, textAlign = TextAlign.Center)
        PrimaryButton(stringResource(R.string.picker_access_grant), actions.onGrantMedia)
        SecondaryButton(stringResource(R.string.picker_access_system), actions.onSystemPicker)
    }
}

@Composable
private fun PartialBanner(onMore: () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.medium)
            .background(c.primary.copy(alpha = 0.1f))
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.picker_partial), style = MaterialTheme.typography.bodySmall, color = c.textSubtle, modifier = Modifier.weight(1f))
        TextButton(onClick = onMore) { Text(stringResource(R.string.picker_partial_more), color = c.primarySoft) }
    }
}

/** Floating bar: the basket's contents at a glance and the "Ready" button. */
@Composable
private fun BasketBar(basket: List<PickItem>, onReady: () -> Unit, modifier: Modifier = Modifier) {
    val c = KaffTheme.colors
    AnimatedVisibility(
        visible = basket.isNotEmpty(),
        enter = slideInVertically { it * 2 },
        exit = slideOutVertically { it * 2 },
        modifier = modifier,
    ) {
        // A scrim fades the grid out under the bar so the hint never sits on top of thumbnails.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to c.background.copy(alpha = 0f), 0.22f to c.background.copy(alpha = 0.96f), 1f to c.background))
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(KaffIcons.Hand, contentDescription = null, tint = c.primarySoft, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.picker_hint), style = MaterialTheme.typography.bodyMedium, color = c.primarySoft)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .shadow(24.dp, KaffShapes.card, ambientColor = c.primary, spotColor = c.primary)
                    .clip(KaffShapes.card)
                    .background(c.surface.copy(alpha = 0.97f))
                    .border(1.dp, c.primary.copy(alpha = 0.45f), KaffShapes.card)
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiniStack(basket.take(3).map { StackCard(rememberThumbnail(if (it.packageName == null) it.uri else null, it.packageName), it.kind) })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        stringResource(R.string.basket_title, itemCount(basket.size)),
                        style = MaterialTheme.typography.titleMedium.copy(color = c.text),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize),
                    )
                    BasicText(
                        fileSizeText(basket.sumOf { it.size }) + " · " + kindSummary(basket.map { it.kind }, "، "),
                        style = MaterialTheme.typography.bodySmall.copy(color = c.textMuted),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.bodySmall.fontSize),
                    )
                }
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(KaffShapes.large)
                        .background(c.primary)
                        .clickable(role = Role.Button, onClick = onReady)
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.basket_ready), style = MaterialTheme.typography.titleMedium, color = c.onPrimary)
                    Icon(KaffIcons.ArrowForward, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private val previewItems = listOf(
    PickItem("content://1", "IMG_2841.jpg", 3_200_000, "image/jpeg", PickSource.Media),
    PickItem("content://2", "IMG_2843.jpg", 2_900_000, "image/jpeg", PickSource.Media),
    PickItem("content://3", "VID_0193.mp4", 12_400_000, "video/mp4", PickSource.Media, durationMs = 42_000),
    PickItem("content://4", "IMG_2850.jpg", 3_100_000, "image/jpeg", PickSource.Media),
)

@Preview(name = "Picker · RTL", locale = "ar", device = "spec:width=390dp,height=844dp")
@Preview(name = "Picker · EN", locale = "en", device = "spec:width=390dp,height=844dp")
@Composable
private fun PickerPreview() {
    KaffTheme(darkTheme = true) {
        PickerScreen(
            PickerUiState(items = previewItems, basket = previewItems.take(2) + PickItem("content://5", "Report.pdf", 3_100_000, "application/pdf", PickSource.Document)),
            PickerActions(),
        )
    }
}
