package dev.kaff.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.delay

private val Card = RoundedCornerShape(24.dp)
private val Medium = RoundedCornerShape(16.dp)

/** The whole window below the title bar. */
@Composable
fun KaffApp(controller: KaffController, dragging: Boolean, onChooseFiles: () -> Unit, onToggleLanguage: () -> Unit) {
    val phase by controller.phase.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(K.bg)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(K.glow, K.bg), center = Offset(size.width * 0.5f, size.height * 0.05f), radius = size.maxDimension * 0.7f))
            },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TopBar(controller, phase, onToggleLanguage)
            AnimatedContent(phase::class, transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) }, modifier = Modifier.weight(1f)) {
                when (val p = phase) {
                    Phase.Searching -> Searching(controller)
                    is Phase.Approving -> Approving(p.phone)
                    is Phase.Connected -> Connected(controller, p.phone, dragging, onChooseFiles)
                }
            }
        }
    }
}

@Composable
private fun TopBar(controller: KaffController, phase: Phase, onToggleLanguage: () -> Unit) {
    val t = Strings.t
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(K.primary), contentAlignment = Alignment.Center) {
            Icon(KIcons.Hand, null, tint = K.onPrimary, modifier = Modifier.size(24.dp))
        }
        Text(t.appName, style = MaterialTheme.typography.headlineSmall, color = K.text)
        if (phase is Phase.Connected) {
            Pill(
                "${t.connectedTitle} · ${phase.phone.name} · ${if (phase.phone.onKaffNetwork) t.viaKaff else t.viaWifi}",
                if (phase.phone.onKaffNetwork) KIcons.Bolt else KIcons.Wifi,
            )
        }
        Spacer(Modifier.weight(1f))
        Ghost(t.language, onClick = onToggleLanguage)
        if (phase is Phase.Connected) Ghost(t.disconnect, onClick = controller::disconnect)
    }
}

// region Searching

@Composable
private fun Searching(controller: KaffController) {
    val t = Strings.t
    val phones by controller.discovery.phones.collectAsState()
    val networks by controller.kaffNetworks.collectAsState()
    val denied by controller.denied.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Radar(KIcons.Phone)
        Text(t.searchingTitle, style = MaterialTheme.typography.displaySmall, color = K.text, textAlign = TextAlign.Center)
        Text(t.searchingBody, style = MaterialTheme.typography.bodyLarge, color = K.subtle, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        if (denied) Text(t.denied, style = MaterialTheme.typography.bodyMedium, color = K.danger)
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phones.isNotEmpty()) Label(t.phonesFound)
            phones.forEach { phone -> PhoneCard(phone) { controller.connect(phone) } }
            networks.forEach { ssid -> KaffNetworkCard(controller, ssid) }
        }
        Text(t.searchingHint, style = MaterialTheme.typography.bodyMedium, color = K.muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PhoneCard(phone: Phone, onConnect: () -> Unit) {
    val t = Strings.t
    Row(
        Modifier.fillMaxWidth().clip(Card).background(K.surface).border(1.dp, K.primary.copy(alpha = 0.35f), Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Badge(KIcons.Phone)
        Column(Modifier.weight(1f)) {
            Text(phone.name, style = MaterialTheme.typography.titleLarge, color = K.text)
            Text(if (phone.onKaffNetwork) t.viaKaff else t.viaWifi, style = MaterialTheme.typography.bodyMedium, color = K.muted)
        }
        Primary(t.connect, onClick = onConnect)
    }
}

@Composable
private fun KaffNetworkCard(controller: KaffController, ssid: String) {
    val t = Strings.t
    var password by remember(ssid) { mutableStateOf("") }
    var busy by remember(ssid) { mutableStateOf(false) }
    var failed by remember(ssid) { mutableStateOf(false) }
    val known = remember(ssid) { WifiNetworks.hasProfile(ssid) }
    Column(
        Modifier.fillMaxWidth().clip(Card).background(K.surface).border(1.dp, K.border, Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Badge(KIcons.Bolt)
            Column(Modifier.weight(1f)) {
                Text(t.kaffNetworkTitle, style = MaterialTheme.typography.titleLarge, color = K.text)
                Text(ssid, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr), color = K.soft)
                Text(t.kaffNetworkBody, style = MaterialTheme.typography.bodySmall, color = K.muted)
            }
        }
        if (!known) {
            Field(password, { password = it.take(63) }, t.password, monospace = true)
            Text(t.passwordHint, style = MaterialTheme.typography.bodySmall, color = K.muted)
        }
        if (failed) Text(t.joinFailed, style = MaterialTheme.typography.bodySmall, color = K.danger)
        Primary(if (busy) t.joining else t.join, enabled = !busy && (known || password.length >= 8)) {
            busy = true
            failed = false
            controller.joinKaffNetwork(ssid, password.takeIf { !known }) { ok ->
                busy = false
                failed = !ok
            }
        }
    }
}

// endregion

@Composable
private fun Approving(phone: Phone) {
    val t = Strings.t
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Radar(KIcons.Hand)
        Text(t.waitingApproval, style = MaterialTheme.typography.displaySmall, color = K.text)
        Text(phone.name, style = MaterialTheme.typography.titleLarge, color = K.soft)
        Text(t.waitingApprovalBody, style = MaterialTheme.typography.bodyLarge, color = K.subtle, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
    }
}

// region Connected

@Composable
private fun Connected(controller: KaffController, phone: Phone, dragging: Boolean, onChooseFiles: () -> Unit) {
    val transfers by controller.transfers.collectAsState()
    val fast by controller.fastNetwork.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val f = fast
        if (f != null && !phone.onKaffNetwork) FasterBanner(controller, f)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1.15f).fillMaxHeight()) {
                PanelHead(KIcons.Download, Strings.t.fromPhone) {
                    Ghost(Strings.t.openFolder, KIcons.Folder) { openPath(controller.identity.downloads.toFile()) }
                }
                val incoming = transfers.filter { it.incoming }
                if (incoming.isEmpty()) {
                    Empty(KIcons.Phone, Strings.t.fromPhoneEmpty)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(incoming, key = { it.id }) { TransferRow(it) } }
                }
            }
            Panel(Modifier.weight(1f).fillMaxHeight()) {
                PanelHead(KIcons.Upload, Strings.t.toPhone) {}
                DropZone(dragging, onChooseFiles)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(transfers.filter { !it.incoming }, key = { it.id }) { TransferRow(it) }
                }
            }
        }
        ClipsBar(controller)
    }
}

