package dev.kaff.app.data.mediastore

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.core.di.IoDispatcher
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.SaveLocation
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.SaveResult
import dev.kaff.app.domain.transfer.ProtocolCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Reads outgoing files through the ContentResolver and saves incoming ones without any storage
 * permission: MediaStore on Android 10+ (images to Pictures/Kaff, videos to Movies/Kaff, everything
 * else to Download/Kaff), and the app's own external folder on Android 8–9.
 */
@Singleton
class MediaStoreFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileRepository {

    private val resolver: ContentResolver get() = context.contentResolver

    override suspend fun prepare(uri: String): OutgoingFile? = withContext(io) {
        val parsed = uri.toUri()
        try {
            val (name, declaredSize) = queryNameAndSize(parsed)
            val mime = resolver.getType(parsed) ?: mimeFromName(name)
            val (sha, size) = resolver.openInputStream(parsed)?.use { hash(it) } ?: return@withContext null
            if (declaredSize != null && declaredSize != size) Log.w(TAG, "Declared size differs from content size")
            val meta = FileMeta(name = sanitize(name), size = size, mime = mime, sha256 = sha)
            OutgoingFile(uri, meta, thumbnail(parsed, meta.kind))
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read selected file", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to selected file", e)
            null
        }
    }

    override suspend fun save(file: ReceivedFile, meta: FileMeta): SaveResult = withContext(io) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(file, meta) else saveToAppFolder(file, meta)
        } catch (e: IOException) {
            Log.w(TAG, "Saving failed", e)
            SaveResult.StorageError
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Saving failed", e)
            SaveResult.StorageError
        } catch (e: SecurityException) {
            Log.w(TAG, "Saving failed", e)
            SaveResult.StorageError
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(file: ReceivedFile, meta: FileMeta): SaveResult {
        val (collection, folder, location) = when (meta.kind) {
            FileKind.Image -> Triple(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_PICTURES, SaveLocation.Gallery)
            FileKind.Video -> Triple(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_MOVIES, SaveLocation.Gallery)
            FileKind.Other -> Triple(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS, SaveLocation.Downloads)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(meta.name))
            put(MediaStore.MediaColumns.MIME_TYPE, meta.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values) ?: return SaveResult.StorageError
        val verified = try {
            val out = resolver.openOutputStream(target) ?: throw IOException("No output stream")
            copyVerified(file, out, meta)
        } catch (e: IOException) {
            resolver.delete(target, null, null)
            throw e
        }
        if (!verified) {
            resolver.delete(target, null, null)
            return SaveResult.IntegrityError
        }
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return SaveResult.Saved(SavedFile(target.toString(), meta, location))
    }

    private fun saveToAppFolder(file: ReceivedFile, meta: FileMeta): SaveResult {
        val type = when (meta.kind) {
            FileKind.Image -> Environment.DIRECTORY_PICTURES
            FileKind.Video -> Environment.DIRECTORY_MOVIES
            FileKind.Other -> Environment.DIRECTORY_DOWNLOADS
        }
        val dir = File(context.getExternalFilesDir(type) ?: context.filesDir, FOLDER).apply { mkdirs() }
        val target = uniqueFile(dir, sanitize(meta.name))
        val verified = target.outputStream().let { copyVerified(file, it, meta) }
        if (!verified) {
            target.delete()
            return SaveResult.IntegrityError
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        return SaveResult.Saved(SavedFile(uri.toString(), meta, SaveLocation.AppFiles))
    }

    /** Copies while hashing; true only if size and SHA-256 match the offer. */
    private fun copyVerified(file: ReceivedFile, out: OutputStream, meta: FileMeta): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.openStream().use { input ->
            out.use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    total += read
                    if (total > meta.size) return false
                }
            }
        }
        return total == meta.size && digest.digest().toHex() == meta.sha256
    }

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                return (name ?: uri.lastPathSegment ?: DEFAULT_NAME) to size
            }
        }
        return (uri.lastPathSegment ?: DEFAULT_NAME) to null
    }

    private fun hash(input: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return digest.digest().toHex() to total
    }

    /** A JPEG small enough to ride inside OFFER, or null for non-media and failures. */
    private fun thumbnail(uri: Uri, kind: FileKind): ByteArray? {
        if (kind == FileKind.Other) return null
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
            } else if (kind == FileKind.Image) {
                decodeSampled(uri)
            } else {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(context, uri)
                        getFrameAtTime(0)
                    } finally {
                        release()
                    }
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return compress(bitmap).also { bitmap.recycle() }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun compress(source: Bitmap): ByteArray? {
        var size = THUMB_PX
        while (size >= MIN_THUMB_PX) {
            val scale = size.toFloat() / maxOf(source.width, source.height)
            val scaled = if (scale < 1f) {
                source.scale((source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1))
            } else {
                source
            }
            for (quality in intArrayOf(80, 65, 50)) {
                val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
                if (bytes.size <= ProtocolCodec.MAX_THUMBNAIL_BYTES) {
                    if (scaled !== source) scaled.recycle()
                    return bytes
                }
            }
            if (scaled !== source) scaled.recycle()
            size /= 2
        }
        return null
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (candidate.exists()) candidate = File(dir, "$base (${n++})$ext")
        return candidate
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "FileRepository"
        const val FOLDER = "Kaff"
        const val DEFAULT_NAME = "kaff_file"
        const val BUFFER = 64 * 1024
        const val THUMB_PX = 256
        const val MIN_THUMB_PX = 64
        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

        /** Names come from another phone: strip paths and characters storage rejects. */
        fun sanitize(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').replace(UNSAFE, "_").trim().take(120).ifBlank { DEFAULT_NAME }
    }
}
