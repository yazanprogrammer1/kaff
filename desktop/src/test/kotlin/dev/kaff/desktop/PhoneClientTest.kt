package dev.kaff.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PhoneClient against a stand-in for the phone's HTTP API, on loopback. */
class PhoneClientTest {
    private lateinit var server: HttpServer
    private val payload = Random(4).nextBytes(3 * 1024 * 1024 + 17)
    private var uploaded: ByteArray? = null
    private var uploadLength: String? = null
    private var helloHeaders = mapOf<String, String>()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tok/") { ex ->
            val path = ex.requestURI.path.removePrefix("/tok/")
            when {
                path == "api/hello" -> {
                    helloHeaders = listOf("User-Agent", "X-Kaff-Device", "X-Kaff-Name").associateWith { ex.requestHeaders.getFirst(it) ?: "" }
                    val body = """{"approved":true,"device":"Phone","key":"${"a".repeat(32)}"}""".toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                }
                path == "file/1" -> {
                    ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.use { it.write(payload) }
                }
                path == "upload" -> {
                    uploadLength = ex.requestHeaders.getFirst("Content-Length")
                    uploaded = ex.requestBody.readBytes()
                    ex.sendResponseHeaders(200, -1); ex.close()
                }
                else -> { ex.sendResponseHeaders(404, -1); ex.close() }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun client() = PhoneClient("http://127.0.0.1:${server.address.port}/tok/", Identity.load())

    @Test
    fun helloIdentifiesTheDesktopApp() {
        val c = client()
        val hello = c.hello()
        assertTrue(hello.approved)
        assertEquals("a".repeat(32), c.key)
        assertTrue(helloHeaders["User-Agent"]!!.startsWith("KaffDesktop/"))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(helloHeaders["X-Kaff-Device"]!!))
        assertTrue(URLDecoder.decode(helloHeaders["X-Kaff-Name"], Charsets.UTF_8).isNotBlank())
    }

    @Test
    fun downloadAndUploadMoveEveryByte() {
        val c = client()
        val dir = Files.createTempDirectory("kaff")
        val target = dir.resolve("حلقة.mp4")
        var last = 0L
        c.download(PhoneFile(1, "حلقة.mp4", payload.size.toLong(), "video/mp4"), target) { last = it }
        assertContentEquals(payload, Files.readAllBytes(target))
        assertEquals(payload.size.toLong(), last)

        val status = c.upload(target, "video/mp4") {}
        assertEquals(200, status)
        assertEquals(payload.size.toString(), uploadLength)
        assertContentEquals(payload, uploaded)
        dir.toFile().deleteRecursively()
    }
}
