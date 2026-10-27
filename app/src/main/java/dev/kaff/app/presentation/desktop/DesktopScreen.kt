package dev.kaff.app.presentation.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.data.desktop.DesktopActivity
import dev.kaff.app.data.desktop.DesktopClient
import dev.kaff.app.data.desktop.DesktopState
import dev.kaff.app.data.desktop.HotspotInfo
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.PickSource
import dev.kaff.app.presentation.common.itemCount
import dev.kaff.app.presentation.common.openFile
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.IconBadge
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffCard
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.MiniStack
import dev.kaff.app.ui.components.PillTone
import dev.kaff.app.ui.components.QrCode
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.StackCard
import dev.kaff.app.ui.components.StatusPill
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.components.wifiQrPayload
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme

@Composable
fun DesktopRoute(onBack: () -> Unit, viewModel: DesktopViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addDocuments(uris)
    }
    // The page only works while this screen is up; keep the phone awake for long downloads.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    DesktopScreen(
        state = state,
        onBack = onBack,
        onAddFiles = { pick.launch(arrayOf("*/*")) },
        onClearFiles = viewModel::clearFiles,
        onStartHotspot = viewModel::startHotspot,
        onStopHotspot = viewModel::stopHotspot,
        onOpen = { activity -> activity.saved?.let { openFile(context, it) } },
    )
    state.desktop.pending?.let { client ->
        ApprovalDialog(client, onAllow = { viewModel.decide(client, true) }, onDeny = { viewModel.decide(client, false) })
    }
}

@Composable
fun DesktopScreen(
    state: DesktopUiState,
    onBack: () -> Unit,
    onAddFiles: () -> Unit,
    onClearFiles: () -> Unit,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onOpen: (DesktopActivity) -> Unit,
) {
    val c = KaffTheme.colors
    val desktop = state.desktop
    KaffBackground(glowCenterY = 0.12f) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassIconButton(KaffIcons.ArrowBack, stringResource(R.string.action_back), onBack)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.desktop_title), style = MaterialTheme.typography.headlineSmall, color = c.text)
                    Text(stringResource(R.string.desktop_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }

            AddressCard(desktop, onStartHotspot, onStopHotspot)

            if (desktop.approved.isNotEmpty()) Computers(desktop.approved)

            FilesCard(state.items, onAddFiles, onClearFiles)

            desktop.uploading.values.forEach { (bytes, total) -> UploadRow(bytes, total) }
            if (desktop.activity.isNotEmpty()) ActivityCard(desktop.activity, onOpen)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(KaffIcons.Lock, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.desktop_privacy), style = MaterialTheme.typography.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun AddressCard(desktop: DesktopState, onStartHotspot: () -> Unit, onStopHotspot: () -> Unit) {
    val c = KaffTheme.colors
    KaffCard(highlighted = true, modifier = Modifier.animateContentSize()) {
        val hotspot = desktop.hotspot
        if (hotspot != null) {
            Step(1, stringResource(R.string.desktop_step_join))
            HotspotBlock(hotspot)
        }
        val url = desktop.url
        if (url == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(KaffIcons.Wifi)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.desktop_no_network), style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(stringResource(R.string.desktop_no_network_body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
            }
        } else {
            Step(if (hotspot != null) 2 else 1, stringResource(R.string.desktop_step_open))
            val display = url.removePrefix("http://").removeSuffix("/")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The address is typed by hand: big, monospaced and always left-to-right.
                    BasicText(
                        display,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            color = c.primarySoft,
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                        ),
                        maxLines = 2,
                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 24.sp),
                    )
                    Text(stringResource(R.string.desktop_step_open_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
                QrCode(url, stringResource(R.string.desktop_qr_address), size = 112.dp)
            }
            Step(if (hotspot != null) 3 else 2, stringResource(R.string.desktop_step_allow))
        }
        when {
            desktop.hotspotStarting -> StatusPill(stringResource(R.string.desktop_hotspot_starting), tone = PillTone.Neutral)
            hotspot != null -> SecondaryButton(stringResource(R.string.desktop_hotspot_stop), onStopHotspot, accent = false, height = 46.dp)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.desktop_hotspot_start), onStartHotspot, height = 46.dp)
                Text(
                    stringResource(if (desktop.hotspotFailed) R.string.desktop_hotspot_failed else R.string.desktop_hotspot_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (desktop.hotspotFailed) c.danger else c.textMuted,
                )
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    val c = KaffTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(c.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = c.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = c.text)
    }
}

@Composable
private fun HotspotBlock(hotspot: HotspotInfo) {
    val c = KaffTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Credential(stringResource(R.string.desktop_hotspot_name), hotspot.ssid)
            Credential(stringResource(R.string.desktop_hotspot_password), hotspot.passphrase)
        }
        QrCode(wifiQrPayload(hotspot.ssid, hotspot.passphrase), stringResource(R.string.desktop_qr_wifi), size = 96.dp)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.border),
    )
}