@Composable
private fun FasterBanner(controller: KaffController, fast: FastNetwork) {
    val t = Strings.t
    var busy by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(Card)
            .background(Brush.linearGradient(listOf(K.primary.copy(alpha = 0.16f), K.surface)))
            .border(1.dp, K.primary.copy(alpha = 0.35f), Card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Badge(KIcons.Bolt, filled = true)
        Column(Modifier.weight(1f)) {
            Text(t.fasterTitle, style = MaterialTheme.typography.titleLarge, color = K.text)
            Text(if (asked) t.switched else t.fasterBody, style = MaterialTheme.typography.bodyMedium, color = K.muted)
        }
        Ghost(t.cantSeeIt) {
            asked = true
            controller.askCompatibleNetwork()
        }
        // The phone already told us the network and its password: one click moves this computer there.
        Primary(if (busy) t.joining else t.join, enabled = !busy) {
            busy = true
            controller.joinKaffNetwork(fast.ssid, fast.pass) { busy = false }
        }
    }
}

@Composable
private fun DropZone(dragging: Boolean, onChooseFiles: () -> Unit) {
    val t = Strings.t
    val scale by animateFloatAsState(if (dragging) 1.03f else 1f)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 170.dp).scale(scale).clip(Card)
            .background(if (dragging) K.primary.copy(alpha = 0.12f) else Color.Transparent)
            .drawBehind {
                drawRoundRect(
                    color = K.primary.copy(alpha = if (dragging) 0.9f else 0.45f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
            }
            .clickable(onClick = onChooseFiles)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(KIcons.Upload, null, tint = K.primary, modifier = Modifier.size(40.dp))
        Text(t.dropTitle, style = MaterialTheme.typography.titleLarge, color = K.text)
        Text(t.dropBody, style = MaterialTheme.typography.bodyMedium, color = K.muted, textAlign = TextAlign.Center)
        Text(t.chooseFiles, style = MaterialTheme.typography.labelLarge, color = K.soft)
    }
}

