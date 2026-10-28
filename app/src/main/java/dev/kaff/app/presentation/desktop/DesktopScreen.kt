package dev.kaff.app.presentation.desktop

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import dev.kaff.app.core.util.KaffPermissions
import dev.kaff.app.data.desktop.DesktopServer
import dev.kaff.app.ui.components.PrimaryButton
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
import dev.kaff.app.core.util.speedText
import dev.kaff.app.data.desktop.DesktopActivity
import dev.kaff.app.data.desktop.DesktopClient
import dev.kaff.app.data.desktop.DesktopState
import dev.kaff.app.data.desktop.HotspotBand
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
    // Kaff's own network is the fast path: bring it up as soon as the screen opens.
    val askHotspot = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) viewModel.startHotspot()
    }
    LaunchedEffect(Unit) {
        val missing = KaffPermissions.missingHotspot(context)
        if (missing.isEmpty()) viewModel.startHotspot() else askHotspot.launch(missing.toTypedArray())
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
        onCopy = { text ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Kaff", text))
            // Android 13+ shows its own "copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.desktop_copied, Toast.LENGTH_SHORT).show()
        },
        onSendClip = viewModel::sendClip,
        onPaste = {
            context.getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        },
        onOpenLink = { link ->
            val uri = (if (link.startsWith("www.", ignoreCase = true)) "https://$link" else link).toUri()
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, R.string.done_open_failed, Toast.LENGTH_SHORT).show()
            }
        },
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
    onCopy: (String) -> Unit = {},
    onSendClip: (String) -> Unit = {},
    onPaste: () -> String? = { null },
    onOpenLink: (String) -> Unit = {},
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

            AddressCard(desktop, onStartHotspot, onStopHotspot, onCopy)

            if (desktop.approved.isNotEmpty()) Computers(desktop.approved)

            FilesCard(state.items, onAddFiles, onClearFiles)

            ClipsCard(desktop.clips, onSendClip, onPaste, onCopy, onOpenLink)

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
private fun AddressCard(desktop: DesktopState, onStartHotspot: () -> Unit, onStopHotspot: () -> Unit, onCopy: (String) -> Unit) {
    val c = KaffTheme.colors
    val hotspot = desktop.hotspot
    if (hotspot != null) HotspotCard(hotspot, onStopHotspot, onCopy)
    KaffCard(highlighted = hotspot == null, modifier = Modifier.animateContentSize()) {
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
            val display = url.removePrefix("http://").removeSuffix("/")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Step(if (hotspot != null) 2 else 1, stringResource(R.string.desktop_step_open)) }
                CopyButton(stringResource(R.string.desktop_copy_address)) { onCopy(display) }
            }
            // The address is typed by hand: one unbroken line, monospaced, always left-to-right.
            BasicText(
                display,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(KaffShapes.medium)
                    .background(c.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = c.primarySoft,
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 26.sp, stepSize = 0.5.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    stringResource(R.string.desktop_step_open_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    modifier = Modifier.weight(1f),
                )
                QrCode(url, stringResource(R.string.desktop_qr_address), size = 104.dp)
            }
            desktop.wifiUrl?.let { other ->
                Text(
                    stringResource(R.string.desktop_also_wifi, other.removePrefix("http://").removeSuffix("/")),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
            }
            Step(if (hotspot != null) 3 else 2, stringResource(R.string.desktop_step_allow))
        }
        when {
            desktop.hotspotStarting -> StatusPill(stringResource(R.string.desktop_hotspot_starting), tone = PillTone.Neutral, glowingDot = true)
            hotspot != null -> Unit
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

/** Kaff's own network: the fastest path, with the name and password big enough to read across a desk. */
@Composable
private fun HotspotCard(hotspot: HotspotInfo, onStop: () -> Unit, onCopy: (String) -> Unit) {
    val c = KaffTheme.colors
    KaffCard(highlighted = true) {
        Step(1, stringResource(R.string.desktop_step_join))
        StatusPill(
            stringResource(
                when (hotspot.band) {
                    HotspotBand.Ghz5 -> R.string.desktop_hotspot_fast_5
                    HotspotBand.Ghz24 -> R.string.desktop_hotspot_fast_24
                    null -> R.string.desktop_hotspot_fast
                },
            ),
            icon = KaffIcons.Bolt,
        )
        Credential(stringResource(R.string.desktop_hotspot_name), hotspot.ssid, stringResource(R.string.desktop_copy_name)) { onCopy(hotspot.ssid) }
        Credential(stringResource(R.string.desktop_hotspot_password), hotspot.passphrase, stringResource(R.string.desktop_copy_password)) { onCopy(hotspot.passphrase) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.desktop_hotspot_qr_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
                modifier = Modifier.weight(1f),
            )
            QrCode(wifiQrPayload(hotspot.ssid, hotspot.passphrase), stringResource(R.string.desktop_qr_wifi), size = 96.dp)
        }
        SecondaryButton(stringResource(R.string.desktop_hotspot_stop), onStop, accent = false, height = 44.dp)
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
private fun Credential(label: String, value: String, copyLabel: String, onCopy: () -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.medium)
            .background(c.surface2)
            .border(1.dp, c.border, KaffShapes.medium)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            BasicText(
                value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = c.text,
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 24.sp),
            )
        }
        CopyButton(copyLabel, onCopy)
    }
}

