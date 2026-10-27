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
            mime.startsWith("audio/") -> FileKind.Audio
            mime == APK_MIME -> FileKind.App
            else -> FileKind.Other
        }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

enum class FileKind { Image, Video, Audio, App, Other }

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

    /** A runtime permission Nearby needs (Bluetooth, nearby Wi-Fi or location) is not granted. */
    NearbyPermission,

    /** Location services are switched off and this Nearby version needs them for discovery. */
    LocationOff,

    /** Bluetooth is off or the radio failed. */
    BluetoothOff,
    FileUnreadable,
    PairingDeclined,
    VersionMismatch,
    WatchTimeout,
    Protocol,
}

/** How fast the current link is, as reported by the transport (Bluetooth → Wi-Fi upgrade). */
enum class LinkQuality { Unknown, Low, Medium, High }

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