@Composable
private fun TransferRow(item: Transfer) {
    val t = Strings.t
    // Ticks once a second so speed and time left move between progress updates.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.status) {
        while (item.status == TransferStatus.Active) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val done = item.status == TransferStatus.Done
    val clickable = done && item.incoming && item.path != null
    Column(
        Modifier.fillMaxWidth().clip(Medium).background(K.surface2)
            .then(if (clickable) Modifier.clickable { item.path?.toFile()?.let(::openPath) }.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(K.glow), contentAlignment = Alignment.Center) {
                Icon(if (done) KIcons.Check else KIcons.File, null, tint = K.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content), color = K.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val end = if (item.finishedAtMs > 0) item.finishedAtMs else now
                val rate = if (item.startedAtMs == 0L || end <= item.startedAtMs) 0.0 else item.bytes * 1000.0 / (end - item.startedAtMs)
                val status = when (item.status) {
                    TransferStatus.Waiting -> "${t.waiting} · ${size(item.size)}"
                    TransferStatus.Active -> buildString {
                        append("${size(item.bytes)} / ${size(item.size)}")
                        if (rate > 0) {
                            append(" · ${speed(rate)}")
                            append(" · ${t.left(((item.size - item.bytes) / rate).toLong().coerceAtLeast(0))}")
                        }
                    }
                    TransferStatus.Done -> "${t.done} · ${size(item.size)}" + if (rate > 0) " · ${speed(rate)}" else ""
                    TransferStatus.Failed -> t.failed
                    TransferStatus.Cancelled -> t.cancelled
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (item.status) {
                        TransferStatus.Done -> K.soft
                        TransferStatus.Failed, TransferStatus.Cancelled -> K.danger
                        else -> K.muted
                    },
                )
            }
            if (clickable) Text(t.open, style = MaterialTheme.typography.labelLarge, color = K.soft)
        }
        if (item.status == TransferStatus.Active) {
            val progress by animateFloatAsState(if (item.size <= 0) 0f else (item.bytes.toFloat() / item.size).coerceIn(0f, 1f))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                color = K.primary,
                trackColor = K.surface,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun ClipsBar(controller: KaffController) {
    val t = Strings.t
    val clips by controller.clips.collectAsState()
    var draft by remember { mutableStateOf("") }
    Panel(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(KIcons.Text, null, tint = K.primary, modifier = Modifier.size(20.dp))
            Text(t.textsTitle, style = MaterialTheme.typography.titleMedium, color = K.text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Field(draft, { draft = it.take(20_000) }, t.textHint, onEnter = {
                    if (draft.isNotBlank()) {
                        controller.sendClip(draft.trim())
                        draft = ""
                    }
                })
            }
            Primary(t.send, enabled = draft.isNotBlank()) {
                controller.sendClip(draft.trim())
                draft = ""
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(clips.asReversed(), key = { it.id }) { clip ->
                Row(
                    Modifier.fillMaxWidth().clip(Medium).background(if (clip.phone) K.surface2 else K.primary.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(if (clip.phone) KIcons.Phone else KIcons.Text, null, tint = K.primary, modifier = Modifier.size(16.dp))
                    Text(clip.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Ghost(t.copy, KIcons.Copy) { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(clip.text), null) }
                }
            }
        }
    }
}

// endregion

// region Building blocks

@Composable
private fun Radar(icon: ImageVector) {
    val pulse = rememberInfiniteTransition()
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2_200, easing = LinearEasing), RepeatMode.Restart))
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                listOf(p, (p + 0.5f) % 1f).forEach { f ->
                    drawCircle(K.primary.copy(alpha = (1f - f) * 0.55f), radius = size.minDimension / 2 * (0.45f + 0.55f * f), style = Stroke(2.dp.toPx()))
                }
            },
        )
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF5FF2AB), Color(0xFF1FCB7B)))), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = K.onPrimary, modifier = Modifier.size(44.dp))
        }
    }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(Card).background(K.surface.copy(alpha = 0.92f)).border(1.dp, K.border, Card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun PanelHead(icon: ImageVector, title: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Badge(icon)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = K.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Empty(icon: ImageVector, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = K.primary.copy(alpha = 0.8f), modifier = Modifier.size(44.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = K.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
    }
}

@Composable
private fun Badge(icon: ImageVector, filled: Boolean = false) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (filled) K.primary else K.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (filled) K.onPrimary else K.primary, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = K.muted)

@Composable
private fun Pill(text: String, icon: ImageVector) {
    Row(
        Modifier.clip(CircleShape).background(K.primary.copy(alpha = 0.12f)).border(1.dp, K.primary.copy(alpha = 0.3f), CircleShape).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = K.primary, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = K.soft, maxLines = 1)
    }
}

@Composable
fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(Medium)
            .background(if (enabled) K.primary else K.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) K.onPrimary else K.muted) }
}

@Composable
fun Ghost(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 38.dp).clip(Medium).border(1.dp, K.border, Medium)
            .clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = K.soft, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = K.subtle)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, monospace: Boolean = false, onEnter: (() -> Unit)? = null) {
    val base = MaterialTheme.typography.bodyLarge.copy(color = K.text, textDirection = TextDirection.Content)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = if (monospace) base.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr) else base,
        cursorBrush = SolidColor(K.primary),
        keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
            if (onEnter != null && it.type == KeyEventType.KeyDown && it.key == Key.Enter && !it.isShiftPressed) {
                onEnter()
                true
            } else {
                false
            }
        },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(Medium).background(K.surface2).border(1.dp, K.border, Medium).padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = K.muted)
                inner()
            }
        },
    )
}

fun openPath(file: java.io.File) {
    runCatching { Desktop.getDesktop().open(file) }
}

/** "30.0 MB", isolated left-to-right so it reads correctly inside Arabic text. */
fun size(bytes: Long): String = LRI + when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
} + PDI

/** "6.1 MB/s", isolated the same way. */
fun speed(bytesPerSecond: Double): String = size(bytesPerSecond.toLong()).removeSuffix(PDI) + "/s" + PDI

/** Unicode left-to-right isolate and its closing mark. */
private const val LRI = "⁦"
private const val PDI = "⁩"

// endregion
