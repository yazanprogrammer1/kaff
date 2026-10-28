package dev.kaff.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What another app shared to Kaff (Gallery → Share → Kaff), held until the share screen picks
 * where it goes. The read grant comes with the intent and lasts while Kaff's task is alive.
 */
@Singleton
class ShareInbox @Inject constructor() {
    private val _uris = MutableStateFlow<List<Uri>>(emptyList())
    val uris: StateFlow<List<Uri>> = _uris.asStateFlow()

    /** Shared text or link as sent; the desktop shows it as a clip instead of a .txt file. */
    @Volatile var text: String? = null
        private set

    /** Takes the items out of a SEND / SEND_MULTIPLE intent. Returns false if it carried nothing usable. */
    fun accept(context: Context, intent: Intent?): Boolean {
        text = intent?.takeIf { it.action == Intent.ACTION_SEND && !it.hasExtra(Intent.EXTRA_STREAM) && it.clipData?.getItemAt(0)?.uri == null }
            ?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        val uris = intent?.let { parse(context, it) }.orEmpty()
        if (uris.isEmpty()) return false
        _uris.value = uris
        return true
    }

    fun clear() {
        text = null
        _uris.value = emptyList()
    }

    private fun parse(context: Context, intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> {
            val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            when {
                stream != null -> listOf(stream)
                intent.clipData != null -> clipUris(intent)
                else -> listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT)?.let { textFile(context, it, intent.getStringExtra(Intent.EXTRA_SUBJECT)) })
            }
        }
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.filterNotNull()?.ifEmpty { null }
                ?: clipUris(intent)
        else -> emptyList()
    }.distinct().take(MAX_SHARED)

    private fun clipUris(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    /** Shared text or a link becomes a small .txt file, so it travels like any other item. */
    private fun textFile(context: Context, text: String, subject: String?): Uri? {
        if (text.isBlank()) return null
        return try {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val base = subject?.takeIf { it.isNotBlank() }?.replace(UNSAFE_NAME, "_")?.take(40) ?: "Kaff-$stamp"
            val file = File(dir, "$base.txt")
            file.writeText(text)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        /** Matches the basket limit (OFFER must fit one Nearby message). */
        const val MAX_SHARED = 50
        val UNSAFE_NAME = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")
    }
}
