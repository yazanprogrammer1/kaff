package dev.kaff.app.testing

import androidx.camera.core.ImageProxy
import dev.kaff.app.data.hand.HandTracker
import dev.kaff.app.data.hand.TrackedFrame
import dev.kaff.app.domain.model.HandFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lets tests push hand frames as if the camera produced them. */
class FakeHandTracker : HandTracker {
    private val _frames = MutableSharedFlow<TrackedFrame>(extraBufferCapacity = 64)
    override val frames: Flow<TrackedFrame> = _frames
    override val fps: StateFlow<Float> = MutableStateFlow(30f)
    override val failed: StateFlow<Boolean> = MutableStateFlow(false)
    override var mirrored: Boolean = true
    override var minFrameIntervalMs: Long = 0L
    override var paused: Boolean = false
    var closed = false
        private set

    fun emit(hand: HandFrame?, timestampMs: Long) {
        if (!paused) _frames.tryEmit(TrackedFrame(hand, timestampMs))
    }

    override fun analyze(image: ImageProxy) = image.close()

    override fun close() {
        closed = true
    }
}
