package dev.kaff.app.data.turbo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.core.di.ApplicationScope
import dev.kaff.app.core.di.IoDispatcher
import dev.kaff.app.data.nearby.NearbyConnectionsTransport
import dev.kaff.app.domain.model.LinkQuality
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.domain.transfer.ProtocolMessage
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The turbo lane: [NearbyTransport] that keeps Nearby for discovery, pairing and every protocol
 * message, but moves file bytes onto [STREAMS] parallel, AES-GCM encrypted TCP streams over the
 * Wi-Fi link Nearby has already brought up. Measured on a Wi-Fi 5 phone, parallel streams beat
 * Nearby's single FILE stream by ~40%.
 *
 * Invisible to the session reducers: turbo files get synthetic (negative) payload ids and the same
 * PayloadProgress / Completed / Failed events Nearby emits, so FileHeader, receipts, cancel and
 * history work unchanged. If the peer is an older build, the sockets do not connect, or the link is
 * Bluetooth only, files simply go as Nearby FILE payloads.
 *
 * Sender side: after the peer's HELLO says turbo and the link is on Wi-Fi, it listens on an
 * ephemeral port and sends TURBO_OFFER (addresses, port, key, token) over the authenticated Nearby
 * channel. The receiver connects, proves the token and answers TURBO_READY.
 */
@Singleton
class TurboTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nearby: NearbyConnectionsTransport,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) : NearbyTransport {

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val active = MutableStateFlow(0)
    override val linkQuality: StateFlow<LinkQuality> = combine(nearby.linkQuality, active) { quality, lanes ->
        if (lanes > 0 && quality >= LinkQuality.High) LinkQuality.Turbo else quality
    }.stateIn(scope, SharingStarted.Eagerly, LinkQuality.Unknown)

    /** Endpoints we asked to connect to: we are their sender and host the lane. */
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sendLanes = ConcurrentHashMap<String, Deferred<TurboSendLane?>>()
    private val receiveLanes = ConcurrentHashMap<String, TurboReceiveLane>()
    private val readiness = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Turbo payload id → endpoint, to route cancels. */
    private val turboPayloads = ConcurrentHashMap<Long, String>()
    private val nextId = AtomicLong(-1L shl 40)
    private val random = SecureRandom()
    private val tempDir by lazy { File(context.cacheDir, "turbo").apply { mkdirs() } }

    init {
        scope.launch(io) { tempDir.listFiles()?.forEach { it.delete() } }
        // Single collector, so lane bookkeeping runs once per event whatever subscribes downstream.
        scope.launch { nearby.events.collect { event -> intercept(event)?.let { _events.emit(it) } } }
    }

    private fun intercept(event: TransportEvent): TransportEvent? {
        val endpointId = event.endpointId
        when (event) {
            is TransportEvent.MessageReceived -> when (val m = event.message) {
                is ProtocolMessage.Hello -> if (m.turbo && endpointId in requested) startLane(endpointId)
                is ProtocolMessage.TurboOffer -> {
                    scope.launch(io) { join(endpointId, m) }
                    return null
                }
                is ProtocolMessage.TurboReady -> {
                    readiness[endpointId]?.complete(m.ok)
                    return null
                }
                // Lets a cancel reach the sender even for a file whose first chunk has not arrived.
                is ProtocolMessage.FileHeader -> if (m.payloadId < 0) turboPayloads[m.payloadId] = endpointId
                is ProtocolMessage.TurboCancel -> {
                    m.payloadIds.forEach { id -> scope.launch { cancelled(endpointId, id) } }
                    return null
                }
                else -> Unit
            }
            is TransportEvent.Disconnected -> teardown(endpointId)
            is TransportEvent.ConnectionResult -> if (!event.connected) teardown(endpointId)
            else -> Unit
        }
        return event
    }

    // region Sender

