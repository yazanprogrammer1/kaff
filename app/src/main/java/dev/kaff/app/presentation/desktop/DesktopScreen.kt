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
import dev.kaff.app.data.desktop.DesktopIncoming
import dev.kaff.app.data.desktop.DesktopOutgoing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.ui.components.FileTile
import kotlinx.coroutines.delay
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
fun DesktopRoute(onBack: () -> Unit, onPickFiles: () -> Unit, viewModel: DesktopViewModel = hiltViewModel()) {
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
        onOpenSaved = { openFile(context, it) },
        onCancelIncoming = viewModel::cancelIncoming,
        onPickFiles = {
            viewModel.startPicking()
            onPickFiles()
        },
        onCompatible = viewModel::setCompatible,
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
    onOpenSaved: (SavedFile) -> Unit,
    onCancelIncoming: (Long) -> Unit = {},
    onCopy: (String) -> Unit = {},
    onSendClip: (String) -> Unit = {},
    onPaste: () -> String? = { null },
    onOpenLink: (String) -> Unit = {},
    onPickFiles: () -> Unit = onAddFiles,
    onCompatible: (Boolean) -> Unit = {},
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

            if (desktop.approved.isNotEmpty()) Computers(desktop.approved)

            NetworkCard(desktop, onStartHotspot, onStopHotspot, onCopy, onCompatible)

            if (desktop.incoming.isNotEmpty()) IncomingCard(desktop.incoming, onCancelIncoming, onOpenSaved)

            OutgoingCard(desktop.outgoing, connected = desktop.approved.isNotEmpty(), onPickFiles, onAddFiles, onClearFiles)

            ClipsCard(desktop.clips, onSendClip, onPaste, onCopy, onOpenLink)


            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(KaffIcons.Lock, contentDescription = null, tint = c.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.desktop_privacy), style = MaterialTheme.typography.bodySmall, color = c.textMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

private enum class NetTab { SameWifi, Kaff }

/**
 * Picks the way in for the computer. With the phone on Wi-Fi, "same Wi-Fi" is the default: nothing
 * to switch on the computer. Kaff's network is always up next to it for speed or when there is no
 * Wi-Fi at all.
 */
@Composable
private fun NetworkCard(
    desktop: DesktopState,
    onStartHotspot: () -> Unit,
    onStopHotspot: () -> Unit,
    onCopy: (String) -> Unit,
    onCompatible: (Boolean) -> Unit,
) {
    var chosen by rememberSaveable { mutableStateOf<NetTab?>(null) }
    val tab = chosen ?: if (desktop.onWifi) NetTab.SameWifi else NetTab.Kaff
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.animateContentSize()) {
        if (desktop.onWifi) NetworkTabs(tab) { chosen = it }
        when (tab) {
            NetTab.SameWifi -> KaffCard(highlighted = true) {
                Step(1, stringResource(R.string.desktop_step_same_wifi))
                val url = desktop.wifiUrl
                if (url != null) AddressBlock(url, 2, onCopy)
                Step(3, stringResource(R.string.desktop_step_allow))
                Text(stringResource(R.string.desktop_same_wifi_tip), style = MaterialTheme.typography.bodySmall, color = KaffTheme.colors.textMuted)
            }
            NetTab.Kaff -> {
                val hotspot = desktop.hotspot
                if (hotspot != null) HotspotCard(hotspot, desktop.hotspotHealing, desktop.compatible, onStopHotspot, onCopy, onCompatible)
                KaffCard(highlighted = hotspot == null) {
                    val url = desktop.kaffUrl
                    when {
                        url != null -> {
                            AddressBlock(url, 2, onCopy)
                            Step(3, stringResource(R.string.desktop_step_allow))
                        }
                        desktop.hotspotStarting || desktop.hotspotHealing ->
                            StatusPill(stringResource(R.string.desktop_hotspot_starting), tone = PillTone.Neutral, glowingDot = true)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SecondaryButton(stringResource(R.string.desktop_hotspot_start), onStartHotspot, height = 46.dp)
                            Text(
                                stringResource(if (desktop.hotspotFailed) R.string.desktop_hotspot_failed else R.string.desktop_hotspot_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (desktop.hotspotFailed) KaffTheme.colors.danger else KaffTheme.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkTabs(selected: NetTab, onSelect: (NetTab) -> Unit) {
    val c = KaffTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.large)
            .background(c.surface)
            .border(1.dp, c.border, KaffShapes.large)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            NetTab.SameWifi to (KaffIcons.Wifi to stringResource(R.string.desktop_tab_same_wifi)),
            NetTab.Kaff to (KaffIcons.Bolt to stringResource(R.string.desktop_tab_kaff)),
        ).forEach { (tab, look) ->
            val on = tab == selected
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(KaffShapes.medium)
                    .background(if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(look.first, contentDescription = null, tint = if (on) c.onPrimary else c.primary, modifier = Modifier.size(18.dp))
                BasicText(
                    look.second,
                    style = MaterialTheme.typography.titleSmall.copy(color = if (on) c.onPrimary else c.textSubtle),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleSmall.fontSize),
                )
            }
        }
    }
}

/** Step [number]: the address to type, a copy button and its QR code. */
@Composable
private fun AddressBlock(url: String, number: Int, onCopy: (String) -> Unit) {
    val c = KaffTheme.colors
    val display = url.removePrefix("http://").removeSuffix("/")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { Step(number, stringResource(R.string.desktop_step_open)) }
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
}

/** Kaff's own network: the fastest path, with the name and password big enough to read across a desk. */
@Composable
private fun HotspotCard(
    hotspot: HotspotInfo,
    healing: Boolean,
    compatible: Boolean,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onCompatible: (Boolean) -> Unit,
) {
    val c = KaffTheme.colors
    KaffCard(highlighted = true) {
        Step(1, stringResource(R.string.desktop_step_join))
        if (healing) {
            StatusPill(stringResource(R.string.desktop_hotspot_healing), tone = PillTone.Neutral, glowingDot = true)
        } else {
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
        }
        if (hotspot.band == null) {
            // Local-only hotspot fallback: Wi-Fi Direct (5 GHz, fixed name) needs the phone's Wi-Fi switched on.
            Text(stringResource(R.string.desktop_hotspot_wifi_off), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
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
        BandChooser(compatible, onCompatible)
        SecondaryButton(stringResource(R.string.desktop_hotspot_stop), onStop, accent = false, height = 44.dp)
    }
}

/** 5 GHz is several times faster; 2.4 GHz is seen by every computer (some laptops have 5 GHz locked off). */
@Composable
private fun BandChooser(compatible: Boolean, onCompatible: (Boolean) -> Unit) {
    val c = KaffTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.desktop_band_title), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(KaffShapes.medium)
                .background(c.surface2)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            listOf(false to R.string.desktop_band_fast, true to R.string.desktop_band_compatible).forEach { (value, label) ->
                val on = value == compatible
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(KaffShapes.small)
                        .background(if (on) c.primary else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.RadioButton) { onCompatible(value) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        stringResource(label),
                        style = MaterialTheme.typography.labelLarge.copy(color = if (on) c.onPrimary else c.textSubtle, textAlign = TextAlign.Center),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize),
                    )
                }
            }
        }
        Text(stringResource(R.string.desktop_band_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
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
            val name = listOf(client.browser, client.os).filter { it.isNotBlank() }.joinToString(" · ")
            StatusPill(
                stringResource(if (client.viaKaff) R.string.desktop_connected_kaff else R.string.desktop_connected_wifi, name),
                icon = if (client.viaKaff) KaffIcons.Bolt else KaffIcons.Wifi,
            )
        }
    }
}

/** Sent to the computer: each file with its own download bar, like a copy over USB. */
@Composable
private fun OutgoingCard(files: List<DesktopOutgoing>, connected: Boolean, onPickFiles: () -> Unit, onAddFiles: () -> Unit, onClear: () -> Unit) {
    val c = KaffTheme.colors
    KaffCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(KaffIcons.Upload)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.desktop_out_title), style = MaterialTheme.typography.titleMedium, color = c.text)
                val done = files.count { it.done }
                Text(
                    when {
                        files.isEmpty() -> stringResource(R.string.desktop_out_body)
                        done == files.size -> stringResource(R.string.desktop_out_all_done, itemCount(files.size))
                        connected -> stringResource(R.string.desktop_out_progress, done, files.size)
                        else -> stringResource(R.string.desktop_files_waiting)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (files.isNotEmpty() && done == files.size) c.primarySoft else c.textMuted,
                )
            }
        }
        PrimaryButton(stringResource(R.string.desktop_files_pick), onPickFiles, height = 52.dp, trailingIcon = KaffIcons.Upload)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(stringResource(R.string.desktop_files_add), onAddFiles, Modifier.weight(1f), accent = false, height = 46.dp)
            if (files.isNotEmpty()) SecondaryButton(stringResource(R.string.desktop_files_clear), onClear, Modifier.weight(1f), accent = false, height = 46.dp)
        }
        files.asReversed().take(MAX_ROWS).forEach { file ->
            TransferRow(
                name = file.item.name,
                thumbUri = file.item.uri.takeIf { file.item.packageName == null },
                packageName = file.item.packageName,
                kind = file.item.kind,
                bytes = file.sentBytes,
                total = file.item.size,
                status = when {
                    file.done -> stringResource(R.string.desktop_row_on_computer, fileSizeText(file.item.size))
                    file.sentBytes > 0 -> "${fileSizeText(file.sentBytes)} / ${fileSizeText(file.item.size)}"
                    else -> stringResource(R.string.desktop_row_waiting, fileSizeText(file.item.size))
                },
                done = file.done,
                active = !file.done && file.sentBytes > 0,
            )
        }
        if (files.size > MAX_ROWS) MoreRows(files.size - MAX_ROWS)
    }
}

