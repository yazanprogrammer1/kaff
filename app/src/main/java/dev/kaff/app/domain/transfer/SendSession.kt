package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.TransferDirection
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.model.TransferStats
import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.domain.transfer.ProtocolMessage.Reason

/** Sender states. Files live next to the state in [SendModel] because they outlive connections. */
sealed interface SendState {
    data object Idle : SendState

    data class Searching(val peers: List<Peer> = emptyList(), val notice: TransferError? = null) : SendState

    data class Connecting(val peer: Peer, val peers: List<Peer>) : SendState

    /** Link requested; waiting for the receiver to confirm the matching digits. */
    data class AwaitingPairing(val peer: Peer, val authDigits: String) : SendState

    /** Paired and connected; the fist (or the manual button) arms the offer. */
    data class Ready(val peer: Peer, val authDigits: String, val notice: TransferError? = null) : SendState

    data class Armed(
        val peer: Peer,
        val authDigits: String,
        val transferId: String,
        val armedAtMs: Long,
        val windowMs: Long,
    ) : SendState

    data class Transferring(
        val peer: Peer,
        val authDigits: String,
        val transferId: String,
        val totalBytes: Long,
        val startedAtMs: Long,
        val payloadIds: List<Long> = emptyList(),
        val sentBytes: Map<Long, Long> = emptyMap(),
        val completed: Set<Long> = emptySet(),
    ) : SendState {
        val sentTotal: Long get() = sentBytes.values.sum()

        val progress: Float get() = if (totalBytes <= 0) 0f else (sentTotal.toFloat() / totalBytes).coerceIn(0f, 1f)

        /** Bytes sent so far for the file at [index] (0 until it is queued). */
        fun bytesOf(index: Int): Long = payloadIds.getOrNull(index)?.let { sentBytes[it] } ?: 0

        fun isDone(index: Int): Boolean = payloadIds.getOrNull(index)?.let { it in completed } ?: false
    }

    data class AwaitingReceipt(
        val peer: Peer,
        val transferId: String,
        val totalBytes: Long,
        val startedAtMs: Long,
        /** True after "cancel remaining": waiting for the receiver to say what it kept. */
        val cancelling: Boolean = false,
        val authDigits: String = "",
    ) : SendState

    /** [skipped] holds offer indexes the receiver did not keep (cancel remaining). */
    data class Completed(val peer: Peer, val stats: TransferStats, val skipped: Set<Int> = emptySet()) : SendState

    data class Failed(val error: TransferError) : SendState
}

data class SendModel(val state: SendState = SendState.Idle, val files: List<OutgoingFile> = emptyList())

sealed interface SendEvent {
    data object Start : SendEvent
    data class FilesChanged(val files: List<OutgoingFile>) : SendEvent
    data class SelectPeer(val endpointId: String) : SendEvent

    /** Confirmed fist, or the "send without gesture" button. */
    data object Grab : SendEvent
    data object Cancel : SendEvent
    data object Retry : SendEvent
    data object SendAnother : SendEvent

    /** Stop after the files already delivered; the receiver keeps those. */
    data object CancelRemaining : SendEvent
    /** Discovery could not start; [error] says why (permission, location, Bluetooth). */
    data class RadioFailed(val error: TransferError) : SendEvent
    data class Transport(val event: TransportEvent) : SendEvent
    data class TimerFired(val timer: SendTimer) : SendEvent
    data class FilesQueued(val transferId: String, val payloadIds: List<Long>) : SendEvent
    data class QueueFailed(val transferId: String) : SendEvent
}

enum class SendTimer { Armed, Receipt, Idle }

