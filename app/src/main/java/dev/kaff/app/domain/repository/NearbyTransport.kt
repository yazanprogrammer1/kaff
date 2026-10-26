package dev.kaff.app.domain.repository

import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.transfer.ProtocolMessage
import kotlinx.coroutines.flow.Flow

/**
 * Peer-to-peer link between two phones. Implemented over Google Nearby Connections; a fake
 * implementation drives the tests.
 */
interface NearbyTransport {
    val events: Flow<TransportEvent>

    suspend fun startAdvertising(localName: String): Boolean
    fun stopAdvertising()
    suspend fun startDiscovery(): Boolean
    fun stopDiscovery()

    suspend fun requestConnection(localName: String, endpointId: String): Boolean
    suspend fun acceptConnection(endpointId: String): Boolean
    suspend fun rejectConnection(endpointId: String)

    suspend fun send(endpointId: String, message: ProtocolMessage): Boolean

    /** Queues a file payload and returns its payload id, or null if the file could not be opened. */
    suspend fun sendFile(endpointId: String, file: OutgoingFile): Long?
    suspend fun cancelPayload(payloadId: Long)

    fun disconnect(endpointId: String)

    /** Stops advertising and discovery and drops every connection. */
    fun stopAll()
}

sealed interface TransportEvent {
    val endpointId: String

    data class EndpointFound(override val endpointId: String, val name: String) : TransportEvent
    data class EndpointLost(override val endpointId: String) : TransportEvent

    /** Both sides get this; [authDigits] must match on both screens. */
    data class ConnectionInitiated(
        override val endpointId: String,
        val name: String,
        val authDigits: String,
        val incoming: Boolean,
    ) : TransportEvent

    data class ConnectionResult(override val endpointId: String, val connected: Boolean) : TransportEvent
    data class Disconnected(override val endpointId: String) : TransportEvent

    data class MessageReceived(override val endpointId: String, val message: ProtocolMessage) : TransportEvent

    /** A BYTES payload that could not be decoded. */
    data class BadMessage(override val endpointId: String, val unsupportedVersion: Boolean) : TransportEvent

    /** Progress of a FILE payload in either direction. */
    data class PayloadProgress(
        override val endpointId: String,
        val payloadId: Long,
        val bytes: Long,
        val outgoing: Boolean,
    ) : TransportEvent

    /** A FILE payload finished. [file] is set for incoming payloads. */
    data class PayloadCompleted(
        override val endpointId: String,
        val payloadId: Long,
        val outgoing: Boolean,
        val file: ReceivedFile?,
    ) : TransportEvent

    data class PayloadFailed(override val endpointId: String, val payloadId: Long, val outgoing: Boolean) : TransportEvent
}