/** From the computer: live progress, time left and cancel; open once it is saved. */
@Composable
private fun IncomingCard(files: List<DesktopIncoming>, onCancel: (Long) -> Unit, onOpen: (SavedFile) -> Unit) {
    val c = KaffTheme.colors
    KaffCard(highlighted = files.any { it.active }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(KaffIcons.Download)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.desktop_in_title), style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(stringResource(R.string.desktop_in_body), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }
        // Re-read the clock every second so speed and time left move while bytes arrive.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(files.any { it.active }) {
            while (files.any { it.active }) {
                delay(1_000)
                now = System.currentTimeMillis()
            }
        }
        files.take(MAX_ROWS).forEach { file ->
            val saved = file.saved
            val elapsed = ((if (file.finishedAtMs > 0) file.finishedAtMs else now) - file.startedAtMs).coerceAtLeast(1)
            val speed = file.bytes * 1000.0 / elapsed
            val status = when {
                saved != null -> stringResource(R.string.desktop_row_saved, fileSizeText(file.total), speedText(speed))
                file.cancelled -> stringResource(R.string.desktop_row_cancelled)
                file.failed -> stringResource(R.string.desktop_row_failed)
                speed > 0 && file.bytes > 0 -> {
                    val left = ((file.total - file.bytes) / speed).toLong().coerceAtLeast(0)
                    "${fileSizeText(file.bytes)} / ${fileSizeText(file.total)} · ${speedText(speed)} · " +
                        pluralStringResource(R.plurals.transfer_eta, left.toInt(), left.toInt())
                }
                else -> stringResource(R.string.desktop_row_starting, fileSizeText(file.total))
            }
            TransferRow(
                name = file.name,
                thumbUri = saved?.uri,
                packageName = null,
                kind = FileMeta(file.name, file.total, file.mime, "").kind,
                bytes = file.bytes,
                total = file.total,
                status = status,
                done = saved != null,
                active = file.active,
                failed = file.failed || file.cancelled,
                onClick = saved?.let { { onOpen(it) } },
                trailing = {
                    when {
                        file.active -> TextButton(onClick = { onCancel(file.id) }) {
                            Text(stringResource(R.string.desktop_cancel), color = c.danger)
                        }
                        saved != null -> TextButton(onClick = { onOpen(saved) }) {
                            Text(stringResource(R.string.desktop_open), color = c.primarySoft)
                        }
                    }
                },
            )
        }
        if (files.size > MAX_ROWS) MoreRows(files.size - MAX_ROWS)
    }
}

