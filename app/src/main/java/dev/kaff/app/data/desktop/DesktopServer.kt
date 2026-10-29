package dev.kaff.app.data.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A tiny HTTP/1.1 server for the "send to desktop" page: the computer's browser opens
 * `http://<phone>:<port>/<token>/` on the same Wi-Fi (or on the phone's own hotspot), with nothing
 * to install and no internet.
 *
 * Every path lives under the random [token]; anything else is a 404, so other devices on the
 * network cannot even see the page. A browser must then be approved on the phone ([Gate]) before it
 * can list, download or upload anything. Host headers must be IP literals, which blocks DNS
 * rebinding from web pages the computer happens to have open.
 *
 * One request per connection (`Connection: close`) keeps the parser trivial; browsers open several
 * connections in parallel anyway.
 */
class DesktopServer(
    private val token: String,
    private val catalog: Catalog,
    private val uploads: Uploads,
    private val clips: Clips,
    private val gate: Gate,
    private val assets: Assets,
    parent: CoroutineScope,
) {
    /** Files the phone offers to the computer. */
    interface Catalog {
        fun files(): List<Entry>
        fun open(id: Int): InputStream?
        fun thumbnail(id: Int): ByteArray?

        /** Bytes of file [id] written to a browser so far, for the phone's progress bars. */
        fun progress(id: Int, bytes: Long) = Unit

        /** File [id] reached the browser completely. */
        fun delivered(id: Int, client: String, durationMs: Long) = Unit
    }

    /**
     * [batch] groups the files the phone sent with one "ready": the page downloads a new batch on its
     * own, like files copied over USB.
     */
    data class Entry(val id: Int, val name: String, val size: Long, val mime: String, val batch: Int = 0, val delivered: Boolean = false)

    enum class UploadResult { Saved, Cancelled, Failed }

    interface Uploads {
        /** Saves one upload of exactly [size] bytes; the phone's user may cancel it on the way. */
        suspend fun save(client: String, name: String, mime: String?, size: Long, input: InputStream): UploadResult
    }

    /** Text and links passed between the phone and the computer, oldest first. */
    interface Clips {
        fun since(id: Long): List<Clip>
        fun add(client: String, text: String)
    }

    data class Clip(val id: Long, val text: String, val fromPhone: Boolean, val atMs: Long)

    interface Gate {
        fun isApproved(client: String): Boolean

        /** Asks the phone's user; suspends until they answer or [timeoutMs] passes (null). */
        suspend fun request(client: String, agent: String, timeoutMs: Long): Boolean?

        /** A download or upload finished in [durationMs], for the phone's activity list and speed log. */
        fun served(client: String, name: String, bytes: Long, upload: Boolean, durationMs: Long)
    }

    interface Assets {
        fun page(): ByteArray
        fun font(name: String): ByteArray?
        val deviceName: String

        /** Kaff's own network, offered to computers that came in over a slower shared Wi-Fi. */
        fun fasterNetwork(client: String): HotspotInfo? = null

        /** Every address this page is served on (shared Wi-Fi, Kaff network), for a page that lost the phone. */
        fun alternates(): List<String> = emptyList()

        /** The computer cannot find Kaff's network: move it to 2.4 GHz, which every device sees. */
        fun requestCompatibleNetwork() = Unit
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private var server: ServerSocket? = null
    private val sessionKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val random = java.security.SecureRandom()

    private fun newSessionKey(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /** Binds to [preferredPort] (or any free port) on every interface and returns the port. */
    fun start(preferredPort: Int): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        try {
            socket.bind(InetSocketAddress(preferredPort), BACKLOG)
        } catch (e: IOException) {
            socket.bind(InetSocketAddress(0), BACKLOG)
        }
        server = socket
        scope.launch { acceptLoop(socket) }
        return socket.localPort
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        scope.cancel()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            scope.launch { handle(client) }
        }
    }

    private class Request(
        val method: String,
        val rawPath: String,
        val path: List<String>,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
    )

    private fun handle(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = READ_TIMEOUT_MS
                it.sendBufferSize = SOCKET_BUFFER
                it.receiveBufferSize = SOCKET_BUFFER
                val input = BufferedInputStream(it.getInputStream(), BUFFER)
                val out = BufferedOutputStream(it.getOutputStream(), 64 * 1024)
                val request = parse(input) ?: return respond(out, 400, "text/plain", "Bad request".toByteArray())
                val client = it.inetAddress.hostAddress ?: "?"
                route(request, client, out)
                out.flush()
            } catch (e: SocketException) {
                // Usually the browser went away (cancelled download, closed tab).
                android.util.Log.i("DesktopServer", "Connection ended: ${e.message}")
            } catch (e: IOException) {
                android.util.Log.w("DesktopServer", "Request failed: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: RuntimeException) {
                // A bug in a handler must not take the whole server down; the browser just retries.
                android.util.Log.e("DesktopServer", "Handler crashed", e)
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val line = input.readLine() ?: return null
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = input.readLine() ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0) return null
            headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) return null
        }
        val target = parts[1]
        val rawPath = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        return Request(
            method = parts[0],
            rawPath = rawPath,
            path = rawPath.split('/').filter { it.isNotEmpty() }.map(::decode),
            query = query,
            headers = headers,
            body = Bounded(input, length),
        )
    }

    private fun route(request: Request, client: String, out: OutputStream) {
        val host = request.headers["host"].orEmpty().substringBeforeLast(':').trim('[', ']')
        if (!IP_LITERAL.matches(host)) return respond(out, 421, "text/plain", "Open Kaff by its address".toByteArray())
        val path = request.path
        if (path.isEmpty() || !MessageDigest.isEqual(path[0].toByteArray(), token.toByteArray())) {
            return respond(out, 404, "text/html; charset=utf-8", LOST_PAGE.toByteArray())
        }
        val rest = path.drop(1)
        when {
            // Relative links (fonts, API) need the trailing slash.
            rest.isEmpty() && !request.rawPath.endsWith("/") -> {
                writeHead(out, 301, "text/plain", 0, mapOf("Location" to "/$token/"))
            }
            rest.isEmpty() && request.method == "GET" -> respond(out, 200, "text/html; charset=utf-8", assets.page())
            rest.size == 2 && rest[0] == "font" -> assets.font(rest[1])?.let { respond(out, 200, "font/ttf", it, cache = true) }
                ?: respond(out, 404, "text/plain", ByteArray(0))
            // Unauthenticated reachability probe: a page that lost the phone looks for it on the other network.
            rest == listOf("api", "ping") -> respond(out, 200, "text/plain", "kaff".toByteArray())
            rest == listOf("api", "hello") -> hello(request, client, out)
            !approved(request, client) -> respond(out, 403, "application/json", """{"error":"not_approved"}""".toByteArray())
            rest == listOf("api", "files") -> respond(out, 200, "application/json", filesJson().toByteArray())
            rest.size == 3 && rest[0] == "api" && rest[1] == "thumb" -> thumbnail(rest[2], out)
            rest.size == 2 && rest[0] == "file" -> download(rest[1], client, out)
            rest == listOf("zip") -> zip(client, out)
            rest == listOf("upload") && (request.method == "PUT" || request.method == "POST") -> upload(request, client, out)
            rest == listOf("api", "clips") && request.method == "GET" -> clipsJson(request.query["since"]?.toLongOrNull() ?: 0L, out)
            rest == listOf("api", "clip") && request.method == "POST" -> addClip(request, client, out)
            rest == listOf("api", "compat") && request.method == "POST" -> {
                assets.requestCompatibleNetwork()
                respond(out, 200, "application/json", """{"ok":true}""".toByteArray())
            }
            else -> respond(out, 404, "text/plain", ByteArray(0))
        }
    }

    /**
     * A browser is approved by its address, or by the session key it got at approval. The key lets
     * the same tab carry on after the computer moves between the shared Wi-Fi and Kaff's network
     * (its address changes) without asking on the phone again.
     */
    private fun approved(request: Request, client: String): Boolean {
        if (gate.isApproved(client)) return true
        val key = request.headers["x-kaff-key"] ?: request.query["k"] ?: return false
        return sessionKeys.any { MessageDigest.isEqual(it.toByteArray(), key.toByteArray()) }
    }

    private fun hello(request: Request, client: String, out: OutputStream) {
        val approved = approved(request, client) || runBlocking {
            gate.request(client, request.headers["user-agent"].orEmpty(), HELLO_WAIT_MS)
        } == true
        val key = if (approved) {
            request.headers["x-kaff-key"]?.takeIf { it in sessionKeys } ?: newSessionKey().also { sessionKeys += it }
        } else {
            null
        }
        val body = buildJsonObject {
            put("approved", approved)
            put("device", assets.deviceName)
            if (key != null) {
                put("key", key)
                put("alternates", buildJsonArray { assets.alternates().forEach { add(JsonPrimitive(it)) } })
            }
            if (approved) {
                assets.fasterNetwork(client)?.let { fast ->
                    put(
                        "fast",
                        buildJsonObject {
                            put("ssid", fast.ssid)
                            put("pass", fast.passphrase)
                        },
                    )
                }
            }
        }
        respond(out, 200, "application/json", body.toString().toByteArray())
    }

    private fun filesJson(): String = buildJsonObject {
        put("device", assets.deviceName)
        put(
            "files",
            buildJsonArray {
                catalog.files().forEach { file ->
                    add(
                        buildJsonObject {
                            put("id", file.id)
                            put("name", file.name)
                            put("size", file.size)
                            put("mime", file.mime)
                            put("batch", file.batch)
                            put("delivered", file.delivered)
                        },
                    )
                }
            },
        )
    }.toString()

    private fun thumbnail(id: String, out: OutputStream) {
        val bytes = id.toIntOrNull()?.let(catalog::thumbnail) ?: return respond(out, 404, "text/plain", ByteArray(0))
        respond(out, 200, "image/jpeg", bytes, cache = true)
    }

    private fun download(id: String, client: String, out: OutputStream) {
        val entry = catalog.files().firstOrNull { it.id.toString() == id } ?: return respond(out, 404, "text/plain", ByteArray(0))
        val input = catalog.open(entry.id) ?: return respond(out, 410, "text/plain", ByteArray(0))
        val started = System.nanoTime()
        input.use {
            // Unknown sizes (some providers) are sent without a length; the connection end marks the body end.
            writeHead(out, 200, entry.mime, entry.size.takeIf { s -> s > 0 }, mapOf("Content-Disposition" to attachment(entry.name)))
            val sent = copyReporting(it, out) { bytes -> catalog.progress(entry.id, bytes) }
            out.flush()
            if (entry.size <= 0 || sent >= entry.size) catalog.delivered(entry.id, client, elapsedMs(started))
            gate.served(client, entry.name, sent, upload = false, durationMs = elapsedMs(started))
        }
    }

    /** All files as one ZIP, streamed without compression (photos and videos are compressed already). */
    private fun zip(client: String, out: OutputStream) {
        val started = System.nanoTime()
        val files = catalog.files()
        val name = "Kaff-${files.size}.zip"
        writeHead(out, 200, "application/zip", null, mapOf("Content-Disposition" to attachment(name)))
        val used = HashSet<String>()
        var total = 0L
        ZipOutputStream(out).apply { setLevel(0) }.use { zip ->
            files.forEach { file ->
                val input = catalog.open(file.id) ?: return@forEach
                val fileStarted = System.nanoTime()
                zip.putNextEntry(ZipEntry(uniqueName(file.name, used)))
                input.use { total += copyReporting(it, zip) { bytes -> catalog.progress(file.id, bytes) } }
                zip.closeEntry()
                catalog.delivered(file.id, client, elapsedMs(fileStarted))
            }
        }
        gate.served(client, name, total, upload = false, durationMs = elapsedMs(started))
    }

    private fun upload(request: Request, client: String, out: OutputStream) {
        val name = request.query["name"]?.takeIf { it.isNotBlank() } ?: "upload"
        val size = request.headers["content-length"]?.toLongOrNull()
            ?: return respond(out, 411, "application/json", """{"error":"length_required"}""".toByteArray())
        val mime = request.query["type"]?.takeIf { it.isNotBlank() } ?: request.headers["content-type"]
        val started = System.nanoTime()
        val result = runBlocking { uploads.save(client, name, mime, size, request.body) }
        if (result == UploadResult.Saved) gate.served(client, name, size, upload = true, durationMs = elapsedMs(started))
        val status = when (result) {
            UploadResult.Saved -> 200
            UploadResult.Cancelled -> 409
            UploadResult.Failed -> 507
        }
        respond(out, status, "application/json", """{"ok":${result == UploadResult.Saved}}""".toByteArray())
    }

    private fun clipsJson(since: Long, out: OutputStream) {
        val body = buildJsonObject {
            put(
                "clips",
                buildJsonArray {
                    clips.since(since).forEach { clip ->
                        add(
                            buildJsonObject {
                                put("id", clip.id)
                                put("text", clip.text)
                                put("phone", clip.fromPhone)
                                put("at", clip.atMs)
                            },
                        )
                    }
                },
            )
        }
        respond(out, 200, "application/json", body.toString().toByteArray())
    }

    private fun addClip(request: Request, client: String, out: OutputStream) {
        val length = request.headers["content-length"]?.toLongOrNull() ?: 0L
        if (length !in 1..MAX_CLIP_BYTES) return respond(out, 413, "application/json", """{"ok":false}""".toByteArray())
        val text = request.body.readBytes().toString(Charsets.UTF_8).trim()
        if (text.isNotEmpty()) clips.add(client, text)
        respond(out, 200, "application/json", """{"ok":true}""".toByteArray())
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Copies everything, reporting progress about every 512 KB. */
    private fun copyReporting(input: InputStream, out: OutputStream, report: (Long) -> Unit): Long {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        var reported = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            if (total - reported >= REPORT_STEP) {
                reported = total
                report(total)
            }
        }
        report(total)
        return total
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, cache: Boolean = false) {
        writeHead(out, status, type, body.size.toLong(), if (cache) mapOf("Cache-Control" to "max-age=86400") else emptyMap())
        out.write(body)
    }

    private fun writeHead(out: OutputStream, status: Int, type: String, length: Long?, extra: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "OK").append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            if (length != null) append("Content-Length: ").append(length).append("\r\n")
            append("Connection: close\r\n")
            if ("Cache-Control" !in extra) append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Content-Security-Policy: default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; font-src 'self'\r\n")
            extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    /** Body reader that stops at Content-Length, so a slow browser cannot make us read past it. */
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = super.read()
            if (b >= 0) left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() = Unit
    }

    companion object {
        const val DEFAULT_PORT = 4999
        private const val BACKLOG = 32
        private const val READ_TIMEOUT_MS = 30_000
        private const val HELLO_WAIT_MS = 20_000L
        private const val MAX_HEADERS = 64
        private const val BUFFER = 256 * 1024
        private const val SOCKET_BUFFER = 4 * 1024 * 1024
        private const val REPORT_STEP = 512 * 1024L
        private const val MAX_CLIP_BYTES = 64 * 1024L
        private val IP_LITERAL = Regex("""^(\d{1,3}\.){3}\d{1,3}$|^[0-9a-fA-F:]+$""")
        private val REASONS = mapOf(
            200 to "OK", 301 to "Moved Permanently", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 410 to "Gone",
            409 to "Conflict", 411 to "Length Required", 413 to "Payload Too Large", 421 to "Misdirected Request", 507 to "Insufficient Storage",
        )
        private const val LOST_PAGE = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Kaff</title><body style="background:#07100C;color:#F2FAF5;font:18px system-ui;display:grid;place-items:center;height:100vh;margin:0;text-align:center">
<div dir="rtl"><h1 style="color:#2BE38B">كفّ</h1><p>اكتب العنوان كامل كما يظهر على شاشة الجوال.</p><p dir="ltr" style="opacity:.7">Type the full address shown on the phone.</p></div>"""

        private fun decode(value: String): String = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            value
        }

        /** RFC 6266 attachment with an ASCII fallback and the real (Arabic-safe) UTF-8 name. */
        internal fun attachment(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }

        internal fun uniqueName(name: String, used: MutableSet<String>): String {
            val clean = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
            var candidate = clean
            var n = 1
            val base = clean.substringBeforeLast('.')
            val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            while (!used.add(candidate.lowercase())) candidate = "$base (${n++})$ext"
            return candidate
        }

        /** Reads a CRLF (or LF) terminated ASCII line; null at end of stream or if it is too long. */
        private fun InputStream.readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().removeSuffix("\r")
                sb.append(b.toChar())
                if (sb.length > MAX_LINE) return null
            }
        }

        private const val MAX_LINE = 8 * 1024
    }
}
