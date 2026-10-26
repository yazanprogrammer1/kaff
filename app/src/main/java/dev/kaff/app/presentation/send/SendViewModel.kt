package dev.kaff.app.presentation.send

import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.ReceiveEvent
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.domain.transfer.SendEvent
import dev.kaff.app.domain.transfer.SendModel
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.presentation.common.GestureCameraFactory
import dev.kaff.app.presentation.common.GestureCameraState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class SendUiState(
    val session: SendState = SendState.Idle,
    val files: List<OutgoingFile> = emptyList(),
    val preparing: Boolean = false,
    val fileError: Boolean = false,
    val camera: GestureCameraState = GestureCameraState(),
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val controller: SendController,
    receiveController: ReceiveController,
    private val files: FileRepository,
    cameraFactory: GestureCameraFactory,
) : ViewModel() {

    private val camera = cameraFactory.create(viewModelScope)
    private val preparing = MutableStateFlow(false)
    private val fileError = MutableStateFlow(false)

    val analyzer: ImageAnalysis.Analyzer get() = camera.tracker

    val state: StateFlow<SendUiState> = combine(controller.model, camera.state, preparing, fileError) { model, cam, prep, err ->
        SendUiState(model.state, model.files, prep, err, cam)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SendUiState())

    init {
        // One role at a time: the radio switches from receiving (e.g. Standby) to sending.
        receiveController.dispatch(ReceiveEvent.Stop)
        controller.dispatch(SendEvent.Cancel)
        controller.dispatch(SendEvent.Start)

        viewModelScope.launch {
            camera.confirmations.collect { gesture ->
                if (gesture.state == GestureState.Fist) controller.dispatch(SendEvent.Grab)
            }
        }
        viewModelScope.launch {
            controller.model.map { it.wantsCamera() }.distinctUntilChanged().collect { wanted ->
                if (wanted) camera.wake() else camera.sleep()
            }
        }
        viewModelScope.launch {
            // A held fist must be made again after a timeout or a rejection.
            controller.model.map { it.state is SendState.Ready }.distinctUntilChanged().collect { ready ->
                if (ready) camera.resetGesture()
            }
        }
    }

    private fun SendModel.wantsCamera(): Boolean = files.isNotEmpty() && when (state) {
        is SendState.Searching, is SendState.Connecting, is SendState.AwaitingPairing,
        is SendState.Ready, is SendState.Armed,
        -> true
        SendState.Idle, is SendState.Transferring, is SendState.AwaitingReceipt,
        is SendState.Completed, is SendState.Failed,
        -> false
    }

    fun onFilesPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            preparing.value = true
            fileError.value = false
            val prepared = uris.take(MAX_FILES).map { async { files.prepare(it) } }.awaitAll()
            val ok = prepared.filterNotNull()
            fileError.value = ok.size != prepared.size
            if (ok.isNotEmpty()) controller.dispatch(SendEvent.FilesChanged(ok))
            preparing.value = false
        }
    }

    fun onManualSend() = controller.dispatch(SendEvent.Grab)
    fun onChangeFiles() = controller.dispatch(SendEvent.FilesChanged(emptyList()))
    fun onSelectPeer(endpointId: String) = controller.dispatch(SendEvent.SelectPeer(endpointId))
    fun onRetry() = controller.dispatch(SendEvent.Retry)
    fun onSendAnother() = controller.dispatch(SendEvent.SendAnother)
    fun onWakeCamera() = camera.wake()

    fun onCameraSelected(frontFacing: Boolean) {
        camera.tracker.mirrored = frontFacing
    }

    override fun onCleared() {
        // Leaving the screen always releases the radio and the camera.
        controller.dispatch(SendEvent.Cancel)
        camera.close()
    }

    companion object {
        /** Keeps OFFER within the Nearby BYTES limit. */
        const val MAX_FILES = 20
    }
}