@Composable
private fun CopyButton(label: String, onClick: () -> Unit) {
    val c = KaffTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(KaffShapes.medium)
            .background(c.primary.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KaffIcons.Copy, contentDescription = label, tint = c.primary, modifier = Modifier.size(22.dp))
    }
}

/** Text and links between the phone and the computer, newest first. */
@Composable
private fun ClipsCard(
    clips: List<DesktopServer.Clip>,
    onSend: (String) -> Unit,
    onPaste: () -> String?,
    onCopy: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val c = KaffTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    KaffCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(KaffIcons.Text)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.desktop_clips_title), style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(stringResource(R.string.desktop_clips_body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_CLIP_CHARS) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.desktop_clips_hint)) },
            minLines = 2,
            maxLines = 5,
            shape = KaffShapes.medium,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary,
                unfocusedBorderColor = c.border,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text,
                cursorColor = c.primary,
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                stringResource(R.string.desktop_clips_paste),
                { onPaste()?.let { draft = it.take(MAX_CLIP_CHARS) } },
                Modifier.weight(1f),
                accent = false,
                height = 48.dp,
            )
            PrimaryButton(
                stringResource(R.string.desktop_clips_send),
                {
                    onSend(draft)
                    draft = ""
                },
                Modifier.weight(1f),
                enabled = draft.isNotBlank(),
                height = 48.dp,
            )
        }
        clips.asReversed().take(MAX_ROWS).forEach { clip -> ClipRow(clip, onCopy, onOpenLink) }
    }
}

@Composable
private fun ClipRow(clip: DesktopServer.Clip, onCopy: (String) -> Unit, onOpenLink: (String) -> Unit) {
    val c = KaffTheme.colors
    val link = clip.text.takeIf { LINK.matches(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.medium)
            .background(if (clip.fromPhone) c.surface2 else c.primary.copy(alpha = 0.08f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (clip.fromPhone) KaffIcons.Phone else KaffIcons.Desktop, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                clip.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = if (link != null) c.primarySoft else c.text,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(if (clip.fromPhone) R.string.desktop_clip_from_phone else R.string.desktop_clip_from_computer),
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
        }
        if (link != null) {
            TextButton(onClick = { onOpenLink(link) }) { Text(stringResource(R.string.desktop_open), color = c.primarySoft) }
        }
        CopyButton(stringResource(R.string.desktop_copy_text)) { onCopy(clip.text) }
    }
}

private val LINK = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
private const val MAX_CLIP_CHARS = 20_000

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
                        stringResource(if (item.upload) R.string.desktop_activity_in else R.string.desktop_activity_out, fileSizeText(item.bytes)) +
                            if (item.bytesPerSecond > 0) " · " + speedText(item.bytesPerSecond) else "",
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
