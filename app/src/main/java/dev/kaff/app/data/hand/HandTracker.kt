package dev.kaff.app.data.hand

import androidx.camera.core.ImageAnalysis
import dev.kaff.app.domain.model.HandFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One analyzed camera frame; [hand] is null when no hand was found. */
data class TrackedFrame(val hand: HandFrame?, val timestampMs: Long)

/**
 * Camera frame analyzer that detects one hand per frame. Bound to CameraX as an
 * [ImageAnalysis.Analyzer]; results arrive on [frames] from a background thread.
 */
interface HandTracker : ImageAnalysis.Analyzer {
    val frames: Flow<TrackedFrame>
    val fps: StateFlow<Float>
    val failed: StateFlow<Boolean>

    /** True for the front camera: frames are mirrored so landmarks match the selfie preview. */
    var mirrored: Boolean

    /** Frames arriving sooner than this after the previous analyzed frame are dropped. */
    var minFrameIntervalMs: Long

    /** Drops every frame without analysis (thermal pause, camera asleep). */
    var paused: Boolean

    fun close()
}