sealed interface SendEffect {
    data object StartDiscovery : SendEffect
    data object StopDiscovery : SendEffect
    data class RequestConnection(val endpointId: String) : SendEffect
    data class AcceptConnection(val endpointId: String) : SendEffect
    data class Send(val endpointId: String, val message: ProtocolMessage) : SendEffect
    data class QueueFiles(val endpointId: String, val transferId: String, val files: List<OutgoingFile>) : SendEffect
    data class CancelPayloads(val payloadIds: List<Long>) : SendEffect
    data class Disconnect(val endpointId: String) : SendEffect
    data object StopAll : SendEffect
    data class StartTimer(val timer: SendTimer, val delayMs: Long) : SendEffect
    data class CancelTimer(val timer: SendTimer) : SendEffect
    data class Feedback(val kind: FeedbackKind) : SendEffect
    data class Record(val record: TransferRecord) : SendEffect
}

private typealias T = Transition<SendModel, SendEffect>

/**
 * Pure sender state machine. Every (state, event) pair is handled explicitly; events that make no
 * sense in a state leave it unchanged.
 */
class SendReducer(
    private val clock: Clock,
    private val localName: () -> String,
    private val newTransferId: () -> String,
    private val timeouts: SessionTimeouts = SessionTimeouts(),
) {
    fun reduce(model: SendModel, event: SendEvent): T {
        // Events that behave the same in every state.
        when (event) {
            SendEvent.Cancel -> return cancel(model)
            is SendEvent.FilesChanged -> if (model.state.acceptsFileChanges()) {
                return T(model.copy(files = event.files))
            }
            is SendEvent.RadioFailed -> if (model.state != SendState.Idle) {
                return T(model.copy(state = SendState.Failed(event.error)), listOf(SendEffect.StopAll))
            }
            else -> Unit
        }
        return when (val state = model.state) {
            SendState.Idle -> idle(model, event)
            is SendState.Searching -> searching(model, state, event)
            is SendState.Connecting -> connecting(model, state, event)
            is SendState.AwaitingPairing -> awaitingPairing(model, state, event)
            is SendState.Ready -> ready(model, state, event)
            is SendState.Armed -> armed(model, state, event)
            is SendState.Transferring -> transferring(model, state, event)
            is SendState.AwaitingReceipt -> awaitingReceipt(model, state, event)
            is SendState.Completed -> completed(model, event)
            is SendState.Failed -> failed(model, event)
        }
    }

    private fun SendState.acceptsFileChanges() =
        this !is SendState.Armed && this !is SendState.Transferring && this !is SendState.AwaitingReceipt

    private fun idle(model: SendModel, event: SendEvent): T = when (event) {
        SendEvent.Start -> T(model.copy(state = SendState.Searching()), listOf(SendEffect.StartDiscovery))
        else -> T(model)
    }

    private fun searching(model: SendModel, state: SendState.Searching, event: SendEvent): T {
        val transport = (event as? SendEvent.Transport)?.event ?: return T(model)
        return when (transport) {
            is TransportEvent.EndpointFound -> {
                val peer = Peer(transport.endpointId, transport.name)
                val peers = (state.peers.filterNot { it.endpointId == peer.endpointId } + peer)
                // Auto-connect to the first phone found; the user can switch while connecting.
                T(
                    model.copy(state = SendState.Connecting(peer, peers)),
                    listOf(SendEffect.RequestConnection(peer.endpointId)),
                )
            }
            is TransportEvent.EndpointLost ->
                T(model.copy(state = state.copy(peers = state.peers.filterNot { it.endpointId == transport.endpointId })))
            else -> T(model)
        }
    }

    private fun connecting(model: SendModel, state: SendState.Connecting, event: SendEvent): T = when (event) {
        is SendEvent.SelectPeer -> {
            val target = state.peers.firstOrNull { it.endpointId == event.endpointId }
            if (target == null || target == state.peer) {
                T(model)
            } else {
                T(
                    model.copy(state = state.copy(peer = target)),
                    listOf(SendEffect.Disconnect(state.peer.endpointId), SendEffect.RequestConnection(target.endpointId)),
                )
            }
        }
        is SendEvent.Transport -> when (val t = event.event) {
            is TransportEvent.EndpointFound -> T(
                model.copy(state = state.copy(peers = state.peers.filterNot { it.endpointId == t.endpointId } + Peer(t.endpointId, t.name))),
            )
            is TransportEvent.EndpointLost -> if (t.endpointId == state.peer.endpointId) {
                T(model)
            } else {
                T(model.copy(state = state.copy(peers = state.peers.filterNot { it.endpointId == t.endpointId })))
            }
            is TransportEvent.ConnectionInitiated -> if (t.endpointId == state.peer.endpointId && !t.incoming) {
                // We asked for this link; accept our side. The receiver still has to confirm the digits.
                T(
                    model.copy(state = SendState.AwaitingPairing(state.peer.copy(name = t.name), t.authDigits)),
                    listOf(SendEffect.AcceptConnection(t.endpointId)),
                )
            } else {
                T(model)
            }
            is TransportEvent.ConnectionResult -> if (t.endpointId == state.peer.endpointId && !t.connected) {
                backToSearching(model, state.peers, TransferError.ConnectionFailed)
            } else {
                T(model)
            }
            is TransportEvent.Disconnected -> if (t.endpointId == state.peer.endpointId) {
                backToSearching(model, state.peers, TransferError.ConnectionFailed)
            } else {
                T(model)
            }
            else -> T(model)
        }
        else -> T(model)
    }

    private fun backToSearching(model: SendModel, peers: List<Peer>, notice: TransferError): T =
        T(model.copy(state = SendState.Searching(peers, notice)))

    private fun awaitingPairing(model: SendModel, state: SendState.AwaitingPairing, event: SendEvent): T {
        val t = (event as? SendEvent.Transport)?.event ?: return T(model)
        if (t.endpointId != state.peer.endpointId) return T(model)
        return when (t) {
            is TransportEvent.ConnectionResult -> if (t.connected) {
                T(
                    model.copy(state = SendState.Ready(state.peer, state.authDigits)),
                    listOf(
                        SendEffect.StopDiscovery,
                        SendEffect.Send(state.peer.endpointId, ProtocolMessage.Hello(ProtocolCodec.VERSION, localName())),
                        SendEffect.StartTimer(SendTimer.Idle, timeouts.idleMs),
                    ),
                )
            } else {
                T(model.copy(state = SendState.Searching(notice = TransferError.ConnectionFailed)))
            }
            is TransportEvent.Disconnected -> T(model.copy(state = SendState.Searching(notice = TransferError.Disconnected)))
            else -> T(model)
        }
    }

    private fun ready(model: SendModel, state: SendState.Ready, event: SendEvent): T = when (event) {
        SendEvent.Grab -> if (model.files.isEmpty()) {
            T(model)
        } else {
            val transferId = newTransferId()
            val offer = ProtocolMessage.Offer(
                transferId = transferId,
                files = model.files.map { it.meta },
                thumbnail = ProtocolCodec.encodeThumbnail(model.files.first().thumbnailJpeg),
            )
            T(
                model.copy(
                    state = SendState.Armed(state.peer, state.authDigits, transferId, clock.elapsedMs(), timeouts.armedWindowMs),
                ),
                listOf(
                    SendEffect.CancelTimer(SendTimer.Idle),
                    SendEffect.Feedback(FeedbackKind.Grab),
                    SendEffect.Send(state.peer.endpointId, offer),
                    SendEffect.Send(state.peer.endpointId, ProtocolMessage.Armed(transferId, timeouts.armedWindowMs)),
                    SendEffect.StartTimer(SendTimer.Armed, timeouts.armedWindowMs),
                ),
            )
        }
        is SendEvent.TimerFired -> if (event.timer == SendTimer.Idle) {
            T(model.copy(state = SendState.Failed(TransferError.Disconnected)), listOf(SendEffect.StopAll))
        } else {
            T(model)
        }
        is SendEvent.Transport -> linkEvent(model, state.peer, event.event) ?: T(model)
        else -> T(model)
    }

    /** Disconnects and version errors while paired but not transferring. */
    private fun linkEvent(model: SendModel, peer: Peer, t: TransportEvent): T? {
        if (t.endpointId != peer.endpointId) return null
        return when (t) {
            is TransportEvent.Disconnected -> T(
                model.copy(state = SendState.Searching(notice = TransferError.Disconnected)),
                listOf(SendEffect.CancelTimer(SendTimer.Idle), SendEffect.CancelTimer(SendTimer.Armed), SendEffect.StartDiscovery),
            )
            is TransportEvent.BadMessage -> if (t.unsupportedVersion) {
                T(model.copy(state = SendState.Failed(TransferError.VersionMismatch)), listOf(SendEffect.StopAll))
            } else {
                null
            }
            else -> null
        }
    }

    private fun armed(model: SendModel, state: SendState.Armed, event: SendEvent): T = when (event) {
        is SendEvent.TimerFired -> if (event.timer == SendTimer.Armed) {
            T(
                model.copy(state = SendState.Ready(state.peer, state.authDigits, TransferError.ArmedTimeout)),
                listOf(
                    SendEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.transferId, Reason.Timeout)),
                    SendEffect.Feedback(FeedbackKind.Error),
                    SendEffect.StartTimer(SendTimer.Idle, timeouts.idleMs),
                ),
            )
        } else {
            T(model)
        }
        is SendEvent.Transport -> {
            val t = event.event
            val message = (t as? TransportEvent.MessageReceived)?.message
            when {
                t.endpointId != state.peer.endpointId -> T(model)
                message is ProtocolMessage.Accept && message.transferId == state.transferId -> T(
                    model.copy(
                        state = SendState.Transferring(
                            peer = state.peer,
                            authDigits = state.authDigits,
                            transferId = state.transferId,
                            totalBytes = model.files.sumOf { it.meta.size },
                            startedAtMs = clock.elapsedMs(),
                        ),
                    ),
                    listOf(
                        SendEffect.CancelTimer(SendTimer.Armed),
                        SendEffect.QueueFiles(state.peer.endpointId, state.transferId, model.files),
                    ),
                )
                message is ProtocolMessage.Reject && message.transferId == state.transferId -> T(
                    model.copy(state = SendState.Ready(state.peer, state.authDigits, if (message.reason == Reason.Timeout) TransferError.ArmedTimeout else TransferError.Rejected)),
                    listOf(
                        SendEffect.CancelTimer(SendTimer.Armed),
                        SendEffect.Feedback(FeedbackKind.Error),
                        SendEffect.StartTimer(SendTimer.Idle, timeouts.idleMs),
                    ),
                )
                else -> linkEvent(model, state.peer, t) ?: T(model)
            }
        }
        else -> T(model)
    }

    private fun transferring(model: SendModel, state: SendState.Transferring, event: SendEvent): T = when (event) {
        SendEvent.CancelRemaining -> T(
            model.copy(
                state = SendState.AwaitingReceipt(state.peer, state.transferId, state.totalBytes, state.startedAtMs, cancelling = true, authDigits = state.authDigits),
            ),
            // The receiver cancels the payloads it will not keep and answers with a partial DONE.
            listOf(
                SendEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.transferId, Reason.UserCancelled)),
                SendEffect.StartTimer(SendTimer.Receipt, timeouts.receiptMs),
            ),
        )
        is SendEvent.FilesQueued -> if (event.transferId == state.transferId) {
            maybeAllSent(model, state.copy(payloadIds = event.payloadIds))
        } else {
            T(model)
        }
        is SendEvent.QueueFailed -> if (event.transferId == state.transferId) {
            fail(model, state.peer, state.transferId, TransferError.FileUnreadable, state.startedAtMs, state.payloadIds)
        } else {
            T(model)
        }
        is SendEvent.Transport -> {
            val t = event.event
            if (t.endpointId != state.peer.endpointId) {
                T(model)
            } else {
                when (t) {
                    is TransportEvent.PayloadProgress -> if (t.outgoing) {
                        T(model.copy(state = state.copy(sentBytes = state.sentBytes + (t.payloadId to t.bytes))))
                    } else {
                        T(model)
                    }
                    is TransportEvent.PayloadCompleted -> if (t.outgoing) {
                        maybeAllSent(model, state.copy(completed = state.completed + t.payloadId))
                    } else {
                        T(model)
                    }
                    is TransportEvent.PayloadFailed ->
                        fail(model, state.peer, state.transferId, TransferError.TransferFailed, state.startedAtMs, state.payloadIds)
                    is TransportEvent.Disconnected ->
                        fail(model, state.peer, state.transferId, TransferError.Disconnected, state.startedAtMs, emptyList())
                    is TransportEvent.MessageReceived -> when (val m = t.message) {
                        // The receipt can overtake our last progress callback.
                        is ProtocolMessage.Done -> if (m.transferId == state.transferId) {
                            receipt(model, state.peer, state.authDigits, state.transferId, state.startedAtMs, m)
                        } else {
                            T(model)
                        }
                        is ProtocolMessage.Cancel -> if (m.transferId == state.transferId) {
                            val error = if (m.reason == Reason.Integrity) TransferError.Integrity else TransferError.CancelledByPeer
                            fail(model, state.peer, state.transferId, error, state.startedAtMs, state.payloadIds)
                        } else {
                            T(model)
                        }
                        else -> T(model)
                    }
                    else -> T(model)
                }
            }
        }
        else -> T(model)
    }

    private fun maybeAllSent(model: SendModel, state: SendState.Transferring): T {
        val allSent = state.payloadIds.size == model.files.size && state.completed.containsAll(state.payloadIds)
        if (!allSent) return T(model.copy(state = state))
        return T(
            model.copy(state = SendState.AwaitingReceipt(state.peer, state.transferId, state.totalBytes, state.startedAtMs, authDigits = state.authDigits)),
            listOf(SendEffect.StartTimer(SendTimer.Receipt, timeouts.receiptMs)),
        )
    }

    private fun awaitingReceipt(model: SendModel, state: SendState.AwaitingReceipt, event: SendEvent): T = when (event) {
        is SendEvent.TimerFired -> if (event.timer == SendTimer.Receipt) {
            fail(model, state.peer, state.transferId, TransferError.TransferFailed, state.startedAtMs, emptyList())
        } else {
            T(model)
        }
        is SendEvent.Transport -> {
            val t = event.event
            val message = (t as? TransportEvent.MessageReceived)?.message
            when {
                t.endpointId != state.peer.endpointId -> T(model)
                message is ProtocolMessage.Done && message.transferId == state.transferId ->
                    receipt(model, state.peer, state.authDigits, state.transferId, state.startedAtMs, message)
                message is ProtocolMessage.Cancel && message.transferId == state.transferId -> {
                    val error = if (message.reason == Reason.Integrity) TransferError.Integrity else TransferError.CancelledByPeer
                    fail(model, state.peer, state.transferId, error, state.startedAtMs, emptyList())
                }
                t is TransportEvent.Disconnected ->
                    fail(model, state.peer, state.transferId, TransferError.Disconnected, state.startedAtMs, emptyList())
                else -> T(model)
            }
        }
        else -> T(model)
    }

    private fun receipt(
        model: SendModel,
        peer: Peer,
        authDigits: String,
        transferId: String,
        startedAtMs: Long,
        done: ProtocolMessage.Done,
    ): T {
        val skipped = done.skipped.filter { it in model.files.indices }.toSet()
        val delivered = model.files.filterIndexed { index, _ -> index !in skipped }
        if (done.sha256 != delivered.map { it.meta.sha256 }) {
            return fail(model, peer, transferId, TransferError.Integrity, startedAtMs, emptyList())
        }
        if (delivered.isEmpty()) {
            // Everything was cancelled before arriving: stay paired for another try.
            return T(
                model.copy(state = SendState.Ready(peer, authDigits)),
                listOf(SendEffect.CancelTimer(SendTimer.Receipt), SendEffect.StartTimer(SendTimer.Idle, timeouts.idleMs)),
            )
        }
        val duration = clock.elapsedMs() - startedAtMs
        val bytes = delivered.sumOf { it.meta.size }
        return T(
            model.copy(state = SendState.Completed(peer, TransferStats(bytes, duration), skipped)),
            listOf(
                SendEffect.CancelTimer(SendTimer.Receipt),
                SendEffect.Feedback(FeedbackKind.Success),
                SendEffect.Record(record(model, peer, success = true, durationMs = duration, files = delivered)),
                SendEffect.StopAll,
            ),
        )
    }

    private fun fail(
        model: SendModel,
        peer: Peer,
        transferId: String,
        error: TransferError,
        startedAtMs: Long,
        payloadIds: List<Long>,
    ): T {
        val effects = buildList {
            add(SendEffect.CancelTimer(SendTimer.Receipt))
            if (payloadIds.isNotEmpty()) add(SendEffect.CancelPayloads(payloadIds))
            if (error != TransferError.Disconnected && error != TransferError.CancelledByPeer) {
                add(SendEffect.Send(peer.endpointId, ProtocolMessage.Cancel(transferId, Reason.UserCancelled)))
            }
            add(SendEffect.Feedback(FeedbackKind.Error))
            add(SendEffect.Record(record(model, peer, success = false, durationMs = clock.elapsedMs() - startedAtMs)))
            add(SendEffect.StopAll)
        }
        return T(model.copy(state = SendState.Failed(error)), effects)
    }

    private fun completed(model: SendModel, event: SendEvent): T = when (event) {
        SendEvent.SendAnother -> T(
            SendModel(state = SendState.Searching(), files = emptyList()),
            listOf(SendEffect.StartDiscovery),
        )
        else -> T(model)
    }

    private fun failed(model: SendModel, event: SendEvent): T = when (event) {
        SendEvent.Retry, SendEvent.Start -> T(model.copy(state = SendState.Searching()), listOf(SendEffect.StartDiscovery))
        else -> T(model)
    }

    private fun cancel(model: SendModel): T {
        val state = model.state
        if (state == SendState.Idle) return T(model)
        val effects = buildList {
            SendTimer.entries.forEach { add(SendEffect.CancelTimer(it)) }
            when (state) {
                is SendState.Armed ->
                    add(SendEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.transferId, Reason.UserCancelled)))
                is SendState.Transferring -> {
                    add(SendEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.transferId, Reason.UserCancelled)))
                    if (state.payloadIds.isNotEmpty()) add(SendEffect.CancelPayloads(state.payloadIds))
                    add(SendEffect.Record(record(model, state.peer, success = false, durationMs = clock.elapsedMs() - state.startedAtMs)))
                }
                else -> Unit
            }
            add(SendEffect.StopAll)
        }
        return T(SendModel(), effects)
    }

    private fun record(
        model: SendModel,
        peer: Peer,
        success: Boolean,
        durationMs: Long,
        files: List<OutgoingFile> = model.files,
    ): TransferRecord {
        val first = files.firstOrNull()?.meta
        return TransferRecord(
            direction = TransferDirection.Sent,
            peerName = peer.name,
            fileCount = files.size,
            totalBytes = files.sumOf { it.meta.size },
            firstFileName = first?.name.orEmpty(),
            firstMime = first?.mime.orEmpty(),
            success = success,
            durationMs = durationMs.coerceAtLeast(0),
            createdAtMs = clock.wallMs(),
        )
    }
}
