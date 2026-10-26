package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.model.FileMeta
import dev.kaff.app.domain.model.Peer
import dev.kaff.app.domain.model.ReceivedFile
import dev.kaff.app.domain.model.SavedFile
import dev.kaff.app.domain.model.TransferDirection
import dev.kaff.app.domain.model.TransferError
import dev.kaff.app.domain.model.TransferRecord
import dev.kaff.app.domain.model.TransferStats
import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.TransportEvent
import dev.kaff.app.domain.transfer.ProtocolMessage.Reason

sealed interface ReceiveState {
    data object Idle : ReceiveState

    /** Visible to nearby senders. [notice] explains why a previous attempt ended. */
    data class Advertising(val notice: TransferError? = null) : ReceiveState

    /** A sender wants to connect. Nothing is accepted until the user confirms the digits. */
    data class PairingRequest(val peer: Peer, val authDigits: String, val confirmed: Boolean = false) : ReceiveState

    data class Connected(val peer: Peer, val authDigits: String, val notice: TransferError? = null) : ReceiveState

    /** An offer is on screen; the camera watches for the palm (or the Accept button). */
    data class Offered(
        val peer: Peer,
        val authDigits: String,
        val offer: ProtocolMessage.Offer,
        val armed: Boolean,
        val offeredAtMs: Long,
        val watchMs: Long,
    ) : ReceiveState

    data class Receiving(
        val peer: Peer,
        val authDigits: String,
        val offer: ProtocolMessage.Offer,
        val startedAtMs: Long,
        /** payloadId -> file index, from FILE headers. */
        val headers: Map<Long, Int> = emptyMap(),
        /** Payloads that finished before their header arrived. */
        val pending: Map<Long, ReceivedFile> = emptyMap(),
        val receivedBytes: Map<Long, Long> = emptyMap(),
        val saved: Map<Int, SavedFile> = emptyMap(),
    ) : ReceiveState {
        val progress: Float
            get() = if (offer.totalBytes <= 0) 0f else (receivedBytes.values.sum().toFloat() / offer.totalBytes).coerceIn(0f, 1f)
    }

    data class Completed(
        val peer: Peer,
        val offer: ProtocolMessage.Offer,
        val saved: List<SavedFile>,
        val stats: TransferStats,
    ) : ReceiveState

    data class Failed(val error: TransferError) : ReceiveState
}

sealed interface ReceiveEvent {
    data object Start : ReceiveEvent
    data object Stop : ReceiveEvent
    data object ConfirmPairing : ReceiveEvent
    data object DeclinePairing : ReceiveEvent

    /** Confirmed open palm held long enough, or the Accept button. */
    data object Accept : ReceiveEvent
    data object Reject : ReceiveEvent
    /** Advertising could not start; [error] says why (permission, location, Bluetooth). */
    data class RadioFailed(val error: TransferError) : ReceiveEvent
    data class Transport(val event: TransportEvent) : ReceiveEvent
    data class TimerFired(val timer: ReceiveTimer) : ReceiveEvent
    data class FileSaved(val transferId: String, val index: Int, val file: SavedFile) : ReceiveEvent
    data class SaveFailed(val transferId: String, val index: Int, val integrity: Boolean) : ReceiveEvent
}

enum class ReceiveTimer { Watch, Linger, Idle }

/** What a background notification should say. */
sealed interface IncomingAlert {
    data class Pairing(val peerName: String) : IncomingAlert
    data class Offer(val peerName: String, val offer: ProtocolMessage.Offer) : IncomingAlert
}

