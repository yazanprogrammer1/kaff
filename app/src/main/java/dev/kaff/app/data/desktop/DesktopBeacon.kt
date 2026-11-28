package dev.kaff.app.data.desktop

import android.net.wifi.WifiManager
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the Kaff desktop app find this phone without typing anything: the app broadcasts
 * `KAFF?` on UDP [PORT] and the phone answers to the sender with its name and the page's port and
 * path. The answer is unicast to the asker, so Windows Firewall lets it in without a prompt.
 *
 * Anyone on the network can learn the address this way, exactly as they could by watching the
 * phone's screen; every computer still has to be approved on the phone.
 */
class DesktopBeacon(
    private val scope: CoroutineScope,
    /** Creates the Wi-Fi multicast lock that lets broadcasts through; null where there is none (tests). */
    private val multicastLockFactory: () -> WifiManager.MulticastLock? = { null },
    private val answer: () -> Answer?,
) {
    /** [id] is stable for this phone (random, never derived from hardware), unlike its editable name. */
    data class Answer(val id: String, val name: String, val port: Int, val token: String, val network: String?)

    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (job != null) return
        // Most phones drop broadcast packets in the Wi-Fi chip to save power unless a lock is held,
        // so without it the computer's "KAFF?" never arrives on home Wi-Fi.
        multicastLock = runCatching {
            multicastLockFactory()?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        val created = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: SocketException) {
            runCatching { multicastLock?.release() }
            multicastLock = null
            return
        }
        socket = created
        job = scope.launch(Dispatchers.IO) { listen(created) }
    }

    fun stop() {
        runCatching { multicastLock?.release() }
        multicastLock = null
        socket?.close()
        socket = null
        job?.cancel()
        job = null
    }

    private fun listen(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                return
            }
            val question = String(packet.data, 0, packet.length, Charsets.UTF_8)
            if (!question.startsWith(QUESTION)) continue
            val reply = answer() ?: continue
            val body = buildJsonObject {
                put("app", "kaff")
                put("v", 1)
                put("id", reply.id)
                put("name", reply.name)
                put("port", reply.port)
                put("path", "/${reply.token}/")
                reply.network?.let { put("network", it) }
            }.toString().toByteArray(Charsets.UTF_8)
            try {
                socket.send(DatagramPacket(body, body.size, packet.socketAddress))
            } catch (e: IOException) {
                // The asker left; the next broadcast gets a fresh answer.
            }
        }
    }

    companion object {
        const val PORT = 4998
        const val QUESTION = "KAFF?"
    }
}
