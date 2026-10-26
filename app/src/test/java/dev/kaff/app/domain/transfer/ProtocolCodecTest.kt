package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.FileMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    private val meta = FileMeta("IMG_2841.jpg", 3_200_000, "image/jpeg", "ab".repeat(32))

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            ProtocolMessage.Hello(ProtocolCodec.VERSION, "Pixel 8"),
            ProtocolMessage.Offer("t1", listOf(meta, meta.copy(name = "b.mp4", mime = "video/mp4")), ProtocolCodec.encodeThumbnail(ByteArray(100) { it.toByte() })),
            ProtocolMessage.Armed("t1", 5_000),
            ProtocolMessage.Accept("t1"),
            ProtocolMessage.Reject("t1", ProtocolMessage.Reason.UserDeclined),
            ProtocolMessage.Cancel("t1", ProtocolMessage.Reason.Timeout),
            ProtocolMessage.FileHeader("t1", 1, 42L),
            ProtocolMessage.Done("t1", listOf(meta.sha256)),
        )
        messages.forEach { message ->
            val decoded = ProtocolCodec.decode(ProtocolCodec.encode(message))
            assertEquals(DecodeResult.Message(message), decoded)
        }
    }

    @Test
    fun `thumbnail bytes decode back`() {
        val thumb = ByteArray(500) { (it * 7).toByte() }
        val offer = ProtocolMessage.Offer("t", listOf(meta), ProtocolCodec.encodeThumbnail(thumb))
        val decoded = (ProtocolCodec.decode(ProtocolCodec.encode(offer)) as DecodeResult.Message).message as ProtocolMessage.Offer
        assertTrue(thumb.contentEquals(decoded.thumbnailBytes()))
    }

    @Test
    fun `oversized thumbnails are not encoded`() {
        assertNull(ProtocolCodec.encodeThumbnail(ByteArray(ProtocolCodec.MAX_THUMBNAIL_BYTES + 1)))
    }

    @Test
    fun `offer that would exceed the bytes limit drops its thumbnail`() {
        val files = List(150) { meta.copy(name = "file_with_a_rather_long_name_number_$it.jpg") }
        val offer = ProtocolMessage.Offer("t", files, ProtocolCodec.encodeThumbnail(ByteArray(ProtocolCodec.MAX_THUMBNAIL_BYTES)))
        val bytes = ProtocolCodec.encode(offer)
        assertTrue(bytes.size <= ProtocolCodec.MAX_BYTES)
        val decoded = (ProtocolCodec.decode(bytes) as DecodeResult.Message).message as ProtocolMessage.Offer
        assertNull(decoded.thumbnail)
        assertEquals(files, decoded.files)
    }

    @Test
    fun `other versions are reported, not parsed`() {
        val bytes = """{"v":2,"msg":{"type":"accept","transferId":"t"}}""".encodeToByteArray()
        assertEquals(DecodeResult.UnsupportedVersion(2), ProtocolCodec.decode(bytes))
    }

    @Test
    fun `garbage and unknown types are malformed`() {
        assertEquals(DecodeResult.Malformed, ProtocolCodec.decode("not json".encodeToByteArray()))
        assertEquals(DecodeResult.Malformed, ProtocolCodec.decode("""{"msg":{}}""".encodeToByteArray()))
        assertEquals(DecodeResult.Malformed, ProtocolCodec.decode("""{"v":1,"msg":{"type":"nope"}}""".encodeToByteArray()))
        assertEquals(DecodeResult.Malformed, ProtocolCodec.decode(ByteArray(ProtocolCodec.MAX_BYTES + 1)))
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val bytes = """{"v":1,"extra":true,"msg":{"type":"accept","transferId":"t","later":1}}""".encodeToByteArray()
        assertEquals(DecodeResult.Message(ProtocolMessage.Accept("t")), ProtocolCodec.decode(bytes))
    }
}