sealed interface ReceiveEffect {
    data class StartAdvertising(val localName: String) : ReceiveEffect
    data object StopAdvertising : ReceiveEffect
    data class AcceptConnection(val endpointId: String) : ReceiveEffect
    data class RejectConnection(val endpointId: String) : ReceiveEffect
    data class Send(val endpointId: String, val message: ProtocolMessage) : ReceiveEffect
    data class SaveFile(val transferId: String, val index: Int, val meta: FileMeta, val file: ReceivedFile) : ReceiveEffect
    data class Discard(val files: List<ReceivedFile>) : ReceiveEffect
    data class CancelPayloads(val payloadIds: List<Long>) : ReceiveEffect
    data object StopAll : ReceiveEffect
    data class StartTimer(val timer: ReceiveTimer, val delayMs: Long) : ReceiveEffect
    data class CancelTimer(val timer: ReceiveTimer) : ReceiveEffect
    data class Feedback(val kind: FeedbackKind) : ReceiveEffect
    data class Alert(val alert: IncomingAlert) : ReceiveEffect
    data object ClearAlert : ReceiveEffect
    data class Record(val record: TransferRecord) : ReceiveEffect
}

private typealias R = Transition<ReceiveState, ReceiveEffect>

/** Pure receiver state machine. */
class ReceiveReducer(
    private val clock: Clock,
    private val localName: () -> String,
    private val timeouts: SessionTimeouts = SessionTimeouts(),
) {

    fun reduce(state: ReceiveState, event: ReceiveEvent): R {
        when (event) {
            ReceiveEvent.Stop -> return stop(state)
            is ReceiveEvent.RadioFailed -> if (state != ReceiveState.Idle) {
                return R(ReceiveState.Failed(event.error), listOf(ReceiveEffect.StopAll))
            }
            else -> Unit
        }
        return when (state) {
            ReceiveState.Idle -> idle(state, event)
            is ReceiveState.Advertising -> advertising(state, event)
            is ReceiveState.PairingRequest -> pairing(state, event)
            is ReceiveState.Connected -> connected(state, event)
            is ReceiveState.Offered -> offered(state, event)
            is ReceiveState.Receiving -> receiving(state, event)
            is ReceiveState.Completed -> completed(state, event)
            is ReceiveState.Failed -> failed(state, event)
        }
    }

    private fun startAdvertising(notice: TransferError? = null, extra: List<ReceiveEffect> = emptyList()): R =
        R(ReceiveState.Advertising(notice), extra + ReceiveEffect.StartAdvertising(localName()))

    private fun idle(state: ReceiveState, event: ReceiveEvent): R = when (event) {
        ReceiveEvent.Start -> startAdvertising()
        else -> R(state)
    }

    private fun advertising(state: ReceiveState.Advertising, event: ReceiveEvent): R {
        val t = (event as? ReceiveEvent.Transport)?.event
        return if (t is TransportEvent.ConnectionInitiated && t.incoming) {
            R(
                ReceiveState.PairingRequest(Peer(t.endpointId, t.name), t.authDigits),
                listOf(ReceiveEffect.Feedback(FeedbackKind.Attention), ReceiveEffect.Alert(IncomingAlert.Pairing(t.name))),
            )
        } else {
            R(state)
        }
    }

    private fun pairing(state: ReceiveState.PairingRequest, event: ReceiveEvent): R = when (event) {
        ReceiveEvent.ConfirmPairing -> if (state.confirmed) {
            R(state)
        } else {
            R(state.copy(confirmed = true), listOf(ReceiveEffect.AcceptConnection(state.peer.endpointId)))
        }
        ReceiveEvent.DeclinePairing -> R(
            ReceiveState.Advertising(),
            listOf(ReceiveEffect.RejectConnection(state.peer.endpointId), ReceiveEffect.ClearAlert),
        )
        is ReceiveEvent.Transport -> when (val t = event.event) {
            // Only one sender at a time: refuse anyone else while a pairing is on screen.
            is TransportEvent.ConnectionInitiated -> if (t.endpointId != state.peer.endpointId && t.incoming) {
                R(state, listOf(ReceiveEffect.RejectConnection(t.endpointId)))
            } else {
                R(state)
            }
            is TransportEvent.ConnectionResult -> when {
                t.endpointId != state.peer.endpointId -> R(state)
                t.connected -> R(
                    ReceiveState.Connected(state.peer, state.authDigits),
                    listOf(
                        ReceiveEffect.StopAdvertising,
                        ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Hello(ProtocolCodec.VERSION, localName())),
                        ReceiveEffect.StartTimer(ReceiveTimer.Idle, timeouts.idleMs),
                    ),
                )
                else -> R(ReceiveState.Advertising(TransferError.ConnectionFailed), listOf(ReceiveEffect.ClearAlert))
            }
            is TransportEvent.Disconnected -> if (t.endpointId == state.peer.endpointId) {
                R(ReceiveState.Advertising(TransferError.Disconnected), listOf(ReceiveEffect.ClearAlert))
            } else {
                R(state)
            }
            else -> R(state)
        }
        else -> R(state)
    }

    /** Disconnects and incompatible versions while linked; null when [t] is not one of those. */
    private fun linkEvent(peer: Peer, t: TransportEvent, cleanup: List<ReceiveEffect> = emptyList()): R? {
        if (t.endpointId != peer.endpointId) return null
        return when (t) {
            is TransportEvent.Disconnected -> startAdvertising(
                TransferError.Disconnected,
                cleanup + ReceiveEffect.CancelTimer(ReceiveTimer.Watch) + ReceiveEffect.CancelTimer(ReceiveTimer.Idle) + ReceiveEffect.ClearAlert,
            )
            is TransportEvent.BadMessage -> if (t.unsupportedVersion) {
                R(ReceiveState.Failed(TransferError.VersionMismatch), cleanup + ReceiveEffect.StopAll)
            } else {
                null
            }
            else -> null
        }
    }

    private fun connected(state: ReceiveState.Connected, event: ReceiveEvent): R = when (event) {
        is ReceiveEvent.TimerFired -> if (event.timer == ReceiveTimer.Idle) {
            // Never keep an idle link: drop it and stay visible for the next sender.
            startAdvertising(extra = listOf(ReceiveEffect.StopAll))
        } else {
            R(state)
        }
        is ReceiveEvent.Transport -> {
            val t = event.event
            val message = (t as? TransportEvent.MessageReceived)?.message
            if (message is ProtocolMessage.Offer && t.endpointId == state.peer.endpointId) {
                offer(state.peer, state.authDigits, message)
            } else {
                linkEvent(state.peer, t) ?: R(state)
            }
        }
        else -> R(state)
    }

    private fun offer(peer: Peer, digits: String, offer: ProtocolMessage.Offer): R = R(
        ReceiveState.Offered(peer, digits, offer, armed = false, offeredAtMs = clock.elapsedMs(), watchMs = timeouts.watchMs),
        listOf(
            ReceiveEffect.CancelTimer(ReceiveTimer.Idle),
            ReceiveEffect.StartTimer(ReceiveTimer.Watch, timeouts.watchMs),
            ReceiveEffect.Feedback(FeedbackKind.Attention),
            ReceiveEffect.Alert(IncomingAlert.Offer(peer.name, offer)),
        ),
    )

    private fun backToConnected(state: ReceiveState.Offered, notice: TransferError?, extra: List<ReceiveEffect>): R = R(
        ReceiveState.Connected(state.peer, state.authDigits, notice),
        extra + ReceiveEffect.CancelTimer(ReceiveTimer.Watch) + ReceiveEffect.ClearAlert +
            ReceiveEffect.StartTimer(ReceiveTimer.Idle, timeouts.idleMs),
    )

    private fun offered(state: ReceiveState.Offered, event: ReceiveEvent): R = when (event) {
        ReceiveEvent.Accept -> R(
            ReceiveState.Receiving(state.peer, state.authDigits, state.offer, startedAtMs = clock.elapsedMs()),
            listOf(
                ReceiveEffect.CancelTimer(ReceiveTimer.Watch),
                ReceiveEffect.ClearAlert,
                ReceiveEffect.Feedback(FeedbackKind.Accept),
                ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Accept(state.offer.transferId)),
            ),
        )
        ReceiveEvent.Reject -> backToConnected(
            state,
            null,
            listOf(ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Reject(state.offer.transferId, Reason.UserDeclined))),
        )
        is ReceiveEvent.TimerFired -> if (event.timer == ReceiveTimer.Watch) {
            backToConnected(
                state,
                TransferError.WatchTimeout,
                listOf(ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Reject(state.offer.transferId, Reason.Timeout))),
            )
        } else {
            R(state)
        }
        is ReceiveEvent.Transport -> {
            val t = event.event
            val message = (t as? TransportEvent.MessageReceived)?.message
            when {
                t.endpointId != state.peer.endpointId -> R(state)
                message is ProtocolMessage.Armed && message.transferId == state.offer.transferId -> R(state.copy(armed = true))
                message is ProtocolMessage.Cancel && message.transferId == state.offer.transferId ->
                    backToConnected(state, TransferError.OfferExpired, emptyList())
                // A fresh offer (the sender grabbed again) replaces the old one.
                message is ProtocolMessage.Offer -> offer(state.peer, state.authDigits, message)
                else -> linkEvent(state.peer, t) ?: R(state)
            }
        }
        else -> R(state)
    }

    private fun receiving(state: ReceiveState.Receiving, event: ReceiveEvent): R = when (event) {
        is ReceiveEvent.FileSaved -> if (event.transferId == state.offer.transferId) {
            val next = state.copy(saved = state.saved + (event.index to event.file))
            if (next.saved.size == state.offer.files.size) complete(next) else R(next)
        } else {
            R(state)
        }
        is ReceiveEvent.SaveFailed -> if (event.transferId == state.offer.transferId) {
            val reason = if (event.integrity) Reason.Integrity else Reason.StorageError
            fail(
                state,
                if (event.integrity) TransferError.Integrity else TransferError.SaveFailed,
                listOf(ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.offer.transferId, reason))),
            )
        } else {
            R(state)
        }
        is ReceiveEvent.Transport -> receivingTransport(state, event.event)
        else -> R(state)
    }

    private fun receivingTransport(state: ReceiveState.Receiving, t: TransportEvent): R {
        if (t.endpointId != state.peer.endpointId) return R(state)
        return when (t) {
            is TransportEvent.MessageReceived -> when (val m = t.message) {
                is ProtocolMessage.FileHeader -> {
                    if (m.transferId != state.offer.transferId || m.index !in state.offer.files.indices) return R(state)
                    val early = state.pending[m.payloadId]
                    val next = state.copy(headers = state.headers + (m.payloadId to m.index), pending = state.pending - m.payloadId)
                    if (early != null) R(next, listOf(save(state, m.index, early))) else R(next)
                }
                is ProtocolMessage.Cancel -> if (m.transferId == state.offer.transferId) {
                    fail(state, TransferError.CancelledByPeer, emptyList())
                } else {
                    R(state)
                }
                else -> R(state)
            }
            is TransportEvent.PayloadProgress -> if (!t.outgoing) {
                R(state.copy(receivedBytes = state.receivedBytes + (t.payloadId to t.bytes)))
            } else {
                R(state)
            }
            is TransportEvent.PayloadCompleted -> {
                val file = t.file
                if (t.outgoing || file == null) return R(state)
                val index = state.headers[t.payloadId]
                if (index == null) {
                    R(state.copy(pending = state.pending + (t.payloadId to file)))
                } else {
                    R(state, listOf(save(state, index, file)))
                }
            }
            is TransportEvent.PayloadFailed -> if (!t.outgoing) {
                fail(
                    state,
                    TransferError.TransferFailed,
                    listOf(ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.offer.transferId, Reason.StorageError))),
                )
            } else {
                R(state)
            }
            is TransportEvent.Disconnected -> fail(state, TransferError.Disconnected, emptyList())
            else -> R(state)
        }
    }

    private fun save(state: ReceiveState.Receiving, index: Int, file: ReceivedFile): ReceiveEffect =
        ReceiveEffect.SaveFile(state.offer.transferId, index, state.offer.files[index], file)

    private fun complete(state: ReceiveState.Receiving): R {
        val saved = state.offer.files.indices.map { state.saved.getValue(it) }
        val duration = clock.elapsedMs() - state.startedAtMs
        return R(
            ReceiveState.Completed(state.peer, state.offer, saved, TransferStats(state.offer.totalBytes, duration)),
            listOf(
                ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Done(state.offer.transferId, saved.map { it.meta.sha256 })),
                ReceiveEffect.Feedback(FeedbackKind.Success),
                ReceiveEffect.Record(record(state.peer, state.offer, true, duration, saved.firstOrNull()?.uri)),
                ReceiveEffect.StartTimer(ReceiveTimer.Linger, timeouts.lingerMs),
            ),
        )
    }

    private fun fail(state: ReceiveState.Receiving, error: TransferError, extra: List<ReceiveEffect>): R = R(
        ReceiveState.Failed(error),
        extra + listOf(
            ReceiveEffect.Discard(state.pending.values.toList()),
            ReceiveEffect.CancelPayloads(state.headers.keys.toList()),
            ReceiveEffect.Feedback(FeedbackKind.Error),
            ReceiveEffect.Record(record(state.peer, state.offer, false, clock.elapsedMs() - state.startedAtMs, null)),
            ReceiveEffect.StopAll,
        ),
    )

    private fun completed(state: ReceiveState.Completed, event: ReceiveEvent): R = when (event) {
        is ReceiveEvent.TimerFired -> if (event.timer == ReceiveTimer.Linger) R(state, listOf(ReceiveEffect.StopAll)) else R(state)
        ReceiveEvent.Start -> startAdvertising(extra = listOf(ReceiveEffect.CancelTimer(ReceiveTimer.Linger), ReceiveEffect.StopAll))
        else -> R(state)
    }

    private fun failed(state: ReceiveState.Failed, event: ReceiveEvent): R = when (event) {
        ReceiveEvent.Start -> startAdvertising()
        else -> R(state)
    }

    private fun stop(state: ReceiveState): R {
        if (state == ReceiveState.Idle) return R(state)
        val effects = buildList {
            ReceiveTimer.entries.forEach { add(ReceiveEffect.CancelTimer(it)) }
            add(ReceiveEffect.ClearAlert)
            when (state) {
                is ReceiveState.Offered -> add(
                    ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Reject(state.offer.transferId, Reason.UserDeclined)),
                )
                is ReceiveState.Receiving -> {
                    add(ReceiveEffect.Send(state.peer.endpointId, ProtocolMessage.Cancel(state.offer.transferId, Reason.UserCancelled)))
                    add(ReceiveEffect.Discard(state.pending.values.toList()))
                    add(ReceiveEffect.CancelPayloads(state.headers.keys.toList()))
                    add(ReceiveEffect.Record(record(state.peer, state.offer, false, clock.elapsedMs() - state.startedAtMs, null)))
                }
                else -> Unit
            }
            add(ReceiveEffect.StopAll)
        }
        return R(ReceiveState.Idle, effects)
    }

    private fun record(
        peer: Peer,
        offer: ProtocolMessage.Offer,
        success: Boolean,
        durationMs: Long,
        savedUri: String?,
    ): TransferRecord = TransferRecord(
        direction = TransferDirection.Received,
        peerName = peer.name,
        fileCount = offer.files.size,
        totalBytes = offer.totalBytes,
        firstFileName = offer.files.firstOrNull()?.name.orEmpty(),
        firstMime = offer.files.firstOrNull()?.mime.orEmpty(),
        success = success,
        durationMs = durationMs.coerceAtLeast(0),
        createdAtMs = clock.wallMs(),
        savedUri = savedUri,
    )
}
