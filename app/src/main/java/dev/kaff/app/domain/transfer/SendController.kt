package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.FeedbackPlayer
import dev.kaff.app.domain.repository.HistoryRepository
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.repository.TransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Drives [SendReducer] with the real transport. One instance per process. */
class SendController(
    private val transport: NearbyTransport,
    private val history: HistoryRepository,
    private val feedback: FeedbackPlayer,
    private val device: LocalDevice,
    clock: Clock,
    private val scope: CoroutineScope,
    timeouts: SessionTimeouts = SessionTimeouts(),
    newTransferId: () -> String = { UUID.randomUUID().toString() },
) {
    private val reducer = SendReducer(clock, { device.name.value }, newTransferId, timeouts)
    private val timers = SessionTimers<SendTimer>(scope) { dispatch(SendEvent.TimerFired(it)) }
    private val runner = SessionRunner(SendModel(), scope, reducer::reduce, ::handle)

    val model: StateFlow<SendModel> = runner.model

    init {
        scope.launch { transport.events.collect { dispatch(SendEvent.Transport(it)) } }
    }

    fun dispatch(event: SendEvent) = runner.dispatch(event)

    private suspend fun handle(effect: SendEffect) {
        when (effect) {
            SendEffect.StartDiscovery -> if (!transport.startDiscovery()) dispatch(SendEvent.NearbyUnavailable)
            SendEffect.StopDiscovery -> transport.stopDiscovery()
            is SendEffect.RequestConnection -> if (!transport.requestConnection(device.name.value, effect.endpointId)) {
                dispatch(SendEvent.Transport(TransportEvent.ConnectionResult(effect.endpointId, connected = false)))
            }
            is SendEffect.AcceptConnection -> if (!transport.acceptConnection(effect.endpointId)) {
                dispatch(SendEvent.Transport(TransportEvent.ConnectionResult(effect.endpointId, connected = false)))
            }
            is SendEffect.Send -> transport.send(effect.endpointId, effect.message)
            is SendEffect.QueueFiles -> scope.launch { queue(effect) }
            is SendEffect.CancelPayloads -> effect.payloadIds.forEach { transport.cancelPayload(it) }
            is SendEffect.Disconnect -> transport.disconnect(effect.endpointId)
            SendEffect.StopAll -> transport.stopAll()
            is SendEffect.StartTimer -> timers.start(effect.timer, effect.delayMs)
            is SendEffect.CancelTimer -> timers.cancel(effect.timer)
            is SendEffect.Feedback -> feedback.play(effect.kind)
            is SendEffect.Record -> scope.launch { history.add(effect.record) }
        }
    }

    private suspend fun queue(effect: SendEffect.QueueFiles) {
        val ids = ArrayList<Long>(effect.files.size)
        effect.files.forEachIndexed { index, file ->
            val payloadId = transport.sendFile(effect.endpointId, file)
            if (payloadId == null) {
                dispatch(SendEvent.QueueFailed(effect.transferId))
                return
            }
            transport.send(effect.endpointId, ProtocolMessage.FileHeader(effect.transferId, index, payloadId))
            ids += payloadId
        }
        dispatch(SendEvent.FilesQueued(effect.transferId, ids))
    }
}
