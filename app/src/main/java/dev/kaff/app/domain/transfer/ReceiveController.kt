package dev.kaff.app.domain.transfer

import dev.kaff.app.domain.repository.Clock
import dev.kaff.app.domain.repository.FeedbackPlayer
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.repository.HistoryRepository
import dev.kaff.app.domain.repository.IncomingNotifier
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.repository.NearbyTransport
import dev.kaff.app.domain.repository.SaveResult
import dev.kaff.app.domain.repository.TransportEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives [ReceiveReducer]. Process-wide so Standby mode and the receive screen share one session.
 */
class ReceiveController(
    private val transport: NearbyTransport,
    private val files: FileRepository,
    private val history: HistoryRepository,
    private val feedback: FeedbackPlayer,
    private val notifier: IncomingNotifier,
    device: LocalDevice,
    clock: Clock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    timeouts: SessionTimeouts = SessionTimeouts(),
) {
    private val reducer = ReceiveReducer(clock, { device.name.value }, timeouts)
    private val timers = SessionTimers<ReceiveTimer>(scope) { dispatch(ReceiveEvent.TimerFired(it)) }
    private val runner = SessionRunner<ReceiveState, ReceiveEvent, ReceiveEffect>(
        ReceiveState.Idle,
        scope,
        reducer::reduce,
        ::handle,
    )

    val state: StateFlow<ReceiveState> = runner.model

    init {
        scope.launch { transport.events.collect { dispatch(ReceiveEvent.Transport(it)) } }
    }

    fun dispatch(event: ReceiveEvent) = runner.dispatch(event)

    private suspend fun handle(effect: ReceiveEffect) {
        when (effect) {
            is ReceiveEffect.StartAdvertising ->
                transport.startAdvertising(effect.localName)?.let { dispatch(ReceiveEvent.RadioFailed(it)) }
            ReceiveEffect.StopAdvertising -> transport.stopAdvertising()
            is ReceiveEffect.AcceptConnection -> if (!transport.acceptConnection(effect.endpointId)) {
                dispatch(ReceiveEvent.Transport(TransportEvent.ConnectionResult(effect.endpointId, connected = false)))
            }
            is ReceiveEffect.RejectConnection -> transport.rejectConnection(effect.endpointId)
            is ReceiveEffect.Send -> transport.send(effect.endpointId, effect.message)
            is ReceiveEffect.SaveFile -> scope.launch(ioDispatcher) {
                val result = try {
                    files.save(effect.file, effect.meta)
                } finally {
                    effect.file.discard()
                }
                dispatch(
                    when (result) {
                        is SaveResult.Saved -> ReceiveEvent.FileSaved(effect.transferId, effect.index, result.file)
                        SaveResult.IntegrityError -> ReceiveEvent.SaveFailed(effect.transferId, effect.index, integrity = true)
                        SaveResult.StorageError -> ReceiveEvent.SaveFailed(effect.transferId, effect.index, integrity = false)
                    },
                )
            }
            is ReceiveEffect.Discard -> effect.files.forEach { it.discard() }
            is ReceiveEffect.CancelPayloads -> effect.payloadIds.forEach { transport.cancelPayload(it) }
            ReceiveEffect.StopAll -> transport.stopAll()
            is ReceiveEffect.StartTimer -> timers.start(effect.timer, effect.delayMs)
            is ReceiveEffect.CancelTimer -> timers.cancel(effect.timer)
            is ReceiveEffect.Feedback -> feedback.play(effect.kind)
            is ReceiveEffect.Alert -> notifier.alert(effect.alert)
            ReceiveEffect.ClearAlert -> notifier.clear()
            is ReceiveEffect.Record -> scope.launch { history.add(effect.record) }
        }
    }
}
