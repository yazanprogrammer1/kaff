package dev.kaff.app.data.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.core.di.IoDispatcher
import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.MediaAccess
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.PickSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Lists what the picker shows: recent photos, videos and audio, installed apps, picked documents. */
@Singleton
class MediaLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    enum class Collection { Images, Videos, Audio }

    fun access(collection: Collection): MediaAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val full = when (collection) {
                    Collection.Images -> granted(Manifest.permission.READ_MEDIA_IMAGES)
                    Collection.Videos -> granted(Manifest.permission.READ_MEDIA_VIDEO)
                    Collection.Audio -> granted(Manifest.permission.READ_MEDIA_AUDIO)
                }
                val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    collection != Collection.Audio &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                when {
                    full -> MediaAccess.Full
                    partial -> MediaAccess.Partial
                    else -> MediaAccess.None
                }
            }
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.Full
            else -> MediaAccess.None
        }
    }

    suspend fun media(collection: Collection, limit: Int = MEDIA_LIMIT): List<PickItem> = withContext(io) {
        val base: Uri = when (collection) {
            Collection.Images -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Collection.Videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            Collection.Audio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (collection != Collection.Images) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val items = ArrayList<PickItem>()
        try {
            context.contentResolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val date = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val duration = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (c.moveToNext() && items.size < limit) {
                    val bytes = c.getLong(size)
                    if (bytes <= 0) continue
                    items += PickItem(
                        uri = ContentUris.withAppendedId(base, c.getLong(id)).toString(),
                        name = c.getString(name) ?: "kaff_${c.getLong(id)}",
                        size = bytes,
                        mime = c.getString(mime) ?: defaultMime(collection),
                        source = PickSource.Media,
                        dateAddedSec = c.getLong(date),
                        durationMs = if (duration >= 0) c.getLong(duration) else 0,
                    )
                }
            }
        } catch (e: SecurityException) {
            return@withContext emptyList()
        }
        items
    }

    /** Launchable apps the user installed, newest first. System apps are skipped. */
    suspend fun apps(): List<PickItem> = withContext(io) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != context.packageName }
            .mapNotNull { info ->
                val apk = File(info.sourceDir)
                if (!apk.canRead()) return@mapNotNull null
                val label = pm.getApplicationLabel(info).toString()
                val installed = try {
                    pm.getPackageInfo(info.packageName, 0).firstInstallTime / 1000
                } catch (e: PackageManager.NameNotFoundException) {
                    0L
                }
                PickItem(
                    uri = apk.toUri().toString(),
                    name = "${label.replace('/', '_')}.apk",
                    size = apk.length(),
                    mime = FileMeta.APK_MIME,
                    source = PickSource.App,
                    dateAddedSec = installed,
                    packageName = info.packageName,
                    unsupported = !info.splitSourceDirs.isNullOrEmpty(),
                )
            }
            // Shareable apps first; split-APK apps stay visible but at the end.
            .sortedWith(compareBy<PickItem> { it.unsupported }.thenByDescending { it.dateAddedSec })
    }

    /** Name, size and type of a document the user picked with the system file picker. */
    suspend fun describe(uri: Uri): PickItem? = withContext(io) {
        try {
            val resolver = context.contentResolver
            var name: String? = null
            var size = 0L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            // Some providers (and our own shared-text files) leave SIZE empty.
            if (size <= 0) {
                size = try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: 0L
                } catch (e: java.io.IOException) {
                    0L
                }
            }
            PickItem(
                uri = uri.toString(),
                name = name ?: uri.lastPathSegment ?: "kaff_file",
                size = size,
                mime = resolver.getType(uri) ?: "application/octet-stream",
                source = PickSource.Document,
            )
        } catch (e: SecurityException) {
            null
        }
    }

    private fun defaultMime(collection: Collection) = when (collection) {
        Collection.Images -> "image/jpeg"
        Collection.Videos -> "video/mp4"
        Collection.Audio -> "audio/mpeg"
    }

    private companion object {
        const val MEDIA_LIMIT = 600
    }
}
