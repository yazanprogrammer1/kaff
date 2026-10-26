package dev.kaff.app.presentation.lab

import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kaff.app.presentation.common.GestureCameraFactory
import dev.kaff.app.presentation.common.GestureCameraState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class GestureLabViewModel @Inject constructor(
    cameraFactory: GestureCameraFactory,
) : ViewModel() {

    private val camera = cameraFactory.create(viewModelScope)

    val analyzer: ImageAnalysis.Analyzer get() = camera.tracker
    val state: StateFlow<GestureCameraState> = camera.state

    fun onWake() = camera.wake()

    fun onCameraSelected(frontFacing: Boolean) {
        camera.tracker.mirrored = frontFacing
    }

    override fun onCleared() {
        camera.close()
    }
}
