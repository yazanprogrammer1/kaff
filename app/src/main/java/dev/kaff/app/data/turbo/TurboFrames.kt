package dev.kaff.app.data.turbo

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format of one turbo stream (sender → receiver), after the receiver's 16-byte token and the
 * sender's 1-byte ack:
 *
 *   frame  = header(28) ‖ AES-256-GCM(chunk)            ciphertext = chunk length + 16-byte tag
 *   header = payloadId(8) ‖ fileSize(8) ‖ offset(8) ‖ chunkLength(4), big-endian, also the AAD
 *
 * Chunks of one file are striped over all streams and may arrive in any order; the receiver
 * writes each at its offset. The GCM nonce is the payload id's low 32 bits and the offset, unique
 * per key because ids are handed out sequentially and keys are fresh for every lane.
 */
internal object TurboFrames {
    const val HEADER_BYTES = 28
    const val TAG_BYTES = 16
    const val TOKEN_BYTES = 16
    const val KEY_BYTES = 32
    const val CHUNK_BYTES = 512 * 1024
    const val ACK: Int = 0x4B // 'K'

    data class Header(val payloadId: Long, val fileSize: Long, val offset: Long, val length: Int) {
        /** Rejects frames no honest sender produces, before any allocation or disk write. */
        fun valid(): Boolean =
            fileSize >= 0 && offset >= 0 && length in 0..CHUNK_BYTES && offset + length <= fileSize

        fun encode(into: ByteArray) {
            ByteBuffer.wrap(into).putLong(payloadId).putLong(fileSize).putLong(offset).putInt(length)
        }

        companion object {
            fun decode(bytes: ByteArray): Header = ByteBuffer.wrap(bytes).let {
                Header(it.long, it.long, it.long, it.int)
            }
        }
    }

    /** Reads the next header, or null on a clean end of stream between frames. */
    fun readHeader(input: DataInputStream, scratch: ByteArray): Header? {
        val first = input.read()
        if (first < 0) return null
        scratch[0] = first.toByte()
        input.readFully(scratch, 1, HEADER_BYTES - 1)
        return Header.decode(scratch)
    }

    fun tokensEqual(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun readToken(input: java.io.InputStream): ByteArray {
        val token = ByteArray(TOKEN_BYTES)
        var read = 0
        while (read < TOKEN_BYTES) {
            val n = input.read(token, read, TOKEN_BYTES - read)
            if (n < 0) throw EOFException("token")
            read += n
        }
        return token
    }

    fun writeFrame(out: OutputStream, header: ByteArray, sealed: ByteArray, sealedLength: Int) {
        out.write(header)
        out.write(sealed, 0, sealedLength)
    }
}

/** AES-256-GCM over turbo chunks. Not thread-safe: one per stream. */
internal class ChunkCipher(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val nonce = ByteArray(12)

    /** Encrypts [length] bytes of [plain] into [out] (at least length + 16 bytes); returns the sealed length. */
    fun seal(header: TurboFrames.Header, headerBytes: ByteArray, plain: ByteArray, length: Int, out: ByteArray): Int {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TurboFrames.TAG_BYTES * 8, nonceOf(header)))
        cipher.updateAAD(headerBytes)
        return cipher.doFinal(plain, 0, length, out, 0)
    }

    /** Decrypts and authenticates; throws [IOException] if the chunk was altered. */
    fun open(header: TurboFrames.Header, headerBytes: ByteArray, sealed: ByteArray, sealedLength: Int, out: ByteArray): Int = try {
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TurboFrames.TAG_BYTES * 8, nonceOf(header)))
        cipher.updateAAD(headerBytes)
        cipher.doFinal(sealed, 0, sealedLength, out, 0)
    } catch (e: java.security.GeneralSecurityException) {
        throw IOException("Turbo chunk failed authentication", e)
    }

    private fun nonceOf(header: TurboFrames.Header): ByteArray {
        ByteBuffer.wrap(nonce).putInt(header.payloadId.toInt()).putLong(header.offset)
        // Cipher.init copies the IV, so reusing the array is safe.
        return nonce
    }
}
