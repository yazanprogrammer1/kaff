package dev.kaff.app.data.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.R
import dev.kaff.app.core.di.ApplicationScope
import dev.kaff.app.domain.model.PickItem
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.LocalDevice
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** A computer browser that asked to connect. */
data class DesktopClient(val address: String, val browser: String, val os: String)

/** One download or upload, newest first on the screen. */
data class DesktopActivity(val name: String, val bytes: Long, val upload: Boolean, val saved: SavedFile? = null)

/** The phone's own Wi-Fi network for computers when there is no shared Wi-Fi. */
data class HotspotInfo(val ssid: String, val passphrase: String)

data class DesktopState(
    val running: Boolean = false,
    /** Full address to type, e.g. http://192.168.1.23:4999/k7m2p/, or null with no usable network. */
    val url: String? = null,
    val hotspot: HotspotInfo? = null,
    val hotspotStarting: Boolean = false,
    val hotspotFailed: Boolean = false,
    val approved: List<DesktopClient> = emptyList(),
    val pending: DesktopClient? = null,
    val activity: List<DesktopActivity> = emptyList(),
    /** Upload in progress: name to bytes so far / total. */
    val uploading: Map<String, Pair<Long, Long>> = emptyMap(),
)

/**
 * Runs the "send to desktop" page: a [DesktopServer] on the phone, the files it offers, approval of
 * each computer, and the optional local-only hotspot for places without Wi-Fi. Lives while the
 * desktop screen is open.
 */
