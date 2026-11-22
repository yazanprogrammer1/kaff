package dev.kaff.app.data.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real HTTP over loopback against the desktop server. */
class DesktopServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = listOf(
        "صورة.jpg" to Random(1).nextBytes(300_000),
        "notes.txt" to "hello".toByteArray(),
    )
    private val uploaded = mutableMapOf<String, ByteArray>()
    private val clipList = mutableListOf(DesktopServer.Clip(1, "https://kaff.dev", fromPhone = true, atMs = 0))
    private var approve: Boolean? = true
    private val approved = mutableSetOf<String>()
    private lateinit var server: DesktopServer
    private var port = 0

    @Before
    fun setUp() {
        server = DesktopServer(
            token = "abc23",
            catalog = object : DesktopServer.Catalog {
                override fun files() = files.mapIndexed { i, (name, bytes) -> DesktopServer.Entry(i, name, bytes.size.toLong(), "application/octet-stream") }
                override fun open(id: Int): InputStream? = files.getOrNull(id)?.let { ByteArrayInputStream(it.second) }
                override fun thumbnail(id: Int): ByteArray? = null
            },
            uploads = object : DesktopServer.Uploads {
                override suspend fun save(client: String, name: String, mime: String?, size: Long, input: InputStream): DesktopServer.UploadResult {
                    val bytes = input.readBytes()
                    if (bytes.size.toLong() != size) return DesktopServer.UploadResult.Failed
                    uploaded[name] = bytes
                    return DesktopServer.UploadResult.Saved
                }
            },
            clips = object : DesktopServer.Clips {
                override fun since(id: Long) = clipList.filter { it.id > id }
                override fun add(client: String, text: String) {
                    clipList += DesktopServer.Clip(clipList.size + 1L, text, fromPhone = false, atMs = 0)
                }
            },
            gate = object : DesktopServer.Gate {
                override fun isApproved(client: String) = client in approved
                override suspend fun request(client: String, agent: String, timeoutMs: Long, device: String?, computerName: String?): Boolean? =
                    approve.also { if (it == true) approved += client }
                override fun served(client: String, name: String, bytes: Long, upload: Boolean, durationMs: Long) = Unit
            },
            assets = object : DesktopServer.Assets {
                override fun page() = "<html>kaff</html>".toByteArray()
                override fun font(name: String): ByteArray? = null
                override val deviceName = "Infinix"
            },
            parent = scope,
        )
        port = server.start(0)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun request(path: String, method: String = "GET", body: ByteArray? = null): Pair<Int, ByteArray> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val stream = if (code >= 400) connection.errorStream else connection.inputStream
        return code to (stream?.use { it.readBytes() } ?: ByteArray(0))
    }

    @Test
    fun wrongTokenIsNotFound() {
        assertEquals(404, request("/zzzzz/").first)
        assertEquals(404, request("/").first)
    }

    @Test
    fun tokenWithoutSlashRedirects() {
        assertEquals(301, request("/abc23").first)
        assertEquals(200, request("/abc23/").first)
    }

    @Test
    fun filesNeedApproval() {
        assertEquals(403, request("/abc23/api/files").first)
        approve = false
        assertTrue(String(request("/abc23/api/hello").second).contains("\"approved\":false"))
        assertEquals(403, request("/abc23/file/0").first)
        approve = true
        assertTrue(String(request("/abc23/api/hello").second).contains("\"approved\":true"))
        val list = String(request("/abc23/api/files").second)
        assertTrue(list.contains("صورة.jpg") && list.contains("Infinix"))
    }

    @Test
    fun downloadUploadAndZip() {
        request("/abc23/api/hello")
        val (code, bytes) = request("/abc23/file/0")
        assertEquals(200, code)
        assertArrayEquals(files[0].second, bytes)

        val payload = Random(5).nextBytes(1_000_000)
        val (upCode, _) = request("/abc23/upload?name=%D9%81%D9%8A%D8%AF%D9%8A%D9%88.mp4", "PUT", payload)
        assertEquals(200, upCode)
        assertArrayEquals(payload, uploaded["فيديو.mp4"])

        val zip = request("/abc23/zip").second
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries[e.name] = ByteArrayOutputStream().also { z.copyTo(it) }.toByteArray()
            }
        }
        assertEquals(files.map { it.first }.toSet(), entries.keys)
        assertArrayEquals(files[0].second, entries["صورة.jpg"])
    }

    @Test
    fun sessionKeyFollowsTheBrowserAcrossAddresses() {
        val hello = String(request("/abc23/api/hello").second)
        val key = Regex("\"key\":\"([0-9a-f]{32})\"").find(hello)!!.groupValues[1]
        approved.clear() // as if the computer came back on another network address
        assertEquals(403, request("/abc23/api/files").first)
        assertEquals(200, request("/abc23/api/files?k=$key").first)
        assertEquals(403, request("/abc23/api/files?k=${"0".repeat(32)}").first)
        assertEquals(200, request("/abc23/api/ping").first)
    }

    @Test
    fun clipsTravelBothWays() {
        assertEquals(403, request("/abc23/api/clips").first)
        request("/abc23/api/hello")
        assertTrue(String(request("/abc23/api/clips").second).contains("https://kaff.dev"))
        assertEquals(200, request("/abc23/api/clip", "POST", "مرحبا من اللابتوب".toByteArray()).first)
        val newer = String(request("/abc23/api/clips?since=1").second)
        assertTrue(newer.contains("مرحبا من اللابتوب") && !newer.contains("kaff.dev"))
        assertEquals(413, request("/abc23/api/clip", "POST", ByteArray(70_000)).first)
    }

    @Test
    fun attachmentHeaderKeepsArabicNames() {
        val header = DesktopServer.attachment("صورة \"1\".jpg")
        assertTrue(header.startsWith("attachment; filename=\"____ _1_.jpg\""))
        assertTrue(header.contains("filename*=UTF-8''%D8%B5%D9%88%D8%B1%D8%A9%20%221%22.jpg"))
    }

    @Test
    fun zipNamesAreUnique() {
        val used = mutableSetOf<String>()
        assertEquals("a.jpg", DesktopServer.uniqueName("a.jpg", used))
        assertEquals("a (1).jpg", DesktopServer.uniqueName("A.jpg".lowercase(), used))
    }
}
