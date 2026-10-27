package dev.kaff.app.data.turbo

import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs both lane halves over real loopback sockets. */
class TurboLaneTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tempDir: File
    private val key = Random(7).nextBytes(TurboFrames.KEY_BYTES)

    private val completed = ConcurrentHashMap<Long, CompletableDeferred<File>>()
    private val sentDone = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val failedIn = ConcurrentHashMap.newKeySet<Long>()
    private val failedOut = ConcurrentHashMap.newKeySet<Long>()

    private lateinit var sender: TurboSendLane
    private lateinit var receiver: TurboReceiveLane
    private val allSockets = mutableListOf<Socket>()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("turbo").toFile()
    }

    @After
    fun tearDown() {
        if (::sender.isInitialized) sender.close()
        if (::receiver.isInitialized) receiver.close()
        allSockets.forEach { it.closeQuietly() }
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun connect(streams: Int) {
        val server = ServerSocket(0, streams, InetAddress.getLoopbackAddress())
        val clients = List(streams) { Socket().apply { connect(InetSocketAddress(server.inetAddress, server.localPort)) } }
        val accepted = List(streams) { server.accept() }
        server.close()
        allSockets += clients + accepted
        sender = TurboSendLane(accepted, key, scope, object : TurboSendLane.Listener {
            override fun progress(payloadId: Long, bytes: Long) = Unit
            override suspend fun completed(payloadId: Long) {
                sentDone.getOrPut(payloadId) { CompletableDeferred() }.complete(Unit)
            }
            override suspend fun failed(payloadId: Long) {
                failedOut += payloadId
            }
        })
        receiver = TurboReceiveLane(clients, key, tempDir, scope, object : TurboReceiveLane.Listener {
            override fun progress(payloadId: Long, bytes: Long) = Unit
            override suspend fun completed(payloadId: Long, file: File) {
                completed.getOrPut(payloadId) { CompletableDeferred() }.complete(file)
            }
            override suspend fun failed(payloadId: Long) {
                failedIn += payloadId
            }
        })
    }

    private suspend fun received(id: Long): File =
        withTimeout(10_000) { completed.getOrPut(id) { CompletableDeferred() }.await() }

    @Test
    fun filesArriveIntactAcrossStreams() = runBlocking {
        connect(streams = 4)
        val sizes = listOf(0, 1, TurboFrames.CHUNK_BYTES - 1, TurboFrames.CHUNK_BYTES, 5 * TurboFrames.CHUNK_BYTES + 12_345)
        val contents = sizes.mapIndexed { i, size -> Random(i).nextBytes(size) }
        contents.forEachIndexed { i, bytes -> sender.send(-1L - i, bytes.size.toLong(), ByteArrayInputStream(bytes)) }

        contents.forEachIndexed { i, bytes ->
            assertArrayEquals("file $i", bytes, received(-1L - i).readBytes())
        }
        withTimeout(5_000) { contents.indices.forEach { sentDone.getOrPut(-1L - it) { CompletableDeferred() }.await() } }
        assertTrue(failedIn.isEmpty() && failedOut.isEmpty())
    }

    @Test
    fun cancelledFileIsDroppedAndTheNextOneStillArrives() = runBlocking {
        connect(streams = 2)
        val big = Random(1).nextBytes(40 * TurboFrames.CHUNK_BYTES)
        val small = Random(2).nextBytes(1000)
        sender.send(-1, big.size.toLong(), ByteArrayInputStream(big))
        receiver.cancel(-1)
        sender.cancel(-1)
        sender.send(-2, small.size.toLong(), ByteArrayInputStream(small))

        assertArrayEquals(small, received(-2).readBytes())
        assertFalse(completed.containsKey(-1))
        assertEquals(listOf("turbo--2.part"), tempDir.list()!!.toList())
    }

    @Test
    fun aShortFileFailsWithoutBreakingTheLane() = runBlocking {
        connect(streams = 2)
        sender.send(-1, 10_000, ByteArrayInputStream(ByteArray(10)))
        val ok = Random(3).nextBytes(2000)
        sender.send(-2, ok.size.toLong(), ByteArrayInputStream(ok))
        assertArrayEquals(ok, received(-2).readBytes())
        assertTrue(-1L in failedOut)
        assertFalse(sender.broken)
    }

    @Test
    fun aWrongKeyBreaksTheLane() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket().apply { connect(InetSocketAddress(server.inetAddress, server.localPort)) }
        val accepted = server.accept()
        server.close()
        allSockets += listOf(client, accepted)
        sender = TurboSendLane(listOf(accepted), Random(9).nextBytes(TurboFrames.KEY_BYTES), scope, object : TurboSendLane.Listener {
            override fun progress(payloadId: Long, bytes: Long) = Unit
            override suspend fun completed(payloadId: Long) = Unit
            override suspend fun failed(payloadId: Long) = Unit
        })
        receiver = TurboReceiveLane(listOf(client), key, tempDir, scope, object : TurboReceiveLane.Listener {
            override fun progress(payloadId: Long, bytes: Long) = Unit
            override suspend fun completed(payloadId: Long, file: File) = Unit
            override suspend fun failed(payloadId: Long) = Unit
        })
        sender.send(-1, 3000, ByteArrayInputStream(ByteArray(3000)))
        withTimeout(5_000) { while (!receiver.broken) delay(10) }
        assertTrue(tempDir.list()!!.isEmpty())
    }

    @Test
    fun subnetMatching() {
        val a = InetAddress.getByName("192.168.49.1")
        assertTrue(LocalNetwork.sameSubnet(a, InetAddress.getByName("192.168.49.77"), 24))
        assertFalse(LocalNetwork.sameSubnet(a, InetAddress.getByName("192.168.50.1"), 24))
        assertTrue(LocalNetwork.sameSubnet(InetAddress.getByName("10.8.0.1"), InetAddress.getByName("10.8.3.9"), 20))
        assertFalse(LocalNetwork.sameSubnet(InetAddress.getByName("10.8.0.1"), InetAddress.getByName("10.8.16.9"), 20))
    }
}
