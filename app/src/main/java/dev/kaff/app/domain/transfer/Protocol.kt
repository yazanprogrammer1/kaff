package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.FileMeta
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** Wire messages exchanged as Nearby BYTES payloads. See docs/PROTOCOL.md. */
@Serializable
sealed interface ProtocolMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(val version: Int, val deviceName: String) : ProtocolMessage

    /** [thumbnail] is a Base64 JPEG of the first file, dropped if the message would not fit. */
    @Serializable
    @SerialName("offer")
    data class Offer(
        val transferId: String,
        val files: List<FileMeta>,
        val thumbnail: String? = null,
    ) : ProtocolMessage {
        val totalBytes: Long get() = files.sumOf { it.size }
        fun thumbnailBytes(): ByteArray? = thumbnail?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    }

    /** Sender made the grab gesture; the offer is live for [windowMs]. */
    @Serializable
    @SerialName("armed")
    data class Armed(val transferId: String, val windowMs: Long) : ProtocolMessage

    @Serializable
    @SerialName("accept")
    data class Accept(val transferId: String) : ProtocolMessage

    @Serializable
    @SerialName("reject")
    data class Reject(val transferId: String, val reason: Reason) : ProtocolMessage

    @Serializable
    @SerialName("cancel")
    data class Cancel(val transferId: String, val reason: Reason) : ProtocolMessage

    /** Announces that FILE payload [payloadId] carries file number [index] of the offer. */
    @Serializable
    @SerialName("file")
    data class FileHeader(val transferId: String, val index: Int, val payloadId: Long) : ProtocolMessage

    /**
     * Receiver's receipt: SHA-256 of every saved file in offer order. [skipped] lists offer indexes
     * the receiver did not keep ("cancel remaining"); older peers omit it, meaning none.
     */
    @Serializable
    @SerialName("done")
    data class Done(val transferId: String, val sha256: List<String>, val skipped: List<Int> = emptyList()) : ProtocolMessage

    @Serializable
    enum class Reason { UserDeclined, Timeout, UserCancelled, Integrity, StorageError }
}

@Serializable
private data class Envelope(val v: Int, val msg: ProtocolMessage)

sealed interface DecodeResult {
    data class Message(val message: ProtocolMessage) : DecodeResult
    data class UnsupportedVersion(val version: Int) : DecodeResult
    data object Malformed : DecodeResult
}

/** Versioned JSON codec. Never logs message contents. */
object ProtocolCodec {
    const val VERSION = 1

    /** Nearby's limit for BYTES payloads (ConnectionsClient.MAX_BYTES_DATA_SIZE). */
    const val MAX_BYTES = 32 * 1024

    /** Thumbnails above this are not sent; keeps OFFER well under [MAX_BYTES] after Base64. */
    const val MAX_THUMBNAIL_BYTES = 16 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(message: ProtocolMessage): ByteArray {
        val bytes = json.encodeToString(Envelope.serializer(), Envelope(VERSION, message)).encodeToByteArray()
        if (bytes.size <= MAX_BYTES) return bytes
        require(message is ProtocolMessage.Offer && message.thumbnail != null) {
            "Message of ${bytes.size} bytes exceeds the Nearby BYTES limit"
        }
        return encode(message.copy(thumbnail = null))
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.size > MAX_BYTES) return DecodeResult.Malformed
        return try {
            val element = json.parseToJsonElement(bytes.decodeToString())
            val version = (element as? JsonObject)?.get("v")
                ?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                ?: return DecodeResult.Malformed
            if (version != VERSION) return DecodeResult.UnsupportedVersion(version)
            DecodeResult.Message(json.decodeFromJsonElement(Envelope.serializer(), element).msg)
        } catch (e: SerializationException) {
            DecodeResult.Malformed
        } catch (e: IllegalArgumentException) {
            DecodeResult.Malformed
        }
    }

    fun encodeThumbnail(jpeg: ByteArray?): String? =
        jpeg?.takeIf { it.size <= MAX_THUMBNAIL_BYTES }?.let { Base64.getEncoder().encodeToString(it) }
}