    private fun startLane(endpointId: String) {
        if (sendLanes.containsKey(endpointId)) return
        sendLanes[endpointId] = scope.async(io) {
            try {
                host(endpointId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sockets can fail in many ways (no route, port, OEM firewall): Nearby payloads remain.
                Log.w(TAG, "Turbo lane not available: ${e.message}")
                null
            }
        }
    }

    private suspend fun host(endpointId: String): TurboSendLane? {
        // Sockets need the Wi-Fi link; on Bluetooth alone there is no IP route between the phones.
        withTimeoutOrNull(LINK_WAIT_MS) { nearby.linkQuality.first { it >= LinkQuality.High } }
        val addresses = LocalNetwork.advertisedAddresses()
        if (addresses.isEmpty()) return null
        val key = ByteArray(TurboFrames.KEY_BYTES).also(random::nextBytes)
        val token = ByteArray(TurboFrames.TOKEN_BYTES).also(random::nextBytes)
        val ready = CompletableDeferred<Boolean>().also { readiness[endpointId] = it }
        val accepted = Collections.synchronizedList(ArrayList<Socket>())
        val ok = try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.receiveBufferSize = SOCKET_BUFFER
                server.bind(InetSocketAddress(0), STREAMS)
                val acceptor = scope.launch(io) { accept(server, token, accepted) }
                val sent = nearby.send(
                    endpointId,
                    ProtocolMessage.TurboOffer(addresses, server.localPort, key.base64(), token.base64(), STREAMS),
                )
                val ok = sent && withTimeoutOrNull(OFFER_WAIT_MS) { ready.await() } == true
                server.close()
                acceptor.join()
                ok
            }
        } finally {
            readiness.remove(endpointId)
        }
        val sockets = accepted.toList()
        if (!ok || sockets.isEmpty() || endpointId !in requested) {
            sockets.forEach { it.closeQuietly() }
            Log.i(TAG, "Turbo lane declined (ok=$ok, streams=${sockets.size}); using Nearby payloads")
            return null
        }
        Log.i(TAG, "Turbo lane up: ${sockets.size} streams")
        active.update { it + 1 }
        return TurboSendLane(sockets, key, scope, SendEvents(endpointId))
    }

    /** Accepts streams that prove the token, until [server] is closed. */
    private fun accept(server: ServerSocket, token: ByteArray, into: MutableList<Socket>) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                if (!TurboFrames.tokensEqual(TurboFrames.readToken(socket.getInputStream()), token)) {
                    socket.closeQuietly()
                    continue
                }
                socket.tune()
                socket.soTimeout = 0
                into += socket
                socket.getOutputStream().apply {
                    write(TurboFrames.ACK)
                    flush()
                }
            } catch (e: IOException) {
                into.remove(socket)
                socket.closeQuietly()
            }
        }
    }

    private inner class SendEvents(private val endpointId: String) : TurboSendLane.Listener {
        override fun progress(payloadId: Long, bytes: Long) {
            _events.tryEmit(TransportEvent.PayloadProgress(endpointId, payloadId, bytes, outgoing = true))
        }

        override suspend fun completed(payloadId: Long) {
            turboPayloads.remove(payloadId)
            _events.emit(TransportEvent.PayloadCompleted(endpointId, payloadId, outgoing = true, file = null))
        }

        override suspend fun failed(payloadId: Long) {
            turboPayloads.remove(payloadId)
            _events.emit(TransportEvent.PayloadFailed(endpointId, payloadId, outgoing = true))
        }
    }

    // endregion

    // region Receiver

    private suspend fun join(endpointId: String, offer: ProtocolMessage.TurboOffer) {
        receiveLanes.remove(endpointId)?.let(::retire)
        val sockets = try {
            connect(offer)
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
        if (sockets.isEmpty()) {
            Log.i(TAG, "Turbo offer could not connect; staying on Nearby payloads")
            nearby.send(endpointId, ProtocolMessage.TurboReady(ok = false))
            return
        }
        val lane = TurboReceiveLane(sockets, Base64.getDecoder().decode(offer.key), tempDir, scope, ReceiveEvents(endpointId))
        receiveLanes[endpointId] = lane
        active.update { it + 1 }
        Log.i(TAG, "Turbo lane joined: ${sockets.size} streams")
        if (!nearby.send(endpointId, ProtocolMessage.TurboReady(ok = true))) {
            if (receiveLanes.remove(endpointId, lane)) retire(lane)
        }
    }

    /** Opens the offered streams on the first address that answers. */
    private suspend fun connect(offer: ProtocolMessage.TurboOffer): List<Socket> = withContext(io) {
        val token = Base64.getDecoder().decode(offer.token)
        val streams = offer.streams.coerceIn(1, MAX_STREAMS)
        for (host in offer.addresses) {
            val remote = runCatching { InetAddress.getByName(host) }.getOrNull() ?: continue
            val local = LocalNetwork.localFor(remote) ?: continue
            val first = open(remote, offer.port, local, token) ?: continue
            val rest = (1 until streams).map { async { open(remote, offer.port, local, token) } }.awaitAll()
            return@withContext listOf(first) + rest.filterNotNull()
        }
        emptyList()
    }

    private fun open(remote: InetAddress, port: Int, local: InetAddress, token: ByteArray): Socket? {
        val socket = Socket()
        return try {
            socket.tune()
            socket.bind(InetSocketAddress(local, 0))
            socket.connect(InetSocketAddress(remote, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.getOutputStream().apply {
                write(token)
                flush()
            }
            if (socket.getInputStream().read() != TurboFrames.ACK) throw IOException("No turbo ack")
            socket.soTimeout = 0
            socket
        } catch (e: SocketTimeoutException) {
            socket.closeQuietly()
            null
        } catch (e: IOException) {
            socket.closeQuietly()
            null
        }
    }

    private inner class ReceiveEvents(private val endpointId: String) : TurboReceiveLane.Listener {
        override fun progress(payloadId: Long, bytes: Long) {
            turboPayloads[payloadId] = endpointId
            _events.tryEmit(TransportEvent.PayloadProgress(endpointId, payloadId, bytes, outgoing = false))
        }

        override suspend fun completed(payloadId: Long, file: File) {
            turboPayloads.remove(payloadId)
            _events.emit(TransportEvent.PayloadCompleted(endpointId, payloadId, outgoing = false, file = TurboReceivedFile(file)))
        }

        override suspend fun failed(payloadId: Long) {
            turboPayloads.remove(payloadId)
            _events.emit(TransportEvent.PayloadFailed(endpointId, payloadId, outgoing = false))
        }
    }

    // endregion

    /** The peer stopped [payloadId]: drop it on our side and report it like a cancelled payload. */
    private suspend fun cancelled(endpointId: String, payloadId: Long) {
        val outgoing = sendLanes[endpointId]?.takeIf { it.isCompleted }?.await()?.cancel(payloadId) == true
        val incoming = !outgoing && receiveLanes[endpointId]?.cancel(payloadId) == true
        if (outgoing || incoming) {
            turboPayloads.remove(payloadId)
            _events.emit(TransportEvent.PayloadFailed(endpointId, payloadId, outgoing))
        }
    }

    private fun teardown(endpointId: String) {
        requested.remove(endpointId)
        readiness.remove(endpointId)?.complete(false)
        sendLanes.remove(endpointId)?.let { deferred ->
            scope.launch { runCatching { deferred.await() }.getOrNull()?.let(::retire) }
        }
        receiveLanes.remove(endpointId)?.let(::retire)
        turboPayloads.values.removeAll { it == endpointId }
    }

    private fun retire(lane: TurboSendLane) {
        lane.close()
        active.update { it - 1 }
    }

    private fun retire(lane: TurboReceiveLane) {
        lane.close()
        active.update { it - 1 }
    }

    // region NearbyTransport

    override suspend fun startAdvertising(localName: String): TransferError? = nearby.startAdvertising(localName)
    override fun stopAdvertising() = nearby.stopAdvertising()
    override suspend fun startDiscovery(): TransferError? = nearby.startDiscovery()
    override fun stopDiscovery() = nearby.stopDiscovery()

    override suspend fun requestConnection(localName: String, endpointId: String): Boolean {
        requested += endpointId
        return nearby.requestConnection(localName, endpointId)
    }

    override suspend fun acceptConnection(endpointId: String): Boolean = nearby.acceptConnection(endpointId)
    override suspend fun rejectConnection(endpointId: String) = nearby.rejectConnection(endpointId)

    override suspend fun send(endpointId: String, message: ProtocolMessage): Boolean =
        nearby.send(endpointId, if (message is ProtocolMessage.Hello) message.copy(turbo = true) else message)

    override suspend fun sendFile(endpointId: String, file: OutgoingFile): Long? {
        val lane = sendLanes[endpointId]?.let { withTimeoutOrNull(LANE_WAIT_MS) { it.await() } }
        if (lane == null || lane.broken) return nearby.sendFile(endpointId, file)
        val input = openOutgoing(file) ?: return null
        val id = nextId.decrementAndGet()
        turboPayloads[id] = endpointId
        lane.send(id, file.meta.size, input)
        return id
    }

    @SuppressLint("Recycle") // Ownership moves to the lane, which closes the stream when the file is done.
    private suspend fun openOutgoing(file: OutgoingFile): InputStream? = withContext(io) {
        try {
            context.contentResolver.openInputStream(file.uri.toUri())
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Cannot open outgoing file", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost access to outgoing file", e)
            null
        }
    }

    override suspend fun cancelPayload(payloadId: Long) {
        val endpointId = turboPayloads.remove(payloadId) ?: return nearby.cancelPayload(payloadId)
        val outgoing = sendLanes[endpointId]?.takeIf { it.isCompleted }?.await()?.cancel(payloadId) == true
        val incoming = !outgoing && receiveLanes[endpointId]?.cancel(payloadId) == true
        nearby.send(endpointId, ProtocolMessage.TurboCancel(listOf(payloadId)))
        // Nearby reports a cancelled payload to the side that cancelled it too.
        if (outgoing || incoming) _events.emit(TransportEvent.PayloadFailed(endpointId, payloadId, outgoing))
    }

    override fun disconnect(endpointId: String) {
        teardown(endpointId)
        nearby.disconnect(endpointId)
    }

    override fun stopAll() {
        (sendLanes.keys + receiveLanes.keys).toSet().forEach(::teardown)
        requested.clear()
        nearby.stopAll()
    }

    // endregion

    private fun Socket.tune() {
        sendBufferSize = SOCKET_BUFFER
        receiveBufferSize = SOCKET_BUFFER
        tcpNoDelay = false
        keepAlive = true
    }

    private fun ByteArray.base64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val TAG = "TurboTransport"

        /** Eight streams saturated a Wi-Fi 5 1x1 radio in the probe (17–18 MB/s vs 12.5 on Nearby). */
        const val STREAMS = 8
        const val MAX_STREAMS = 16
        const val SOCKET_BUFFER = 4 * 1024 * 1024
        const val LINK_WAIT_MS = 10_000L
        const val OFFER_WAIT_MS = 4_000L
        const val LANE_WAIT_MS = 2_500L
        const val CONNECT_TIMEOUT_MS = 1_500
        const val HANDSHAKE_TIMEOUT_MS = 3_000
    }
}

/** A turbo file fully written to the app's cache; deleted once saved or dropped. */
private class TurboReceivedFile(private val file: File) : ReceivedFile {
    override fun openStream(): InputStream = FileInputStream(file)
    override fun discard() {
        file.delete()
    }
}
