package dev.kaff.app.domain.model

/** Where an item in the basket comes from; decides how it is listed and thumbnailed. */
enum class PickSource { Media, App, Document }

/**
 * Something the user can put in the basket. [uri] is a content or file URI that the transfer layer
 * can open; [packageName] is set for apps so their icon can be drawn.
 */
data class PickItem(
    val uri: String,
    val name: String,
    val size: Long,
    val mime: String,
    val source: PickSource,
    val dateAddedSec: Long = 0,
    val durationMs: Long = 0,
    val packageName: String? = null,
    /** Apps installed as split APKs cannot be rebuilt from a single file. */
    val unsupported: Boolean = false,
) {
    val kind: FileKind get() = FileMeta(name, size, mime, "").kind
}

/** Whether Kaff may list the user's media, per Android 14 partial-access rules. */
enum class MediaAccess { Full, Partial, None }