@Composable
private fun TransferRow(
    name: String,
    thumbUri: String?,
    packageName: String?,
    kind: FileKind,
    bytes: Long,
    total: Long,
    status: String,
    done: Boolean,
    active: Boolean,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val c = KaffTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(KaffShapes.medium)
            .background(c.surface2)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                FileTile(rememberThumbnail(thumbUri, packageName), kind, size = 44.dp, shape = KaffShapes.small, bordered = false)
                if (done) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(c.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(KaffIcons.Check, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(11.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        failed -> c.danger
                        done -> c.primarySoft
                        else -> c.textMuted
                    },
                    maxLines = 2,
                )
            }
            trailing()
        }
        if (active || (!done && !failed && bytes > 0)) {
            val progress by animateFloatAsState(if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f), label = "row")
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(KaffShapes.pill),
                color = c.primary,
                trackColor = c.surface,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun MoreRows(count: Int) {
    Text(
        pluralStringResource(R.plurals.desktop_activity_more, count, count),
        style = MaterialTheme.typography.bodySmall,
        color = KaffTheme.colors.textMuted,
    )
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
                Text(
                    stringResource(if (client.viaKaff) R.string.desktop_via_kaff else R.string.desktop_via_wifi),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.primarySoft,
                )
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
                    wifiUrl = "http://192.168.1.23:4999/k7m2p/",
                    onWifi = true,
                    approved = listOf(DesktopClient("192.168.1.5", "Chrome", "Windows")),
                    outgoing = listOf(
                        DesktopOutgoing(1, PickItem("content://x/1", "IMG_2041.jpg", 3_400_000L, "image/jpeg", PickSource.Media), 1, 3_400_000L, done = true),
                        DesktopOutgoing(2, PickItem("content://x/2", "trip.mp4", 90_000_000L, "video/mp4", PickSource.Media), 1, 30_000_000L),
                    ),
                    incoming = listOf(DesktopIncoming(1, "setup.exe", "application/octet-stream", 80_000_000L, 20_000_000L, startedAtMs = 0)),
                ),
            ),
            onBack = {}, onAddFiles = {}, onClearFiles = {}, onStartHotspot = {}, onStopHotspot = {}, onOpenSaved = {},
        )
    }
}
