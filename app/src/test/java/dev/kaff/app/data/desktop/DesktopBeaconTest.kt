package dev.kaff.app.data.desktop

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real UDP on loopback: the desktop app's question gets the phone's answer. */
class DesktopBeaconTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val beacon = DesktopBeacon(scope) { DesktopBeacon.Answer("OPPO Reno13", 4999, "k7m2p", "DIRECT-KF-Kaff-23FA") }

    @After
    fun tearDown() {
        beacon.stop()
        scope.cancel()
    }

    private fun ask(question: String): String? = DatagramSocket().use { socket ->
        socket.soTimeout = 1_500
        val bytes = question.toByteArray()
        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getLoopbackAddress(), DesktopBeacon.PORT))
        val buffer = ByteArray(512)
        val reply = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(reply)
            String(reply.data, 0, reply.length)
        } catch (e: java.net.SocketTimeoutException) {
            null
        }
    }

    @Test
    fun answersKaffQuestionsOnly() {
        beacon.start()
        Thread.sleep(200)
        val answer = ask(DesktopBeacon.QUESTION)!!
        assertTrue(answer, answer.contains("\"name\":\"OPPO Reno13\"") && answer.contains("\"path\":\"/k7m2p/\"") && answer.contains("\"port\":4999"))
        assertTrue(ask("HELLO") == null)
    }
}
