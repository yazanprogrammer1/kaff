package dev.kaff.app.domain.model

import kotlinx.serialization.Serializable
import java.io.InputStream

/** A nearby device, identified by its Nearby endpoint id for the current session only. */
data class Peer(val endpointId: String, val name: String)

/** Metadata of one file as sent on the wire in OFFER. */
@Serializable
data class FileMeta(
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String,
) {
    val kind: FileKind
        get() = when {
            mime.startsWith("image/") -> FileKind.Image
            mime.startsWith("video/") -> FileKind.Video
            else -> FileKind.Other
        }
}

enum class FileKind { Image, Video, Other }

/** A file chosen by the sender, already hashed. [thumbnailJpeg] is small enough for OFFER. */
class OutgoingFile(
    val uri: String,
    val meta: FileMeta,
    val thumbnailJpeg: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = other is OutgoingFile && other.uri == uri && other.meta == meta
    override fun hashCode(): Int = 31 * uri.hashCode() + meta.hashCode()
    override fun toString(): String = "OutgoingFile(${meta.name}, ${meta.size} B)"
}

/** Handle to a fully received file payload, owned by the transport until [discard]. */
interface ReceivedFile {
    fun openStream(): InputStream

    /** Deletes the temporary copy kept by the transport. Safe to call more than once. */
    fun discard()
}

enum class SaveLocation { Gallery, Downloads, AppFiles }

data class SavedFile(val uri: String, val meta: FileMeta, val location: SaveLocation)

/** Final numbers for the Done screen and history. */
data class TransferStats(val bytes: Long, val durationMs: Long) {
    val bytesPerSecond: Double get() = if (durationMs <= 0) 0.0 else bytes * 1000.0 / durationMs
}

/** Why a session failed or showed a notice. Mapped to friendly Arabic text in the UI. */
enum class TransferError {
    ArmedTimeout,
    Rejected,
    Disconnected,
    ConnectionFailed,
    TransferFailed,
    Integrity,
    SaveFailed,
    CancelledByPeer,
    OfferExpired,
    NearbyUnavailable,
    FileUnreadable,
    PairingDeclined,
    VersionMismatch,
    WatchTimeout,
    Protocol,
}

enum class TransferDirection { Sent, Received }

data class TransferRecord(
    val id: Long = 0,
    val direction: TransferDirection,
    val peerName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val firstFileName: String,
    val firstMime: String,
    val success: Boolean,
    val durationMs: Long,
    val createdAtMs: Long,
    val savedUri: String? = null,
)
