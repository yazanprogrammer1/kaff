package dev.kaff.app.data.desktop

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
    private val answer: () -> Answer?,
) {
    data class Answer(val name: String, val port: Int, val token: String, val network: String?)

    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        val created = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: SocketException) {
            return
        }
        socket = created
        job = scope.launch(Dispatchers.IO) { listen(created) }
    }

    fun stop() {
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
