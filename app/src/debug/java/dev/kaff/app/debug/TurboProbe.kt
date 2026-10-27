package dev.kaff.app.debug

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * Debug-only throughput probe: measures what raw Wi-Fi Direct (5 GHz) + N parallel TCP streams
 * can do between two phones, to decide whether a custom fast lane beats Nearby's own upgrade.
 *
 *   adb shell am broadcast -n dev.kaff.app/.debug.TurboProbe -a dev.kaff.app.TURBO_PROBE --es role server
 *   adb shell am broadcast -n dev.kaff.app/.debug.TurboProbe -a dev.kaff.app.TURBO_PROBE --es role client --ei streams 4 --ei mb 500
 *   adb shell am broadcast -n dev.kaff.app/.debug.TurboProbe -a dev.kaff.app.TURBO_PROBE --es role stop
 *
 * Results go to logcat tag "TurboProbe". Payload is a zero-filled buffer, never user data.
 */
class TurboProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Needs Android 10+ for WifiP2pConfig.Builder")
            return
        }
        val app = context.applicationContext
        when (intent.getStringExtra("role")) {
            "server" -> run { server(app, intent.getIntExtra("band", WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)) }
            "client" -> run { client(app, intent.getIntExtra("streams", 4), intent.getIntExtra("mb", 500), intent.getIntExtra("buffer_kb", 1024)) }
            "stop" -> run { stop(app) }
            else -> Log.w(TAG, "role must be server, client or stop")
        }
    }

    /** One probe at a time; a new command replaces the running one. Errors are logged, not thrown. */
    private fun run(block: suspend () -> Unit) {
        current?.cancel()
        // A blocking accept() ignores coroutine cancellation; closing the socket releases the port.
        runCatching { listening?.close() }
        current = scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "probe failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES / location are granted for Nearby already.
    private suspend fun server(context: Context, band: Int) {
        val (manager, channel) = p2p(context)
        manager.removeGroupQuietly(channel)
        val config = WifiP2pConfig.Builder()
            .setNetworkName(SSID)
            .setPassphrase(PASSPHRASE)
            .setGroupOperatingBand(band)
            .build()
        val created = manager.call { l -> manager.createGroup(channel, config, l) }
        Log.i(TAG, "server: createGroup ok=$created band=$band")
        delay(1_500)
        manager.requestGroupInfo(channel) { g -> Log.i(TAG, "server: group freq=${g?.frequency} ssid=${g?.networkName} iface=${g?.`interface`}") }

        ServerSocket().use { server ->
            listening = server
            server.reuseAddress = true
            server.receiveBufferSize = 4 * 1024 * 1024
            server.bind(InetSocketAddress(PORT))
            Log.i(TAG, "server: listening on $PORT")
            while (true) {
                val first = server.accept()
                // The client tells how many streams follow in its first byte.
                val streams = first.getInputStream().read().coerceIn(1, 16)
                // Every stream starts with the same count byte; skip it on the others.
                val sockets = listOf(first) + List(streams - 1) { server.accept().also { it.getInputStream().read() } }
                val start = SystemClock.elapsedRealtime()
                val totals = sockets.map { s ->
                    scope.async {
                        s.use {
                            it.receiveBufferSize = 4 * 1024 * 1024
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            val input = it.getInputStream()
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                            }
                            total
                        }
                    }
                }.awaitAll()
                val ms = SystemClock.elapsedRealtime() - start
                val bytes = totals.sum()
                Log.i(TAG, "server: RESULT received ${bytes / 1_048_576} MB over $streams streams in $ms ms = ${"%.1f".format(bytes / 1_048_576.0 / (ms / 1000.0))} MB/s")
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private suspend fun client(context: Context, streams: Int, mb: Int, bufferKb: Int) {
        val (manager, channel) = p2p(context)
        // OEM services (e.g. ColorOS accessory) can leave the P2P state machine busy: clear it first
        // and retry, as a production client has to.
        manager.call { l -> manager.stopPeerDiscovery(channel, l) }
        manager.call { l -> manager.cancelConnect(channel, l) }
        manager.removeGroupQuietly(channel)
        val joined = waitForGroup(context) {
            scope.launch {
                val config = WifiP2pConfig.Builder().setNetworkName(SSID).setPassphrase(PASSPHRASE).build()
                repeat(4) { attempt ->
                    val ok = manager.call { l -> manager.connect(channel, config, l) }
                    Log.i(TAG, "client: connect attempt=${attempt + 1} ok=$ok")
                    if (ok) return@launch
                    delay(1_500)
                }
            }
        }
        if (joined == null) {
            Log.w(TAG, "client: no group within 20 s")
            return
        }
        Log.i(TAG, "client: joined, owner=${joined.groupOwnerAddress?.hostAddress}")
        val host = joined.groupOwnerAddress ?: return
        delay(1_000)
        // Without an explicit bind Android may route 192.168.49.1 over the regular Wi-Fi interface.
        val local = p2pAddress()
        Log.i(TAG, "client: local p2p address=${local?.hostAddress}")
        val perStream = mb.toLong() * 1_048_576 / streams
        val start = SystemClock.elapsedRealtime()
        (0 until streams).map { i ->
            scope.async {
                Socket().use { s ->
                    s.sendBufferSize = 4 * 1024 * 1024
                    s.tcpNoDelay = false
                    if (local != null) s.bind(InetSocketAddress(local, 0))
                    s.connect(InetSocketAddress(host, PORT), 5_000)
                    val out = s.getOutputStream()
                    out.write(streams)
                    val buffer = ByteArray(bufferKb * 1024)
                    var left = perStream
                    while (left > 0) {
                        val n = minOf(left, buffer.size.toLong()).toInt()
                        out.write(buffer, 0, n)
                        left -= n
                    }
                    out.flush()
                }
            }
        }.awaitAll()
        val ms = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "client: RESULT sent $mb MB over $streams streams (buffer ${bufferKb}KB) in $ms ms = ${"%.1f".format(mb / (ms / 1000.0))} MB/s")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun stop(context: Context) {
        val (manager, channel) = p2p(context)
        manager.removeGroupQuietly(channel)
        Log.i(TAG, "stopped")
    }

    /** IPv4 address of this phone on the Wi-Fi Direct group (interface p2p-*), if any. */
    private fun p2pAddress(): Inet4Address? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.name.startsWith("p2p") && it.isUp }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull()

    private fun p2p(context: Context): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val manager = checkNotNull(context.getSystemService(WifiP2pManager::class.java))
        return manager to manager.initialize(context, Looper.getMainLooper(), null)
    }

    private suspend fun WifiP2pManager.removeGroupQuietly(channel: WifiP2pManager.Channel) {
        call { l -> removeGroup(channel, l) }
        delay(500)
    }

    /** Runs a WifiP2pManager call and waits for its listener; true on success. */
    private suspend fun WifiP2pManager.call(block: (WifiP2pManager.ActionListener) -> Unit): Boolean =
        suspendCancellableCoroutine { cont ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() = cont.resume(true)
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "p2p call failed reason=$reason")
                    cont.resume(false)
                }
            })
        }

    @Suppress("DEPRECATION") // NetworkInfo in the P2P broadcast is the only signal before API 33.
    private suspend fun waitForGroup(context: Context, start: () -> Unit): WifiP2pInfo? = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val info = i.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    val net = i.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (info?.groupFormed == true && net?.isConnected == true && cont.isActive) {
                        context.unregisterReceiver(this)
                        cont.resume(info)
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            start()
        }
    }

    private companion object {
        const val TAG = "TurboProbe"
        const val SSID = "DIRECT-KF-kaffprobe"
        const val PASSPHRASE = "kaff-probe-2026"
        const val PORT = 48_988
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile var current: Job? = null
        @Volatile var listening: ServerSocket? = null
    }
}
