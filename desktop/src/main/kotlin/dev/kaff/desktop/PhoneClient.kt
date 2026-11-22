package dev.kaff.desktop

import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Hello(
    val approved: Boolean,
    val device: String = "",
    val key: String? = null,
    val alternates: List<String> = emptyList(),
    val fast: FastNetwork? = null,
)

@Serializable
data class FastNetwork(val ssid: String, val pass: String)

@Serializable
data class PhoneFile(val id: Int, val name: String, val size: Long, val mime: String, val batch: Int = 0, val delivered: Boolean = false)

@Serializable
private data class FileList(val device: String = "", val files: List<PhoneFile> = emptyList())

@Serializable
data class Clip(val id: Long, val text: String, val phone: Boolean, val at: Long)

@Serializable
private data class Clips(val clips: List<Clip> = emptyList())

/** The phone's local HTTP API (the same one the browser page uses), with this app's identity. */
class PhoneClient(val base: String, private val identity: Identity) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile var key: String? = null

    private fun request(path: String, timeout: Duration = Duration.ofSeconds(10)): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("User-Agent", "KaffDesktop/1.0 (Windows)")
            .header("X-Kaff-Device", identity.deviceId)
            .header("X-Kaff-Name", URLEncoder.encode(identity.computerName, Charsets.UTF_8))
            .apply { key?.let { header("X-Kaff-Key", it) } }

    /** Waits (up to ~25 s) for the phone's user to approve this computer, unless it is trusted already. */
    fun hello(): Hello {
        val response = http.send(request("api/hello", Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString())
        val hello = json.decodeFromString(Hello.serializer(), response.body())
        if (hello.key != null) key = hello.key
        return hello
    }

    fun files(): List<PhoneFile> {
        val response = http.send(request("api/files").GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "files ${response.statusCode()}" }
        return json.decodeFromString(FileList.serializer(), response.body()).files
    }

    fun clips(since: Long): List<Clip> {
        val response = http.send(request("api/clips?since=$since").GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "clips ${response.statusCode()}" }
        return json.decodeFromString(Clips.serializer(), response.body()).clips
    }

    fun sendClip(text: String): Boolean =
        http.send(request("api/clip").POST(HttpRequest.BodyPublishers.ofString(text, Charsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode() == 200

    fun askCompatibleNetwork() {
        runCatching { http.send(request("api/compat").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()) }
    }

    /** Streams a phone file into [target] (written to a .part file first), reporting bytes so far. */
    fun download(file: PhoneFile, target: Path, progress: (Long) -> Unit) {
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        val response = http.send(request("file/${file.id}", Duration.ofHours(6)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "download ${response.statusCode()}" }
        response.body().use { input ->
            Files.newOutputStream(part).use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                var reported = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total - reported >= STEP) {
                        reported = total
                        progress(total)
                    }
                }
                progress(total)
                check(file.size <= 0 || total == file.size) { "short download" }
            }
        }
        Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    /** Uploads [path] to the phone; returns the HTTP status (200 saved, 409 cancelled on the phone). */
    fun upload(path: Path, mime: String?, progress: (Long) -> Unit): Int {
        val size = Files.size(path)
        val name = URLEncoder.encode(path.fileName.toString(), Charsets.UTF_8).replace("+", "%20")
        val type = URLEncoder.encode(mime.orEmpty(), Charsets.UTF_8)
        val body = HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream { Counting(Files.newInputStream(path), progress) },
            size,
        )
        return http.send(
            request("upload?name=$name&type=$type", Duration.ofHours(6)).header("Content-Type", "application/octet-stream").PUT(body).build(),
            HttpResponse.BodyHandlers.discarding(),
        ).statusCode()
    }

    private class Counting(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        private var total = 0L
        private var reported = 0L
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total - reported >= STEP) {
                    reported = total
                    report(total)
                }
            }
            return n
        }
    }

    private companion object {
        const val STEP = 512 * 1024L
    }
}