@Singleton
class DesktopHost @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FileRepository,
    private val device: LocalDevice,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    @Volatile private var items: List<PickItem> = emptyList()
    private var server: DesktopServer? = null
    private var port = 0
    private var token = ""
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val approvedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val thumbnails = ConcurrentHashMap<Int, ByteArray>()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val page by lazy { context.assets.open("desktop/index.html").use { it.readBytes() } }

    /** Files the computer can download; replaces the previous list. */
    fun offer(list: List<PickItem>) {
        items = list
        thumbnails.clear()
    }

    fun start() {
        if (server != null) return refreshAddress()
        token = newToken()
        val created = DesktopServer(token, Catalog(), Uploads(), Gate(), PageAssets(), scope)
        port = try {
            created.start(DesktopServer.DEFAULT_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Desktop server could not start", e)
            return
        }
        server = created
        _state.value = DesktopState(running = true)
        refreshAddress()
    }

    fun stop() {
        server?.stop()
        server = null
        decisions.values.forEach { it.complete(false) }
        decisions.clear()
        approvedAddresses.clear()
        stopHotspot()
        _state.value = DesktopState()
    }

    /** Re-reads the phone's address; Wi-Fi may have changed or the hotspot come up. */
    fun refreshAddress() {
        if (server == null) return
        val address = localAddress(preferHotspot = _state.value.hotspot != null)
        _state.update { it.copy(url = address?.let { a -> "http://$a:$port/$token/" }) }
    }

    fun decide(client: DesktopClient, allow: Boolean) {
        if (allow) approvedAddresses += client.address
        decisions.remove(client.address)?.complete(allow)
        _state.update {
            it.copy(
                pending = null,
                approved = if (allow) (it.approved.filterNot { c -> c.address == client.address } + client) else it.approved,
            )
        }
    }

    // region Hotspot

    /** Starts the phone's own Wi-Fi network (no internet) so a computer can join it. */
    @SuppressLint("MissingPermission") // Location / nearby Wi-Fi are granted for Nearby already; failures are reported.
    fun startHotspot() {
        if (reservation != null || _state.value.hotspotStarting) return
        val wifi = context.getSystemService(WifiManager::class.java) ?: return hotspotFailed()
        _state.update { it.copy(hotspotStarting = true, hotspotFailed = false) }
        try {
            wifi.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = res
                        val info = hotspotInfo(res)
                        _state.update { it.copy(hotspot = info, hotspotStarting = false, hotspotFailed = info == null) }
                        // The AP interface gets its address a moment after the callback.
                        Handler(Looper.getMainLooper()).postDelayed({ refreshAddress() }, 1_200)
                        refreshAddress()
                    }

                    override fun onStopped() {
                        reservation = null
                        _state.update { it.copy(hotspot = null, hotspotStarting = false) }
                        refreshAddress()
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "Local-only hotspot failed: $reason")
                        hotspotFailed()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Hotspot needs location / nearby Wi-Fi permission", e)
            hotspotFailed()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Hotspot already requested", e)
            hotspotFailed()
        }
    }

    private fun hotspotFailed() {
        _state.update { it.copy(hotspotStarting = false, hotspotFailed = true) }
    }

    fun stopHotspot() {
        reservation?.close()
        reservation = null
        _state.update { it.copy(hotspot = null, hotspotStarting = false) }
    }

    @Suppress("DEPRECATION") // WifiConfiguration is the only source before Android 11.
    private fun hotspotInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) config.wifiSsid?.toString()?.trim('"') else config.ssid
            ssid?.let { HotspotInfo(it, config.passphrase.orEmpty()) }
        } else {
            res.wifiConfiguration?.let { HotspotInfo(it.SSID.trim('"'), it.preSharedKey?.trim('"').orEmpty()) }
        }

    // endregion

    /** wlan0 for shared Wi-Fi; the soft-AP interface (ap0, swlan0, wlan1…) once our hotspot is up. */
    private fun localAddress(preferHotspot: Boolean): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") && CELLULAR.none(it.name::startsWith) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress } }
        } catch (e: SocketException) {
            emptyList()
        }
        fun isStation(name: String) = name == "wlan0"
        val sorted = candidates.sortedBy { (name, _) ->
            when {
                preferHotspot && !isStation(name) -> 0
                isStation(name) -> 1
                else -> 2
            }
        }
        return sorted.firstOrNull()?.second
    }

    private fun newToken(): String {
        val random = SecureRandom()
        return String(CharArray(TOKEN_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })
    }

    private inner class Catalog : DesktopServer.Catalog {
        override fun files(): List<DesktopServer.Entry> = items.mapIndexed { i, item ->
            DesktopServer.Entry(i, item.name, item.size, item.mime)
        }

        override fun open(id: Int): InputStream? = items.getOrNull(id)?.let { item ->
            try {
                context.contentResolver.openInputStream(item.uri.toUri())
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

        override fun thumbnail(id: Int): ByteArray? {
            thumbnails[id]?.let { return it }
            val item = items.getOrNull(id) ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return try {
                val bitmap = context.contentResolver.loadThumbnail(item.uri.toUri(), Size(THUMB_PX, THUMB_PX), null)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
                    .also { bitmap.recycle(); thumbnails[id] = it }
            } catch (e: IOException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    private inner class Uploads : DesktopServer.Uploads {
        override suspend fun save(client: String, name: String, mime: String?, size: Long, input: InputStream): Boolean {
            val key = "$client/$name/${System.nanoTime()}"
            val counting = Counting(input) { bytes -> _state.update { it.copy(uploading = it.uploading + (key to (bytes to size))) } }
            val saved = try {
                files.import(name, mime, counting, expectedSize = size)
            } finally {
                _state.update { it.copy(uploading = it.uploading - key) }
            }
            if (saved != null) {
                _state.update { it.copy(activity = listOf(DesktopActivity(saved.meta.name, saved.meta.size, upload = true, saved = saved)) + it.activity) }
            }
            return saved != null
        }
    }

    /** Reports progress at most every [STEP] bytes so the state flow is not flooded. */
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

        companion object {
            const val STEP = 512 * 1024L
        }
    }

    private inner class Gate : DesktopServer.Gate {
        override fun isApproved(client: String): Boolean = client in approvedAddresses

        override suspend fun request(client: String, agent: String, timeoutMs: Long): Boolean? {
            val decision = decisions.getOrPut(client) { CompletableDeferred() }
            val (browser, os) = describeAgent(agent)
            _state.update { if (it.pending == null) it.copy(pending = DesktopClient(client, browser, os)) else it }
            val result = withTimeoutOrNull(timeoutMs) { decision.await() }
            if (result != true) {
                decisions.remove(client, decision)
                // Unanswered: the page offers to try again, so the prompt should not linger here.
                if (result == null) _state.update { if (it.pending?.address == client) it.copy(pending = null) else it }
            }
            return result
        }

        override fun served(client: String, name: String, bytes: Long, upload: Boolean) {
            if (!upload) _state.update { it.copy(activity = listOf(DesktopActivity(name, bytes, upload = false)) + it.activity) }
        }
    }

    private inner class PageAssets : DesktopServer.Assets {
        override fun page(): ByteArray = page
        override val deviceName: String get() = device.name.value

        override fun font(name: String): ByteArray? {
            val id = when (name) {
                "regular" -> R.font.tajawal_regular
                "medium" -> R.font.tajawal_medium
                "bold" -> R.font.tajawal_bold
                "extrabold" -> R.font.tajawal_extrabold
                else -> return null
            }
            return context.resources.openRawResource(id).use { it.readBytes() }
        }
    }

    companion object {
        private const val TAG = "DesktopHost"
        private const val TOKEN_LENGTH = 5

        /** No 0/o, 1/l/i: the token is typed by hand. */
        private const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val THUMB_PX = 320
        private val CELLULAR = listOf("rmnet", "ccmni", "pdp", "seth", "v4-rmnet", "dummy", "tun")

        /** "Chrome" / "Windows" from a user agent, for the approval prompt. */
        fun describeAgent(agent: String): Pair<String, String> {
            val browser = when {
                "Edg/" in agent -> "Edge"
                "OPR/" in agent -> "Opera"
                "Firefox/" in agent -> "Firefox"
                "Chrome/" in agent -> "Chrome"
                "Safari/" in agent -> "Safari"
                else -> "Browser"
            }
            val os = when {
                "Windows" in agent -> "Windows"
                "Mac OS X" in agent && "iPhone" !in agent && "iPad" !in agent -> "macOS"
                "iPhone" in agent || "iPad" in agent -> "iOS"
                "Android" in agent -> "Android"
                "CrOS" in agent -> "ChromeOS"
                "Linux" in agent -> "Linux"
                else -> ""
            }
            return browser to os
        }
    }
}
