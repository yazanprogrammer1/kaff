package dev.kaff.app.presentation.viewer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import dev.kaff.app.R
import dev.kaff.app.core.util.fileSizeText
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.ui.components.FileTile
import dev.kaff.app.ui.components.GlassIconButton
import dev.kaff.app.ui.components.KaffBackground
import dev.kaff.app.ui.components.KaffIcons
import dev.kaff.app.ui.components.PrimaryButton
import dev.kaff.app.ui.components.SecondaryButton
import dev.kaff.app.ui.components.rememberThumbnail
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the viewer shows for a file, decided from its type and size. */
private enum class Look { Image, Video, Text, Other }

private sealed interface Loaded {
    data object Loading : Loaded
    data object Missing : Loaded
    data class Picture(val bitmap: Bitmap) : Loaded
    data class Words(val text: String, val truncated: Boolean) : Loaded
    data class Size(val bytes: Long) : Loaded
}

/**
 * Full-screen viewer for a file from the history: zoomable photos, video with controls, selectable
 * text, and for everything else a clean card that hands the file to the right app.
 */
@Composable
fun ViewerScreen(uri: String, name: String, mime: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val kind = FileMeta(name, 0, mime, "").kind
    val look = when {
        kind == FileKind.Image -> Look.Image
        kind == FileKind.Video -> Look.Video
        mime.startsWith("text/") || name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS -> Look.Text
        else -> Look.Other
    }
    val loaded by produceState<Loaded>(Loaded.Loading, uri) {
        value = withContext(Dispatchers.IO) { load(context, uri.toUri(), look) }
    }
    var chrome by remember { mutableStateOf(true) }
    val dark = look == Look.Image || look == Look.Video

    Box(Modifier.fillMaxSize()) {
        if (dark) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        } else {
            KaffBackground(glowCenterY = 0.2f) {}
        }
        Crossfade(loaded, label = "viewer") { state ->
            when (state) {
                Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KaffTheme.colors.primary)
                }
                Loaded.Missing -> FileCard(uri, name, mime, kind, null, missing = true)
                is Loaded.Picture -> ZoomableImage(state.bitmap) { chrome = !chrome }
                is Loaded.Words -> TextPage(state.text, state.truncated)
                is Loaded.Size -> if (look == Look.Video) {
                    VideoPlayer(uri.toUri())
                } else {
                    FileCard(uri, name, mime, kind, state.bytes, missing = false)
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            TopBar(name, dark, onBack, onShare = { share(context, uri, mime) }, onOpen = { openExternally(context, uri, mime) })
        }
    }
}

@Composable
private fun TopBar(name: String, dark: Boolean, onBack: () -> Unit, onShare: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (dark) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassIconButton(KaffIcons.ArrowBack, stringResource(R.string.action_back), onBack)
        Text(
            name,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            color = if (dark) Color.White else KaffTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        GlassIconButton(KaffIcons.Share, stringResource(R.string.viewer_share), onShare)
        GlassIconButton(KaffIcons.External, stringResource(R.string.viewer_open_with), onOpen)
    }
}

/** Pinch to zoom, drag to pan, double tap to zoom in and out, single tap to hide the bars. */
@Composable
private fun ZoomableImage(bitmap: Bitmap, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            offset = (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                setVideoURI(uri)
                setOnPreparedListener { start() }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    )
}

@Composable
private fun TextPage(text: String, truncated: Boolean) {
    val c = KaffTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(top = 72.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(KaffShapes.card)
                .background(c.surface)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    color = c.text,
                )
            }
            if (truncated) {
                Text(stringResource(R.string.viewer_text_truncated), style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(top = 12.dp))
            }
        }
        PrimaryButton(stringResource(R.string.viewer_copy_text), {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Kaff", text))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.desktop_copied, Toast.LENGTH_SHORT).show()
        }, trailingIcon = KaffIcons.Copy)
    }
}

/** Apps, documents, archives, audio…: what it is, how big, and the app that can open it. */
@Composable
private fun FileCard(uri: String, name: String, mime: String, kind: FileKind, bytes: Long?, missing: Boolean) {
    val c = KaffTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        FileTile(rememberThumbnail(if (missing) null else uri), kind, size = 148.dp, shape = KaffShapes.card)
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            color = c.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (missing) stringResource(R.string.viewer_missing) else listOfNotNull(bytes?.let { fileSizeText(it) }, mime.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (missing) null else FontFamily.Monospace),
            color = if (missing) c.danger else c.textMuted,
        )
        if (!missing) {
            PrimaryButton(
                stringResource(if (kind == FileKind.App) R.string.viewer_install else R.string.viewer_open_with),
                { openExternally(context, uri, mime) },
                trailingIcon = KaffIcons.External,
            )
            SecondaryButton(stringResource(R.string.viewer_share), { share(context, uri, mime) })
        }
    }
}

private fun load(context: Context, uri: Uri, look: Look): Loaded = try {
    when (look) {
        Look.Image -> decodeSampled(context, uri)?.let { Loaded.Picture(it) } ?: Loaded.Missing
        Look.Text -> context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_TEXT_BYTES + 1)
            Loaded.Words(bytes.copyOf(minOf(bytes.size, MAX_TEXT_BYTES)).toString(Charsets.UTF_8), bytes.size > MAX_TEXT_BYTES)
        } ?: Loaded.Missing
        Look.Video, Look.Other -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { Loaded.Size(it.length) } ?: Loaded.Missing
    }
} catch (e: IOException) {
    Loaded.Missing
} catch (e: SecurityException) {
    Loaded.Missing
} catch (e: IllegalArgumentException) {
    Loaded.Missing
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** Decodes at about screen size: full-resolution camera photos would not fit in memory. */
private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val target = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) * 2
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

fun openExternally(context: Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri.toUri(), mime.ifBlank { "*/*" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.done_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, uri: String, mime: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mime.ifBlank { "*/*" })
        .putExtra(Intent.EXTRA_STREAM, uri.toUri())
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.done_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private const val MAX_TEXT_BYTES = 512 * 1024
private val TEXT_EXTENSIONS = setOf("txt", "md", "json", "csv", "log", "xml", "html", "js", "kt", "py", "java", "yml", "yaml", "ini", "srt")
