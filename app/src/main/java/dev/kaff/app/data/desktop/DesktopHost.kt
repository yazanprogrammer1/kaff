package dev.kaff.app.data.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import androidx.annotation.RequiresApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.core.content.edit
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A computer browser that asked to connect. */
data class DesktopClient(val address: String, val browser: String, val os: String, val viaKaff: Boolean = false)

/** One download or upload, newest first on the screen. */
data class DesktopActivity(
    val name: String,
    val bytes: Long,
    val upload: Boolean,
    val saved: SavedFile? = null,
    val durationMs: Long = 0,
) {
    val bytesPerSecond: Double get() = if (durationMs <= 0) 0.0 else bytes * 1000.0 / durationMs
}

/** The phone's own Wi-Fi network for computers when there is no shared Wi-Fi. */
data class HotspotInfo(val ssid: String, val passphrase: String, val band: HotspotBand? = null)

enum class HotspotBand { Ghz24, Ghz5 }

data class DesktopState(
    val running: Boolean = false,
    /**
     * Address to type, e.g. http://192.168.1.23:4999/k7m2p/: on Kaff's own network when it is up
     * (direct, fastest), otherwise on the shared Wi-Fi. Null with no usable network.
     */
    val url: String? = null,
    /** The page on the shared Wi-Fi (phone and computer on the same network). */
    val wifiUrl: String? = null,
    /** The page on Kaff's own network, once it is up. */
    val kaffUrl: String? = null,
    val clips: List<DesktopServer.Clip> = emptyList(),
    val hotspot: HotspotInfo? = null,
    val hotspotStarting: Boolean = false,
    val hotspotFailed: Boolean = false,
    /** Another app took our Wi-Fi Direct group down; it is being brought back with the same name. */
    val hotspotHealing: Boolean = false,
    /** The phone is on a Wi-Fi network a computer can share. */
    val onWifi: Boolean = false,
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
    private val lastUploads = ConcurrentHashMap<String, SavedFile>()
    private val clips = ArrayList<DesktopServer.Clip>()
    private val clipIds = java.util.concurrent.atomic.AtomicLong()
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var directGroup = false
    @Volatile private var wantHotspot = false
    @Volatile private var healing = false
    @Volatile private var apAddress: String? = null
    private var missedGroup = 0
    private var p2pChannel: WifiP2pManager.Channel? = null
    private val page by lazy { context.assets.open("desktop/index.html").use { it.readBytes() } }

    /** Files the computer can download; replaces the previous list. */
    fun offer(list: List<PickItem>) {
        items = list
        thumbnails.clear()
    }

    fun start() {
        if (server != null) return refreshAddress()
        token = newToken()
        val created = DesktopServer(token, Catalog(), Uploads(), Clips(), Gate(), PageAssets(), scope)
        port = try {
            created.start(DesktopServer.DEFAULT_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Desktop server could not start", e)
            return
        }
        server = created
        // Keeps text queued from the share sheet before the screen opened.
        _state.value = DesktopState(running = true, clips = synchronized(clips) { clips.toList() })
        refreshAddress()
    }

    fun stop() {
        server?.stop()
        server = null
        decisions.values.forEach { it.complete(false) }
        decisions.clear()
        approvedAddresses.clear()
        synchronized(clips) { clips.clear() }
        stopHotspot()
        _state.value = DesktopState()
    }

    /** Re-reads the phone's address; Wi-Fi may have changed or the hotspot come up. */
    fun refreshAddress() {
        if (server == null) return
        val (station, ap) = localAddresses()
        apAddress = ap
        healGroup(groupUp = ap != null)
        val hotspotUp = _state.value.hotspot != null
        _state.update {
            it.copy(
                url = (if (hotspotUp) ap ?: station else station ?: ap)?.let(::urlFor),
                wifiUrl = station?.let(::urlFor),
                kaffUrl = ap?.takeIf { hotspotUp }?.let(::urlFor),
                onWifi = station != null,
            )
        }
    }

    private fun urlFor(address: String) = "http://$address:$port/$token/"

    /** Sends text or a link from the phone to the computer's page. */
    fun addClip(text: String) {
        val clean = text.trim().take(MAX_CLIP_CHARS)
        if (clean.isEmpty()) return
        synchronized(clips) {
            clips += DesktopServer.Clip(clipIds.incrementAndGet(), clean, fromPhone = true, atMs = System.currentTimeMillis())
            while (clips.size > MAX_CLIPS) clips.removeAt(0)
            _state.update { it.copy(clips = clips.toList()) }
        }
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

    /**
     * Starts Kaff's own Wi-Fi network (no internet) so a computer can join it directly.
     *
     * First choice is a Wi-Fi Direct group on 5 GHz with our own name and password: laptops join
     * it like any Wi-Fi network ("DIRECT-…"), and 5 GHz is several times faster than the 2.4 GHz
     * the system picks for a local-only hotspot. If the group cannot start (older Android, radio
     * busy), the local-only hotspot is the fallback.
     */
    fun startHotspot() {
        if (reservation != null || directGroup || _state.value.hotspotStarting || _state.value.hotspot != null) return
        wantHotspot = true
        _state.update { it.copy(hotspotStarting = true, hotspotFailed = false) }
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && startDirectGroup()) return@launch
            startLocalOnlyHotspot()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission") // The desktop screen asks for nearby Wi-Fi / location first; failures fall back.
    private suspend fun startDirectGroup(): Boolean {
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return false
        val channel = p2pChannel ?: manager.initialize(context, Looper.getMainLooper(), null).also { p2pChannel = it }
        val info = savedNetwork()
        // OEM services can leave the P2P state machine busy (ColorOS answers BUSY): clear it and retry.
        for (band in listOf(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)) {
            repeat(GROUP_ATTEMPTS) { attempt ->
                manager.p2pCall { manager.stopPeerDiscovery(channel, it) }
                manager.p2pCall { manager.removeGroup(channel, it) }
                delay(300)
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(info.ssid)
                    .setPassphrase(info.passphrase)
                    .setGroupOperatingBand(band)
                    .build()
                val ok = try {
                    manager.p2pCall { manager.createGroup(channel, config, it) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Wi-Fi Direct group needs nearby Wi-Fi / location", e)
                    return false
                }
                Log.i(TAG, "Wi-Fi Direct group band=$band attempt=${attempt + 1} ok=$ok")
                if (ok) {
                    directGroup = true
                    val actual = groupBand(manager, channel) ?: if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) HotspotBand.Ghz5 else null
                    _state.update { it.copy(hotspot = info.copy(band = actual), hotspotStarting = false) }
                    // The group interface gets its address a moment after the callback.
                    delay(1_200)
                    refreshAddress()
                    return true
                }
                delay(800)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun groupBand(manager: WifiP2pManager, channel: WifiP2pManager.Channel): HotspotBand? {
        delay(600)
        val frequency = suspendCancellableCoroutine { cont ->
            manager.requestGroupInfo(channel) { group ->
                val mhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group?.frequency ?: 0 else 0
                if (cont.isActive) cont.resume(mhz)
            }
        }
        return when {
            frequency >= 4_900 -> HotspotBand.Ghz5
            frequency in 2_400..2_500 -> HotspotBand.Ghz24
            else -> null
        }
    }

    /** Runs a WifiP2pManager call and waits for its listener; true on success. */
    private suspend fun WifiP2pManager.p2pCall(block: (WifiP2pManager.ActionListener) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onFailure(reason: Int) {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }

    @SuppressLint("MissingPermission")
    private fun startLocalOnlyHotspot() {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return hotspotFailed()
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
        wantHotspot = false
        missedGroup = 0
        reservation?.close()
        reservation = null
        if (directGroup) {
            directGroup = false
            val manager = context.getSystemService(WifiP2pManager::class.java)
            p2pChannel?.let { channel -> manager?.removeGroup(channel, null) }
        }
        _state.update { it.copy(hotspot = null, hotspotStarting = false, hotspotHealing = false) }
    }

    /**
     * Brings the Wi-Fi Direct group back when something else removed it (measured on ColorOS:
     * Microsoft's Link to Windows calls removeGroup ~45 s after a laptop joins). Same name and
     * password, so Windows reconnects on its own.
     */
    @SuppressLint("NewApi") // directGroup is only ever set on Android 10+.
    private fun healGroup(groupUp: Boolean) {
        if (!wantHotspot || !directGroup || healing) return
        if (groupUp) {
            if (missedGroup > 0) _state.update { it.copy(hotspotHealing = false) }
            missedGroup = 0
            return
        }
        if (++missedGroup < MISSED_BEFORE_HEAL) return
        healing = true
        Log.i(TAG, "Kaff network disappeared; bringing it back")
        _state.update { it.copy(hotspotHealing = true) }
        scope.launch {
            try {
                directGroup = false
                if (startDirectGroup()) {
                    missedGroup = 0
                    _state.update { it.copy(hotspotHealing = false) }
                } else {
                    directGroup = true
                }
            } finally {
                healing = false
            }
        }
    }

    private fun savedNetwork(): HotspotInfo {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = prefs.getString(KEY_SSID, null) ?: ("DIRECT-KF-Kaff-" + newToken().take(4).uppercase())
        val pass = prefs.getString(KEY_PASS, null) ?: newPassphrase()
        prefs.edit {
            putString(KEY_SSID, ssid)
            putString(KEY_PASS, pass)
        }
        return HotspotInfo(ssid, pass, HotspotBand.Ghz5)
    }

    @Suppress("DEPRECATION") // WifiConfiguration is the only source before Android 11.
    private fun hotspotInfo(res: WifiManager.LocalOnlyHotspotReservation): HotspotInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) config.wifiSsid?.toString()?.trim('"') else config.ssid
            // The band is not public for local-only hotspots; the system usually picks 2.4 GHz.
            ssid?.let { HotspotInfo(it, config.passphrase.orEmpty(), null) }
        } else {
            res.wifiConfiguration?.let { HotspotInfo(it.SSID.trim('"'), it.preSharedKey?.trim('"').orEmpty(), null) }
        }

    // endregion

    /** (shared Wi-Fi on wlan0, our own network: the Wi-Fi Direct group p2p-* or a soft-AP such as ap0 / wlan1). */
    private fun localAddresses(): Pair<String?, String?> {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && CELLULAR.none(it.name::startsWith) }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress } }
        } catch (e: SocketException) {
            emptyList()
        }
        val station = candidates.firstOrNull { it.first == "wlan0" }?.second
        val others = candidates.filter { it.first != "wlan0" }
        val ap = (if (directGroup) others.firstOrNull { it.first.startsWith("p2p") } else others.firstOrNull { !it.first.startsWith("p2p") })?.second
        return station to ap
    }

    /** Typed by hand on a laptop: 10 easy characters, no look-alikes. */
    private fun newPassphrase(): String {
        val random = SecureRandom()
        return String(CharArray(PASSPHRASE_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })
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
            if (saved != null) lastUploads["$client/$name"] = saved
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
            _state.update { if (it.pending == null) it.copy(pending = DesktopClient(client, browser, os, viaKaff(client))) else it }
            val result = withTimeoutOrNull(timeoutMs) { decision.await() }
            if (result != true) {
                decisions.remove(client, decision)
                // Unanswered: the page offers to try again, so the prompt should not linger here.
                if (result == null) _state.update { if (it.pending?.address == client) it.copy(pending = null) else it }
            }
            return result
        }

        override fun served(client: String, name: String, bytes: Long, upload: Boolean, durationMs: Long) {
            val saved = if (upload) lastUploads.remove("$client/$name") else null
            val entry = DesktopActivity(saved?.meta?.name ?: name, bytes, upload, saved, durationMs)
            Log.i(TAG, "${if (upload) "Upload" else "Download"} ${bytes / 1_048_576} MB in ${durationMs} ms = ${"%.1f".format(entry.bytesPerSecond / 1_048_576)} MB/s")
            _state.update { it.copy(activity = listOf(entry) + it.activity) }
        }
    }

    private inner class Clips : DesktopServer.Clips {
        override fun since(id: Long): List<DesktopServer.Clip> = synchronized(clips) { clips.filter { it.id > id } }

        override fun add(client: String, text: String) {
            val clean = text.take(MAX_CLIP_CHARS)
            synchronized(clips) {
                clips += DesktopServer.Clip(clipIds.incrementAndGet(), clean, fromPhone = false, atMs = System.currentTimeMillis())
                while (clips.size > MAX_CLIPS) clips.removeAt(0)
                _state.update { it.copy(clips = clips.toList()) }
            }
        }
    }

    /** True when [client] is on our own network (same /24 as the group or hotspot address). */
    private fun viaKaff(client: String): Boolean {
        val ap = apAddress ?: return false
        return ap.substringBeforeLast('.') == client.substringBeforeLast('.')
    }

    private inner class PageAssets : DesktopServer.Assets {
        override fun page(): ByteArray = page
        override val deviceName: String get() = device.name.value

        override fun fasterNetwork(client: String): HotspotInfo? = _state.value.hotspot?.takeIf { !viaKaff(client) }

        override fun alternates(): List<String> = listOfNotNull(_state.value.wifiUrl, _state.value.kaffUrl)

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
        private const val PASSPHRASE_LENGTH = 10
        private const val GROUP_ATTEMPTS = 3

        /** Address checks run every 3 s: two misses in a row means the group is really gone. */
        private const val MISSED_BEFORE_HEAL = 2
        private const val PREFS = "kaff_desktop"
        private const val KEY_SSID = "network_name"
        private const val KEY_PASS = "network_password"
        private const val MAX_CLIPS = 50
        private const val MAX_CLIP_CHARS = 20_000

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
