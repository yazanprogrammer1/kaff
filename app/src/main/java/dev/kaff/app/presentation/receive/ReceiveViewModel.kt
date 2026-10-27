package dev.kaff.app.presentation.receive

import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.LinkQuality
import dev.kaff.app.domain.repository.LocalDevice
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.ReceiveEvent
import dev.kaff.app.domain.transfer.ReceiveState
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.domain.transfer.SendEvent
import dev.kaff.app.presentation.common.GestureCameraFactory
import dev.kaff.app.presentation.common.GestureCameraState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Immutable
data class ReceiveUiState(
    val session: ReceiveState = ReceiveState.Idle,
    val camera: GestureCameraState = GestureCameraState(),
    val deviceName: String = "",
    /** 0..1 progress of the open-palm hold that accepts the offer. */
    val holdProgress: Float = 0f,
    val linkQuality: LinkQuality = LinkQuality.Unknown,
)

/** Keeps the last completed receive for the Done screen, independent of the session lifecycle. */
@Singleton
class LastReceiveHolder @Inject constructor() {
    @Volatile var completed: ReceiveState.Completed? = null
}

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val controller: ReceiveController,
    sendController: SendController,
    device: LocalDevice,
    private val lastReceive: LastReceiveHolder,
    cameraFactory: GestureCameraFactory,
) : ViewModel() {

    private val camera = cameraFactory.create(viewModelScope)

    val analyzer: ImageAnalysis.Analyzer get() = camera.tracker

    val state: StateFlow<ReceiveUiState> = combine(controller.state, camera.state, device.name, controller.linkQuality) { session, cam, name, link ->
        val hold = if (session is ReceiveState.Offered && cam.snapshot.stable.state == GestureState.OpenPalm) {
            (cam.snapshot.heldMs.toFloat() / PALM_HOLD_MS).coerceIn(0f, 1f)
        } else {
            0f
        }
        ReceiveUiState(session, cam, name, hold, link)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReceiveUiState())

    init {
        sendController.dispatch(SendEvent.Cancel)
        when (controller.state.value) {
            ReceiveState.Idle, is ReceiveState.Failed, is ReceiveState.Completed -> controller.dispatch(ReceiveEvent.Start)
            else -> Unit
        }
        viewModelScope.launch {
            // Warm the camera as soon as a sender is paired so it is already tracking when the offer
            // lands (a cold start costs 1-1.5 s of the sender's window). Off otherwise.
            controller.state.map { it.cameraPhase() }.distinctUntilChanged().collect { phase ->
                when (phase) {
                    CameraPhase.Off -> camera.sleep()
                    CameraPhase.Warm -> camera.wake()
                    CameraPhase.Watching -> {
                        camera.resetGesture()
                        camera.wake()
                    }
                }
            }
        }
        viewModelScope.launch {
            state.map { it.holdProgress >= 1f }.distinctUntilChanged().collect { held ->
                if (held) controller.dispatch(ReceiveEvent.Accept)
            }
        }
        viewModelScope.launch {
            controller.state.collect { if (it is ReceiveState.Completed) lastReceive.completed = it }
        }
    }

    private enum class CameraPhase { Off, Warm, Watching }

    private fun ReceiveState.cameraPhase(): CameraPhase = when (this) {
        is ReceiveState.Connected -> CameraPhase.Warm
        is ReceiveState.Offered -> CameraPhase.Watching
        ReceiveState.Idle, is ReceiveState.Advertising, is ReceiveState.PairingRequest,
        is ReceiveState.Receiving, is ReceiveState.Completed, is ReceiveState.Failed,
        -> CameraPhase.Off
    }

    fun onConfirmPairing() = controller.dispatch(ReceiveEvent.ConfirmPairing)
    fun onDeclinePairing() = controller.dispatch(ReceiveEvent.DeclinePairing)
    fun onAccept() = controller.dispatch(ReceiveEvent.Accept)
    fun onReject() = controller.dispatch(ReceiveEvent.Reject)
    fun onCancelRemaining() = controller.dispatch(ReceiveEvent.CancelRemaining)
    fun onRetry() = controller.dispatch(ReceiveEvent.Start)
    fun onWakeCamera() = camera.wake()

    fun onCameraSelected(frontFacing: Boolean) {
        camera.tracker.mirrored = frontFacing
    }

    override fun onCleared() {
        // Standby mode, if enabled, restarts advertising on its own once the session is idle.
        if (controller.state.value !is ReceiveState.Completed) controller.dispatch(ReceiveEvent.Stop)
        camera.close()
    }

    companion object {
        /** How long the open palm must be held to accept (spec: about 700 ms). */
        const val PALM_HOLD_MS = 700L
    }
}
