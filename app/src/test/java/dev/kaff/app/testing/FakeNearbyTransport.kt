package dev.kaff.app.testing

import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.domain.transfer.DecodeResult
import dev.kaff.app.domain.transfer.ProtocolCodec
import dev.kaff.app.domain.transfer.ProtocolMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * In-memory "air" connecting [FakeNearbyTransport]s. Endpoint ids are the device ids. Messages go
 * through the real [ProtocolCodec], file contents come from [contents] keyed by URI.
 */
class FakeNearbyNetwork {
    val devices = mutableMapOf<String, FakeNearbyTransport>()
    val contents = mutableMapOf<String, ByteArray>()

    /** Flip a byte in transit to simulate corruption. */
    var corruptFiles = false

    /** Deliver FILE payloads before their FILE header message. */
    var filesBeforeHeaders = false

    var authDigits = "4721"

    fun device(id: String): FakeNearbyTransport = FakeNearbyTransport(id, this).also { devices[id] = it }

    fun register(file: OutgoingFile, bytes: ByteArray) {
        contents[file.uri] = bytes
    }
}

class FakeNearbyTransport(val id: String, private val network: FakeNearbyNetwork) : NearbyTransport {
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events

    /** Set to make radio starts fail with this reason. */
    var startError: TransferError? = null
    var advertisingName: String? = null
        private set
    var discovering = false
        private set
    val connected = mutableSetOf<String>()
    private val pending = mutableMapOf<String, Boolean>() // endpoint -> accepted locally
    private var nextPayloadId = 100L
    val sentMessages = mutableListOf<ProtocolMessage>()
    val cancelledPayloads = mutableListOf<Long>()
    private val heldFiles = mutableListOf<() -> Unit>()

    val isIdle: Boolean get() = advertisingName == null && !discovering && connected.isEmpty()

    fun emit(event: TransportEvent) {
        _events.tryEmit(event)
    }

    private fun peer(endpointId: String) = network.devices.getValue(endpointId)

    override suspend fun startAdvertising(localName: String): TransferError? {
        startError?.let { return it }
        advertisingName = localName
        network.devices.values.filter { it.discovering && it !== this }.forEach { it.emit(TransportEvent.EndpointFound(id, localName)) }
        return null
    }

    override fun stopAdvertising() {
        advertisingName = null
    }

    override suspend fun startDiscovery(): TransferError? {
        startError?.let { return it }
        discovering = true
        network.devices.values.filter { it.advertisingName != null && it !== this }
            .forEach { emit(TransportEvent.EndpointFound(it.id, it.advertisingName!!)) }
        return null
    }

    override fun stopDiscovery() {
        discovering = false
    }

    override suspend fun requestConnection(localName: String, endpointId: String): Boolean {
        val target = network.devices[endpointId] ?: return false
        if (target.advertisingName == null) return false
        pending[endpointId] = false
        target.pending[id] = false
        emit(TransportEvent.ConnectionInitiated(endpointId, target.advertisingName!!, network.authDigits, incoming = false))
        target.emit(TransportEvent.ConnectionInitiated(id, localName, network.authDigits, incoming = true))
        return true
    }

    override suspend fun acceptConnection(endpointId: String): Boolean {
        if (endpointId !in pending) return false
        pending[endpointId] = true
        val other = peer(endpointId)
        if (other.pending[id] == true) {
            pending.remove(endpointId)
            other.pending.remove(id)
            connected += endpointId
            other.connected += id
            emit(TransportEvent.ConnectionResult(endpointId, true))
            other.emit(TransportEvent.ConnectionResult(id, true))
        }
        return true
    }

    override suspend fun rejectConnection(endpointId: String) {
        if (pending.remove(endpointId) == null) return
        val other = peer(endpointId)
        other.pending.remove(id)
        emit(TransportEvent.ConnectionResult(endpointId, false))
        other.emit(TransportEvent.ConnectionResult(id, false))
    }

    override suspend fun send(endpointId: String, message: ProtocolMessage): Boolean {
        if (endpointId !in connected) return false
        sentMessages += message
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(message))
        val event = when (decoded) {
            is DecodeResult.Message -> TransportEvent.MessageReceived(id, decoded.message)
            is DecodeResult.UnsupportedVersion -> TransportEvent.BadMessage(id, unsupportedVersion = true)
            DecodeResult.Malformed -> TransportEvent.BadMessage(id, unsupportedVersion = false)
        }
        peer(endpointId).emit(event)
        // Files queued before this message (normally its FILE header) are delivered right after it.
        heldFiles.toList().also { heldFiles.clear() }.forEach { it() }
        return true
    }

    override suspend fun sendFile(endpointId: String, file: OutgoingFile): Long? {
        if (endpointId !in connected) return null
        val bytes = network.contents[file.uri] ?: return null
        val payloadId = nextPayloadId++
        val delivered = if (network.corruptFiles && bytes.isNotEmpty()) {
            bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        } else {
            bytes
        }
        val deliver = {
            val other = peer(endpointId)
            other.emit(TransportEvent.PayloadProgress(id, payloadId, delivered.size / 2L, outgoing = false))
            emit(TransportEvent.PayloadProgress(endpointId, payloadId, bytes.size / 2L, outgoing = true))
            other.emit(TransportEvent.PayloadProgress(id, payloadId, delivered.size.toLong(), outgoing = false))
            emit(TransportEvent.PayloadProgress(endpointId, payloadId, bytes.size.toLong(), outgoing = true))
            other.emit(TransportEvent.PayloadCompleted(id, payloadId, outgoing = false, file = FakeReceivedFile(delivered)))
            emit(TransportEvent.PayloadCompleted(endpointId, payloadId, outgoing = true, file = null))
        }
        if (network.filesBeforeHeaders) deliver() else heldFiles += deliver
        return payloadId
    }

    override suspend fun cancelPayload(payloadId: Long) {
        cancelledPayloads += payloadId
    }

    override fun disconnect(endpointId: String) {
        if (connected.remove(endpointId)) {
            val other = peer(endpointId)
            other.connected.remove(id)
            other.emit(TransportEvent.Disconnected(id))
        }
    }

    override fun stopAll() {
        stopAdvertising()
        stopDiscovery()
        connected.toList().forEach { disconnect(it) }
        pending.clear()
    }

    /** Simulates the radio link dropping. */
    fun dropLink() {
        connected.toList().forEach { endpoint ->
            connected.remove(endpoint)
            peer(endpoint).connected.remove(id)
            emit(TransportEvent.Disconnected(endpoint))
            peer(endpoint).emit(TransportEvent.Disconnected(id))
        }
    }
}
