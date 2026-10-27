package dev.kaff.app.presentation.send

import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.domain.model.GestureState
import dev.kaff.app.domain.model.LinkQuality
import dev.kaff.app.domain.model.OutgoingFile
import dev.kaff.app.domain.repository.FileRepository
import dev.kaff.app.domain.transfer.ReceiveController
import dev.kaff.app.domain.transfer.ReceiveEvent
import dev.kaff.app.domain.transfer.SendController
import dev.kaff.app.domain.transfer.SendEvent
import dev.kaff.app.domain.transfer.SendModel
import dev.kaff.app.domain.transfer.SendState
import dev.kaff.app.presentation.common.Basket
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

@Immutable
data class SendUiState(
    val session: SendState = SendState.Idle,
    val files: List<OutgoingFile> = emptyList(),
    /** Basket items still being hashed: (done, total). */
    val preparing: Pair<Int, Int>? = null,
    val fileError: Boolean = false,
    val camera: GestureCameraState = GestureCameraState(),
    val linkQuality: LinkQuality = LinkQuality.Unknown,
)

@HiltViewModel
class SendViewModel @Inject constructor(
    private val controller: SendController,
    receiveController: ReceiveController,
    private val files: FileRepository,
    private val basket: Basket,
    cameraFactory: GestureCameraFactory,
) : ViewModel() {

    private val camera = cameraFactory.create(viewModelScope)
    private val preparing = MutableStateFlow<Pair<Int, Int>?>(null)
    private val fileError = MutableStateFlow(false)

    val analyzer: ImageAnalysis.Analyzer get() = camera.tracker

    val state: StateFlow<SendUiState> = combine(controller.model, camera.state, preparing, fileError, controller.linkQuality) { model, cam, prep, err, link ->
        SendUiState(model.state, model.files, prep, err, cam, link)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SendUiState())

    init {
        // One role at a time: the radio switches from receiving (e.g. Standby) to sending.
        receiveController.dispatch(ReceiveEvent.Stop)
        controller.dispatch(SendEvent.Cancel)
        controller.dispatch(SendEvent.Start)
        prepareBasket()

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
        viewModelScope.launch {
            // A delivered basket is done; the next transfer starts empty.
            controller.model.map { it.state is SendState.Completed }.distinctUntilChanged().collect { done ->
                if (done) basket.clear()
            }
        }
    }

    /** Hashes and thumbnails every basket item while discovery and pairing run in parallel. */
    private fun prepareBasket() {
        val items = basket.items.value
        if (items.isEmpty()) return
        viewModelScope.launch {
            preparing.value = 0 to items.size
            val gate = Semaphore(PARALLEL_PREPARE)
            var done = 0
            val prepared = items.map { item ->
                async {
                    gate.withPermit { files.prepare(item) }.also {
                        done++
                        preparing.value = done to items.size
                    }
                }
            }.awaitAll()
            val ok = prepared.filterNotNull()
            fileError.value = ok.size != prepared.size
            if (ok.isNotEmpty()) controller.dispatch(SendEvent.FilesChanged(ok))
            preparing.value = null
        }
    }

    /** The fist only matters once a phone is paired, so the camera starts then (and saves power). */
    private fun SendModel.wantsCamera(): Boolean = files.isNotEmpty() && when (state) {
        is SendState.Ready, is SendState.Armed -> true
        SendState.Idle, is SendState.Searching, is SendState.Connecting, is SendState.AwaitingPairing,
        is SendState.Transferring, is SendState.AwaitingReceipt, is SendState.Completed, is SendState.Failed,
        -> false
    }

    fun onManualSend() = controller.dispatch(SendEvent.Grab)
    fun onSelectPeer(endpointId: String) = controller.dispatch(SendEvent.SelectPeer(endpointId))
    fun onRetry() = controller.dispatch(SendEvent.Retry)
    fun onCancelRemaining() = controller.dispatch(SendEvent.CancelRemaining)
    fun onWakeCamera() = camera.wake()

    fun onCameraSelected(frontFacing: Boolean) {
        camera.tracker.mirrored = frontFacing
    }

    override fun onCleared() {
        // Leaving the screen always releases the radio and the camera.
        controller.dispatch(SendEvent.Cancel)
        camera.close()
    }

    private companion object {
        const val PARALLEL_PREPARE = 3
    }
}
