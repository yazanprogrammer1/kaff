package dev.kaff.app.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a small JPEG (e.g. from an OFFER) once per byte array. */
@Composable
fun rememberJpeg(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
}

/** Thumbnails decoded once per process; the grid scrolls back without reloading. */
private object ThumbnailCache : LruCache<String, ImageBitmap>(240)

/**
 * Loads a thumbnail off the main thread: an app icon when [packageName] is set, otherwise the
 * system thumbnail of a content URI (photos, videos, album art).
 */
@Composable
fun rememberThumbnail(uri: String?, packageName: String? = null, sizePx: Int = 320): ImageBitmap? {
    val context = LocalContext.current
    if (LocalInspectionMode.current) return null
    val key = packageName ?: uri ?: return null
    val image by produceState(ThumbnailCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri, packageName, sizePx) }?.also { ThumbnailCache.put(key, it) }
    }
    return image
}

private fun loadThumbnail(context: Context, uri: String?, packageName: String?, sizePx: Int): ImageBitmap? = try {
    if (packageName != null) {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx / 2, sizePx / 2).asImageBitmap()
    } else {
        val parsed = checkNotNull(uri).toUri()
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(parsed, Size(sizePx, sizePx), null)
        } else {
            context.contentResolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
            }
        }
        bitmap?.asImageBitmap()
    }
} catch (e: Exception) {
    // Any decoder or provider failure just means "no thumbnail"; the tile shows the type icon.
    null
}

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.Image -> KaffIcons.Image
    FileKind.Video -> KaffIcons.Video
    FileKind.Audio -> KaffIcons.Music
    FileKind.App -> KaffIcons.Apps
    FileKind.Other -> KaffIcons.File
}

/** Gradient that stands in for a missing preview, tinted per kind so stacks stay readable. */
fun FileKind.placeholder(): Brush = when (this) {
    FileKind.Image -> Brush.linearGradient(listOf(Color(0xFFF6B26B), Color(0xFFE4572E), Color(0xFF2A3B63)))
    FileKind.Video -> Brush.linearGradient(listOf(Color(0xFF8EC5FC), Color(0xFF5B6CF0)))
    FileKind.Audio -> Brush.linearGradient(listOf(Color(0xFFA18CD1), Color(0xFFFBC2EB)))
    FileKind.App -> Brush.linearGradient(listOf(Color(0xFF16251E), Color(0xFF0E3321)))
    FileKind.Other -> Brush.linearGradient(listOf(Color(0xFF16251E), Color(0xFF101B16)))
}

/**
 * A file preview tile: the image when there is one, otherwise a gradient with the type icon. App
 * icons are drawn centered instead of cropped.
 */
@Composable
fun FileTile(
    image: ImageBitmap?,
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    shape: Shape = KaffShapes.hero,
    bordered: Boolean = true,
    borderColor: Color = KaffTheme.colors.primary,
) {
    val c = KaffTheme.colors
    val photo = image != null && kind != FileKind.App
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (photo) Brush.linearGradient(listOf(c.surface2, c.surface)) else kind.placeholder())
            .then(if (bordered) Modifier.border(2.dp, borderColor, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo -> {
                Image(checkNotNull(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (kind == FileKind.Video) {
                    Box(
                        Modifier
                            .size(size * 0.28f)
                            .clip(KaffShapes.pill)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(KaffIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.14f)) }
                }
            }
            // App icons keep their own shape, centered on the dark tile.
            image != null -> Image(
                image,
                contentDescription = null,
                modifier = Modifier
                    .size(size * 0.58f)
                    .clip(RoundedCornerShape(percent = 26)),
            )
            else -> Icon(
                kind.icon(),
                contentDescription = null,
                tint = if (kind == FileKind.App || kind == FileKind.Other) c.primary else Color.White,
                modifier = Modifier.size(size * 0.36f),
            )
        }
    }
}
