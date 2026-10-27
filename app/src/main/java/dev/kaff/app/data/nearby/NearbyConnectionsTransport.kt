package dev.kaff.app.data.nearby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.domain.model.LinkQuality
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.domain.transfer.DecodeResult
import dev.kaff.app.domain.transfer.ProtocolCodec
import dev.kaff.app.domain.transfer.ProtocolMessage
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * [NearbyTransport] over Google Nearby Connections with P2P_POINT_TO_POINT (one link at a time,
 * the highest bandwidth strategy: Bluetooth to bootstrap, then Wi-Fi Direct/hotspot when possible).
 *
 * Protocol messages travel as BYTES payloads, files as FILE payloads. Payload contents are never
 * logged.
 */
@Singleton
class NearbyConnectionsTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : NearbyTransport {

    private val client by lazy { Nearby.getConnectionsClient(context) }
    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 256)
    override val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val _linkQuality = MutableStateFlow(LinkQuality.Unknown)
    override val linkQuality: StateFlow<LinkQuality> = _linkQuality.asStateFlow()

    /** FILE payloads in flight, so transfer updates can be told apart from BYTES updates. */
    private val incomingFiles = ConcurrentHashMap<Long, Payload>()
    private val outgoingFiles = ConcurrentHashMap<Long, Payload>()

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            emit(
                TransportEvent.ConnectionInitiated(
                    endpointId = endpointId,
                    name = info.endpointName,
                    authDigits = info.authenticationDigits,
                    incoming = info.isIncomingConnection,
                ),
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            emit(TransportEvent.ConnectionResult(endpointId, resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK))
        }

        override fun onDisconnected(endpointId: String) {
            _linkQuality.value = LinkQuality.Unknown
            emit(TransportEvent.Disconnected(endpointId))
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            val quality = when (info.quality) {
                BandwidthInfo.Quality.HIGH -> LinkQuality.High
                BandwidthInfo.Quality.MEDIUM -> LinkQuality.Medium
                BandwidthInfo.Quality.LOW -> LinkQuality.Low
                else -> LinkQuality.Unknown
            }
            Log.i(TAG, "Link quality: $quality")
            _linkQuality.value = quality
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId == SERVICE_ID) emit(TransportEvent.EndpointFound(endpointId, info.endpointName))
        }

        override fun onEndpointLost(endpointId: String) {
            emit(TransportEvent.EndpointLost(endpointId))
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    emit(
                        when (val decoded = ProtocolCodec.decode(bytes)) {
                            is DecodeResult.Message -> TransportEvent.MessageReceived(endpointId, decoded.message)
                            is DecodeResult.UnsupportedVersion -> TransportEvent.BadMessage(endpointId, unsupportedVersion = true)
                            DecodeResult.Malformed -> TransportEvent.BadMessage(endpointId, unsupportedVersion = false)
                        },
                    )
                }
                Payload.Type.FILE -> incomingFiles[payload.id] = payload
                // Kaff never sends streams; anything else is dropped.
                else -> payload.close()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val id = update.payloadId
            val outgoing = outgoingFiles.containsKey(id)
            if (!outgoing && !incomingFiles.containsKey(id)) return // BYTES payload
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS ->
                    emit(TransportEvent.PayloadProgress(endpointId, id, update.bytesTransferred, outgoing))
                PayloadTransferUpdate.Status.SUCCESS -> {
                    emit(TransportEvent.PayloadProgress(endpointId, id, update.totalBytes, outgoing))
                    val file = if (outgoing) {
                        outgoingFiles.remove(id)?.close()
                        null
                    } else {
                        incomingFiles.remove(id)?.let { NearbyReceivedFile(context, it) }
                    }
                    emit(TransportEvent.PayloadCompleted(endpointId, id, outgoing, file))
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    (outgoingFiles.remove(id) ?: incomingFiles.remove(id))?.close()
                    emit(TransportEvent.PayloadFailed(endpointId, id, outgoing))
                }
            }
        }
    }

    private fun emit(event: TransportEvent) {
        if (!_events.tryEmit(event)) Log.w(TAG, "Dropped transport event ${event::class.simpleName}")
    }

    override suspend fun startAdvertising(localName: String): TransferError? = client.startAdvertising(
        localName,
        SERVICE_ID,
        lifecycle,
        AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            // Let Nearby move the link to Wi-Fi Direct / hotspot for full speed, like SHAREit does.
            // Internet on Wi-Fi may pause during a transfer; it comes back when the link closes.
            .setConnectionType(ConnectionType.DISRUPTIVE)
            .setLowPower(false)
            .build(),
    ).radioError(ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING)

    override fun stopAdvertising() = client.stopAdvertising()

    override suspend fun startDiscovery(): TransferError? {
        // Measured on Play services 26.41 / Android 16: with location services off, discovery fails
        // with MISSING_PERMISSION_ACCESS_COARSE_LOCATION even when the permission is granted.
        // Checking first gives the user the real fix. Advertising works with location off.
        if (!locationEnabled()) return TransferError.LocationOff
        return client.startDiscovery(
            SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).radioError(ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING)
    }

    private fun locationEnabled(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true

    private fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun stopDiscovery() = client.stopDiscovery()

    override suspend fun requestConnection(localName: String, endpointId: String): Boolean =
        client.requestConnection(localName, endpointId, lifecycle, CONNECTION_OPTIONS)
            .succeeds(ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT)

    override suspend fun acceptConnection(endpointId: String): Boolean =
        client.acceptConnection(endpointId, payloads).succeeds()

    override suspend fun rejectConnection(endpointId: String) {
        client.rejectConnection(endpointId).succeeds()
    }

    override suspend fun send(endpointId: String, message: ProtocolMessage): Boolean =
        client.sendPayload(endpointId, Payload.fromBytes(ProtocolCodec.encode(message))).succeeds()

    override suspend fun sendFile(endpointId: String, file: OutgoingFile): Long? {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(file.uri.toUri(), "r")
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Cannot open outgoing file", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Lost access to outgoing file", e)
            null
        } ?: return null
        val payload = Payload.fromFile(descriptor).apply { setFileName(file.meta.name) }
        outgoingFiles[payload.id] = payload
        if (!client.sendPayload(endpointId, payload).succeeds()) {
            outgoingFiles.remove(payload.id)
            payload.close()
            return null
        }
        return payload.id
    }

    override suspend fun cancelPayload(payloadId: Long) {
        client.cancelPayload(payloadId).succeeds()
    }

    override fun disconnect(endpointId: String) = client.disconnectFromEndpoint(endpointId)

    override fun stopAll() {
        _linkQuality.value = LinkQuality.Unknown
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        outgoingFiles.values.forEach { it.close() }
        outgoingFiles.clear()
        incomingFiles.values.forEach { NearbyReceivedFile(context, it).discard() }
        incomingFiles.clear()
    }

    /** Awaits a start call and turns Nearby's status code into a specific, fixable reason. */
    private suspend fun Task<Void>.radioError(benign: Int): TransferError? = try {
        await()
        null
    } catch (e: ApiException) {
        val code = e.statusCode
        Log.w(TAG, "Nearby start failed: ${ConnectionsStatusCodes.getStatusCodeString(code)}")
        when (code) {
            benign -> null
            // A "missing location permission" with the permission granted means location is off.
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            -> if (locationGranted() && !locationEnabled()) TransferError.LocationOff else TransferError.NearbyPermission
            in MISSING_PERMISSION_CODES -> TransferError.NearbyPermission
            ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON -> TransferError.LocationOff
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> TransferError.BluetoothOff
            else -> TransferError.NearbyUnavailable
        }
    }

    /** Awaits the task; true on success or on one of the [benign] status codes. */
    private suspend fun Task<Void>.succeeds(vararg benign: Int): Boolean = try {
        await()
        true
    } catch (e: ApiException) {
        val ok = e.statusCode in benign
        if (!ok) Log.w(TAG, "Nearby call failed: ${ConnectionsStatusCodes.getStatusCodeString(e.statusCode)}")
        ok
    }

    companion object {
        const val SERVICE_ID = "dev.kaff.app.transfer"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
        private val CONNECTION_OPTIONS = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.DISRUPTIVE)
            .setLowPower(false)
            .build()
        private val MISSING_PERMISSION_CODES =
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES..ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT
        private const val TAG = "NearbyTransport"
    }
}

/** A FILE payload Nearby finished writing to its own storage. */
private class NearbyReceivedFile(private val context: Context, private val payload: Payload) : ReceivedFile {
    override fun openStream(): InputStream {
        val file = checkNotNull(payload.asFile()) { "Not a file payload" }
        val uri = file.asUri()
        if (uri != null) return checkNotNull(context.contentResolver.openInputStream(uri)) { "Unreadable payload" }
        return ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(file.asParcelFileDescriptor()))
    }

    override fun discard() {
        val uri = payload.asFile()?.asUri()
        if (uri != null) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: SecurityException) {
                Log.w("NearbyReceivedFile", "Could not delete temporary payload", e)
            } catch (e: UnsupportedOperationException) {
                Log.w("NearbyReceivedFile", "Could not delete temporary payload", e)
            }
        }
        payload.close()
    }
}
