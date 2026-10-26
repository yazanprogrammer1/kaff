package dev.kaff.app.ui.components

import android.graphics.BitmapFactory
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.ui.theme.KaffShapes
import dev.kaff.app.ui.theme.KaffTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a small JPEG (e.g. from an OFFER) once per byte array. */
@Composable
fun rememberJpeg(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
}

/** Loads a thumbnail of a content URI off the main thread. */
@Composable
fun rememberUriThumbnail(uri: String?, sizePx: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        if (uri == null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val parsed = uri.toUri()
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(parsed, Size(sizePx, sizePx), null)
                } else {
                    context.contentResolver.openInputStream(parsed)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 })
                    }
                }
                bitmap?.asImageBitmap()
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }
    }
    return image
}

/**
 * A file preview tile: the image when there is one, otherwise a branded gradient with the file
 * type icon.
 */
@Composable
fun FileTile(
    image: ImageBitmap?,
    kind: FileKind,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    shape: Shape = KaffShapes.hero,
    bordered: Boolean = true,
) {
    val c = KaffTheme.colors
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(c.surface2, c.glow, c.surface)))
            .then(if (bordered) Modifier.border(2.dp, c.primary, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                when (kind) {
                    FileKind.Image -> KaffIcons.Image
                    FileKind.Video -> KaffIcons.Video
                    FileKind.Other -> KaffIcons.File
                },
                contentDescription = null,
                tint = c.primary,
                modifier = Modifier.size(size * 0.36f),
            )
        }
    }
}
