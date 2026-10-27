package dev.kaff.app.presentation.common

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.core.net.toUri
import dev.kaff.app.R
import dev.kaff.app.domain.model.FileKind
import dev.kaff.app.domain.model.SaveLocation
import dev.kaff.app.domain.model.SavedFile

/** Shared-storage folder a received file of this kind lands in (see MediaStoreFileRepository). */
fun FileKind.folder(): String = when (this) {
    FileKind.Image -> Environment.DIRECTORY_PICTURES
    FileKind.Video -> Environment.DIRECTORY_MOVIES
    FileKind.Audio -> Environment.DIRECTORY_MUSIC
    FileKind.App, FileKind.Other -> Environment.DIRECTORY_DOWNLOADS
} + "/Kaff"

/** Human-readable location, e.g. "Pictures / Kaff". */
fun SavedFile.locationLabel(): String =
    if (location == SaveLocation.AppFiles) "Android/data/…/Kaff" else meta.kind.folder().replace("/", " / ")

fun openFile(context: Context, file: SavedFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri.toUri(), file.meta.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    if (!context.tryStart(intent)) Toast.makeText(context, R.string.done_open_failed, Toast.LENGTH_SHORT).show()
}

/**
 * Opens the folder holding [file] in the system Files app. Not every launcher/Files app supports
 * opening a folder directly, so this falls back to the file itself, then to Downloads.
 */
fun openFolder(context: Context, file: SavedFile) {
    if (file.location != SaveLocation.AppFiles) {
        val folder = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:${file.meta.kind.folder()}")
        val browse = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (context.tryStart(browse)) return
    }
    if (file.meta.kind == FileKind.Other || file.meta.kind == FileKind.App) {
        if (context.tryStart(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
    }
    openFile(context, file)
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
