package dev.kaff.app.data.desktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
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
import dev.kaff.app.domain.model.RecordFile
import dev.kaff.app.domain.model.TransferDirection
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.HistoryRepository
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
data class DesktopClient(
    val address: String,
    val browser: String,
    val os: String,
    val viaKaff: Boolean = false,
    /** Stable id sent by the Kaff desktop app; null for a plain browser. */
    val device: String? = null,
    /** The computer's own name, from the desktop app. */
    val computerName: String? = null,
) {
    val label: String get() = computerName ?: listOf(browser, os).filter(String::isNotBlank).joinToString(" · ")
}

/** A file the phone sent to the computer with "ready"; the page downloads it on its own. */
data class DesktopOutgoing(
    val id: Int,
    val item: PickItem,
    val batch: Int,
    val sentBytes: Long = 0,
    val done: Boolean = false,
    val durationMs: Long = 0,
)

/** A file the computer is uploading to the phone, or did. */
data class DesktopIncoming(
    val id: Long,
    val name: String,
    val mime: String,
    val total: Long,
    val bytes: Long = 0,
    val startedAtMs: Long,
    val from: String = "",
    val saved: SavedFile? = null,
    val failed: Boolean = false,
    val cancelled: Boolean = false,
    val finishedAtMs: Long = 0,
) {
    val active: Boolean get() = saved == null && !failed && !cancelled
    val bytesPerSecond: Double
        get() {
            val ms = (if (finishedAtMs > 0) finishedAtMs else System.currentTimeMillis()) - startedAtMs
            return if (ms <= 0) 0.0 else bytes * 1000.0 / ms
        }
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
    /** Kaff network on 2.4 GHz for computers without 5 GHz. */
    val compatible: Boolean = false,
    val approved: List<DesktopClient> = emptyList(),
    val pending: DesktopClient? = null,
    /** Sent to the computer this session, newest batch last. */
    val outgoing: List<DesktopOutgoing> = emptyList(),
    /** From the computer this session, newest first. */
    val incoming: List<DesktopIncoming> = emptyList(),
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
    private val history: HistoryRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DesktopState())
    val state: StateFlow<DesktopState> = _state.asStateFlow()

    private val outgoing = ArrayList<DesktopOutgoing>()
    private val outgoingIds = java.util.concurrent.atomic.AtomicInteger()
    private val batches = java.util.concurrent.atomic.AtomicInteger()
    private val recordedBatches: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val uploadIds = java.util.concurrent.atomic.AtomicLong()
    private val cancelledUploads: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private var server: DesktopServer? = null
    private var port = 0
    private var token = ""
    private val decisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val approvedAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val thumbnails = ConcurrentHashMap<Int, ByteArray>()
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

    /** Answers the desktop app's "KAFF?" so it finds this phone by itself. */
    private val beacon = DesktopBeacon(
        scope,
        { context.getSystemService(WifiManager::class.java)?.createMulticastLock("kaff-beacon") },
    ) {
        if (server == null) null else DesktopBeacon.Answer(phoneId, device.name.value, port, token, _state.value.hotspot?.ssid)
    }

    /**
     * Sends [items] to the computer as one batch: they show up on the page and download there by
     * themselves. Works before the screen opens too (share sheet), they wait for the computer.
     */
    fun push(items: List<PickItem>) {
        val usable = items.filterNot { it.unsupported }
        if (usable.isEmpty()) return
        val batch = batches.incrementAndGet()
        synchronized(outgoing) {
            usable.forEach { outgoing += DesktopOutgoing(outgoingIds.incrementAndGet(), it, batch) }
            _state.update { it.copy(outgoing = outgoing.toList()) }
        }
    }

    fun clearOutgoing() {
        synchronized(outgoing) {
            outgoing.clear()
            _state.update { it.copy(outgoing = emptyList()) }
        }
        thumbnails.clear()
    }

    /** Stops an upload from the computer; what arrived so far is deleted. */
    fun cancelIncoming(id: Long) {
        if (_state.value.incoming.any { it.id == id && it.active }) cancelledUploads += id
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
        beacon.start()
        // Keeps text and files queued from the share sheet before the screen opened.
        _state.value = DesktopState(
            running = true,
            clips = synchronized(clips) { clips.toList() },
            outgoing = synchronized(outgoing) { outgoing.toList() },
            compatible = compatibleMode(),
        )
        refreshAddress()
    }

    fun stop() {
        beacon.stop()
        server?.stop()
        server = null
        decisions.values.forEach { it.complete(false) }
        decisions.clear()
        approvedAddresses.clear()
        synchronized(clips) { clips.clear() }
        synchronized(outgoing) { outgoing.clear() }
        recordedBatches.clear()
        cancelledUploads.clear()
        thumbnails.clear()
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

    /**
     * [remember] trusts a desktop app's computer from now on: it connects without asking next time.
     * Browsers have no stable id, so they are approved for this session only.
     */
    fun decide(client: DesktopClient, allow: Boolean, remember: Boolean = true) {
        if (allow) approvedAddresses += client.address
        if (allow && remember && client.device != null) trust(client)
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
        // Our group is already up (a retry, or a heal racing the system): keep it, so joined laptops stay.
        // Only a group on a channel every laptop scans (2.4 GHz or 5 GHz channels 36-64) is worth keeping.
        val compat = compatibleMode()
        val existing = groupInfo(manager, channel)
        val keep = if (compat) 2_400..2_500 else 2_400..5_340
        if (existing?.networkName == info.ssid && existing.frequency in keep) {
            return groupUp(manager, channel, info, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
        }
        // OEM services can leave the P2P state machine busy (ColorOS answers BUSY): clear it and retry.
        // Same channel as the phone's own Wi-Fi first: the radio never has to hop between two channels,
        // which keeps long transfers steady (hopping 6 <-> 11 dropped a 200 MB download, 2026-10-29).
        val station = stationFrequency()
        val shared = station?.takeIf { if (compat) it in 2_400..2_500 else it in 5_170..5_330 }
        for (choice in listOfNotNull(shared) + if (compat) COMPATIBLE_CHOICES else CHANNEL_CHOICES) {
            repeat(GROUP_ATTEMPTS) { attempt ->
                manager.p2pCall { manager.stopPeerDiscovery(channel, it) }
                manager.p2pCall { manager.removeGroup(channel, it) }
                delay(300)
                val builder = WifiP2pConfig.Builder()
                    .setNetworkName(info.ssid)
                    .setPassphrase(info.passphrase)
                val config = try {
                    (if (choice > 1_000) builder.setGroupOperatingFrequency(choice) else builder.setGroupOperatingBand(choice)).build()
                } catch (e: IllegalArgumentException) {
                    return@repeat
                }
                val ok = try {
                    manager.p2pCall { manager.createGroup(channel, config, it) }
                } catch (e: SecurityException) {
                    Log.w(TAG, "Wi-Fi Direct group needs nearby Wi-Fi / location", e)
                    return false
                }
                Log.i(TAG, "Wi-Fi Direct group choice=$choice attempt=${attempt + 1} ok=$ok")
                val band = if (choice == WifiP2pConfig.GROUP_OWNER_BAND_AUTO) choice else WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                if (ok) return groupUp(manager, channel, info, band)
                // Measured on ColorOS: createGroup can answer BUSY and still bring the group up a second
                // later. Removing it on the next attempt would kick out a laptop that is joining.
                delay(1_200)
                if (groupInfo(manager, channel)?.networkName == info.ssid) {
                    Log.i(TAG, "Wi-Fi Direct group came up despite the failure answer")
                    return groupUp(manager, channel, info, band)
                }
            }
        }
        return false
    }

    /** Frequency of the Wi-Fi network the phone is on, if any. */
    @Suppress("DEPRECATION") // connectionInfo is still the simple way to read the station frequency.
    private fun stationFrequency(): Int? =
        context.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency?.takeIf { it > 0 }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun groupUp(manager: WifiP2pManager, channel: WifiP2pManager.Channel, info: HotspotInfo, band: Int): Boolean {
        directGroup = true
        val actual = groupBand(manager, channel) ?: if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) HotspotBand.Ghz5 else null
        _state.update { it.copy(hotspot = info.copy(band = actual), hotspotStarting = false) }
        // The group interface gets its address a moment after the callback.
        delay(1_200)
        refreshAddress()
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun groupInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pGroup? =
        withTimeoutOrNull(2_000) {
            suspendCancellableCoroutine { cont ->
                manager.requestGroupInfo(channel) { group -> if (cont.isActive) cont.resume(group) }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun groupBand(manager: WifiP2pManager, channel: WifiP2pManager.Channel): HotspotBand? {
        delay(600)
        val frequency = groupInfo(manager, channel)?.frequency ?: 0
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

    /**
     * 2.4 GHz for laptops that cannot use 5 GHz at all. Measured on an Intel AX201 whose regulatory
     * mask allows no 5 GHz channel (ChannelMaskBand52 = 0): it never sees a 5 GHz network.
     */
    private fun compatibleMode(): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_COMPAT, false)

    /** Switches Kaff's network between 5 GHz (fast) and 2.4 GHz (every device); remembered. */
    fun setCompatibleMode(compatible: Boolean) {
        if (compatibleMode() == compatible && _state.value.hotspot != null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_COMPAT, compatible) }
        _state.update { it.copy(compatible = compatible) }
        if (!wantHotspot && _state.value.hotspot == null) return
        stopHotspot()
        scope.launch {
            // Give the radio a moment to tear the old group down before the new band comes up.
            delay(800)
            startHotspot()
        }
    }

    /**
     * Lets a computer tell this phone apart from another with the same name (two identical models).
     * Random and kept in app storage; it says nothing about the device or its owner.
     */
    private val phoneId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PHONE_ID, null) ?: newPassphrase().also { prefs.edit { putString(KEY_PHONE_ID, it) } }
    }

    private fun trustedDevices(): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_TRUSTED, emptySet()).orEmpty()

    private fun trust(client: DesktopClient) {
        val id = client.device ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(KEY_TRUSTED, trustedDevices() + id)
            putString("$KEY_TRUSTED_NAME$id", client.label)
        }
    }

    /** Forgets every trusted computer; each must be approved again. */
    fun forgetComputers() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            trustedDevices().forEach { remove("$KEY_TRUSTED_NAME$it") }
            remove(KEY_TRUSTED)
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
        return HotspotInfo(ssid, pass, if (compatibleMode()) HotspotBand.Ghz24 else HotspotBand.Ghz5)
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
        override fun files(): List<DesktopServer.Entry> = synchronized(outgoing) {
            outgoing.map { DesktopServer.Entry(it.id, it.item.name, it.item.size, it.item.mime, it.batch, it.done) }
        }

        private fun item(id: Int): PickItem? = synchronized(outgoing) { outgoing.firstOrNull { it.id == id }?.item }

        override fun open(id: Int): InputStream? = item(id)?.let { item ->
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
            val item = item(id) ?: return null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || item.packageName != null) return null
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

        override fun progress(id: Int, bytes: Long) = updateOutgoing(id) { it.copy(sentBytes = maxOf(it.sentBytes, bytes)) }

        override fun delivered(id: Int, client: String, durationMs: Long) {
            updateOutgoing(id) { it.copy(sentBytes = it.item.size, done = true, durationMs = durationMs) }
            val batch = synchronized(outgoing) {
                val file = outgoing.firstOrNull { it.id == id } ?: return
                outgoing.filter { it.batch == file.batch }
            }
            // One history entry per "ready", once the whole batch is on the computer.
            if (batch.all { it.done } && recordedBatches.add(batch.first().batch)) {
                record(
                    TransferDirection.Sent,
                    client,
                    batch.map { RecordFile(it.item.uri, it.item.name, it.item.mime, it.item.size) },
                    batch.sumOf { it.durationMs },
                )
            }
        }
    }

    private fun updateOutgoing(id: Int, change: (DesktopOutgoing) -> DesktopOutgoing) {
        synchronized(outgoing) {
            val index = outgoing.indexOfFirst { it.id == id }
            if (index < 0) return
            outgoing[index] = change(outgoing[index])
            _state.update { it.copy(outgoing = outgoing.toList()) }
        }
    }

    private fun updateIncoming(id: Long, change: (DesktopIncoming) -> DesktopIncoming) {
        _state.update { state -> state.copy(incoming = state.incoming.map { if (it.id == id) change(it) else it }) }
    }

    private inner class Uploads : DesktopServer.Uploads {
        override suspend fun save(client: String, name: String, mime: String?, size: Long, input: InputStream): DesktopServer.UploadResult {
            val id = uploadIds.incrementAndGet()
            val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val entry = DesktopIncoming(id, name, type, size, startedAtMs = System.currentTimeMillis(), from = clientLabel(client))
            _state.update { it.copy(incoming = listOf(entry) + it.incoming) }
            val counting = Counting(input, cancelled = { id in cancelledUploads }) { bytes -> updateIncoming(id) { it.copy(bytes = bytes) } }
            val saved = try {
                files.import(name, mime, counting, expectedSize = size)
            } catch (e: CancelledUpload) {
                null
            }
            val cancelled = cancelledUploads.remove(id)
            updateIncoming(id) {
                it.copy(
                    bytes = if (saved != null) size else it.bytes,
                    saved = saved,
                    cancelled = cancelled,
                    failed = saved == null && !cancelled,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
            if (saved != null) {
                record(
                    TransferDirection.Received,
                    client,
                    listOf(RecordFile(saved.uri, saved.meta.name, saved.meta.mime, saved.meta.size)),
                    System.currentTimeMillis() - entry.startedAtMs,
                    saved.uri,
                )
            }
            return when {
                saved != null -> DesktopServer.UploadResult.Saved
                cancelled -> DesktopServer.UploadResult.Cancelled
                else -> DesktopServer.UploadResult.Failed
            }
        }
    }

    private class CancelledUpload : IOException("Cancelled on the phone")

    /** Reports progress at most every [STEP] bytes so the state flow is not flooded; stops when cancelled. */
    private class Counting(
        input: InputStream,
        private val cancelled: () -> Boolean,
        private val report: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var total = 0L
        private var reported = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancelled()) throw CancelledUpload()
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

    private fun clientLabel(client: String): String =
        _state.value.approved.firstOrNull { it.address == client }?.let { listOf(it.browser, it.os).filter(String::isNotBlank).joinToString(" · ") }
            ?: context.getString(R.string.desktop_computer)

    private fun record(direction: TransferDirection, client: String, recordFiles: List<RecordFile>, durationMs: Long, savedUri: String? = null) {
        val first = recordFiles.firstOrNull() ?: return
        scope.launch {
            history.add(
                TransferRecord(
                    direction = direction,
                    peerName = clientLabel(client),
                    fileCount = recordFiles.size,
                    totalBytes = recordFiles.sumOf { it.size },
                    firstFileName = first.name,
                    firstMime = first.mime,
                    success = true,
                    durationMs = durationMs,
                    createdAtMs = System.currentTimeMillis(),
                    savedUri = savedUri ?: first.uri,
                    files = recordFiles,
                    desktop = true,
                ),
            )
        }
    }

    private inner class Gate : DesktopServer.Gate {
        override fun isApproved(client: String): Boolean = client in approvedAddresses

        override suspend fun request(client: String, agent: String, timeoutMs: Long, device: String?, computerName: String?): Boolean? {
            val (browser, os) = describeAgent(agent)
            val asking = DesktopClient(client, browser, os, viaKaff(client), device, computerName)
            if (device != null && device in trustedDevices()) {
                // Approved before on this phone: straight in, like a paired Bluetooth device.
                approvedAddresses += client
                _state.update { it.copy(approved = it.approved.filterNot { c -> c.address == client } + asking) }
                return true
            }
            val decision = decisions.getOrPut(client) { CompletableDeferred() }
            _state.update { if (it.pending == null) it.copy(pending = asking) else it }
            val result = withTimeoutOrNull(timeoutMs) { decision.await() }
            if (result != true) {
                decisions.remove(client, decision)
                // Unanswered: the page offers to try again, so the prompt should not linger here.
                if (result == null) _state.update { if (it.pending?.address == client) it.copy(pending = null) else it }
            }
            return result
        }

        override fun served(client: String, name: String, bytes: Long, upload: Boolean, durationMs: Long) {
            val speed = if (durationMs <= 0) 0.0 else bytes * 1000.0 / durationMs / 1_048_576
            Log.i(TAG, "${if (upload) "Upload" else "Download"} ${bytes / 1_048_576} MB in $durationMs ms = ${"%.1f".format(speed)} MB/s")
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

        override fun requestCompatibleNetwork() = setCompatibleMode(true)

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
        private const val GROUP_ATTEMPTS = 2

        /**
         * Where to put the group, best first: channels 36 and 48 (5180 / 5240 MHz) are allowed in every
         * region and scanned by every laptop. Left to itself the phone may pick channel 149 (5745 MHz),
         * which an Intel AX201 in passive-scan mode never sees (measured 2026-10-29: "network not
         * available"). Then any 5 GHz channel, and finally whatever the radio allows (2.4 GHz).
         */
        @SuppressLint("InlinedApi") // Compile-time ints; only read on Android 10+.
        private val CHANNEL_CHOICES = listOf(5_180, 5_240, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)

        /** 2.4 GHz only; the system picks the phone's own Wi-Fi channel when it can (steadier). */
        @SuppressLint("InlinedApi") // Compile-time ints; only read on Android 10+.
        private val COMPATIBLE_CHOICES = listOf(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)

        /** Address checks run every 3 s: two misses in a row means the group is really gone. */
        private const val MISSED_BEFORE_HEAL = 2
        private const val PREFS = "kaff_desktop"
        private const val KEY_SSID = "network_name"
        private const val KEY_PASS = "network_password"
        private const val KEY_COMPAT = "network_compatible"
        private const val KEY_TRUSTED = "trusted_computers"
        private const val KEY_PHONE_ID = "phone_id"
        private const val KEY_TRUSTED_NAME = "trusted_name_"
        private const val MAX_CLIPS = 50
        private const val MAX_CLIP_CHARS = 20_000

        /** No 0/o, 1/l/i: the token is typed by hand. */
        private const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val THUMB_PX = 320
        private val CELLULAR = listOf("rmnet", "ccmni", "pdp", "seth", "v4-rmnet", "dummy", "tun")

        /** "Chrome" / "Windows" from a user agent, for the approval prompt. */
        fun describeAgent(agent: String): Pair<String, String> {
            val browser = when {
                "KaffDesktop/" in agent -> "Kaff"
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