@Composable
private fun Credential(label: String, value: String) {
    val c = KaffTheme.colors
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Computers(clients: List<DesktopClient>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        clients.forEach { client ->
            StatusPill(
                stringResource(R.string.desktop_connected, listOf(client.browser, client.os).filter { it.isNotBlank() }.joinToString(" · ")),
                icon = KaffIcons.Desktop,
            )
        }
    }
}

@Composable
private fun FilesCard(items: List<PickItem>, onAddFiles: () -> Unit, onClearFiles: () -> Unit) {
    val c = KaffTheme.colors
    KaffCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items.isEmpty()) {
                IconBadge(KaffIcons.Download)
            } else {
                MiniStack(items.take(3).map { StackCard(rememberThumbnail(if (it.source == PickSource.App) null else it.uri, it.packageName), it.kind) })
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (items.isEmpty()) stringResource(R.string.desktop_files_empty) else stringResource(R.string.desktop_files_ready, itemCount(items.size)),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.text,
                )
                Text(
                    if (items.isEmpty()) stringResource(R.string.desktop_files_empty_body) else fileSizeText(items.sumOf { it.size }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.desktop_files_add), onAddFiles, Modifier.weight(1f), height = 46.dp)
            if (items.isNotEmpty()) SecondaryButton(stringResource(R.string.desktop_files_clear), onClearFiles, Modifier.weight(1f), accent = false, height = 46.dp)
        }
    }
}

@Composable
private fun UploadRow(bytes: Long, total: Long) {
    val c = KaffTheme.colors
    KaffCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(KaffIcons.Download, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.desktop_receiving), style = MaterialTheme.typography.titleSmall, color = c.text, modifier = Modifier.weight(1f))
            Text("${fileSizeText(bytes)} / ${fileSizeText(total)}", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        LinearProgressIndicator(
            progress = { if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(KaffShapes.pill),
            color = c.primary,
            trackColor = c.surface2,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun ActivityCard(activity: List<DesktopActivity>, onOpen: (DesktopActivity) -> Unit) {
    val c = KaffTheme.colors
    KaffCard {
        Text(stringResource(R.string.desktop_activity), style = MaterialTheme.typography.titleMedium, color = c.text)
        activity.take(MAX_ROWS).forEach { item ->
            val clickable = item.saved != null
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(KaffShapes.medium)
                    .then(if (clickable) Modifier.clickable(role = Role.Button) { onOpen(item) } else Modifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActivityIcon(if (item.upload) KaffIcons.Download else KaffIcons.Upload)
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(if (item.upload) R.string.desktop_activity_in else R.string.desktop_activity_out, fileSizeText(item.bytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
                if (clickable) Text(stringResource(R.string.desktop_open), style = MaterialTheme.typography.labelLarge, color = c.primarySoft)
            }
        }
        if (activity.size > MAX_ROWS) {
            Text(
                pluralStringResource(R.plurals.desktop_activity_more, activity.size - MAX_ROWS, activity.size - MAX_ROWS),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
    }
}

@Composable
private fun ActivityIcon(icon: ImageVector) {
    val c = KaffTheme.colors
    Box(
        Modifier
            .size(36.dp)
            .clip(KaffShapes.small)
            .background(c.primary.copy(alpha = 0.12f))
            .border(1.dp, c.primary.copy(alpha = 0.25f), KaffShapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ApprovalDialog(client: DesktopClient, onAllow: () -> Unit, onDeny: () -> Unit) {
    val c = KaffTheme.colors
    AlertDialog(
        onDismissRequest = onDeny,
        icon = { Icon(KaffIcons.Desktop, contentDescription = null, tint = c.primary, modifier = Modifier.size(32.dp)) },
        title = { Text(stringResource(R.string.desktop_approve_title), textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val name = listOf(client.browser, client.os).filter { it.isNotBlank() }.joinToString(" · ")
                Text(name, style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(client.address, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), color = c.textMuted)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.desktop_approve_body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.desktop_allow), color = c.primary) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.desktop_deny), color = c.textMuted) } },
        containerColor = c.surface,
        titleContentColor = c.text,
    )
}

private const val MAX_ROWS = 6

@Preview(name = "Desktop · RTL", locale = "ar", device = "spec:width=390dp,height=900dp")
@Composable
private fun DesktopPreview() {
    KaffTheme(darkTheme = true) {
        DesktopScreen(
            DesktopUiState(
                DesktopState(
                    running = true,
                    url = "http://192.168.1.23:4999/k7m2p/",
                    approved = listOf(DesktopClient("192.168.1.5", "Chrome", "Windows")),
                    activity = listOf(DesktopActivity("IMG_2041.jpg", 3_400_000, upload = false)),
                ),
                List(3) { PickItem("content://x/$it", "IMG_$it.jpg", 2_400_000L, "image/jpeg", PickSource.Media) },
            ),
            {}, {}, {}, {}, {}, {},
        )
    }
}
