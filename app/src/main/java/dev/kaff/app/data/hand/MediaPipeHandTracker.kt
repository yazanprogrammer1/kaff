package dev.kaff.app.data.hand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kaff.app.domain.model.HandFrame
import dev.kaff.app.domain.model.Handedness
import dev.kaff.app.domain.model.Landmark
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Creates one tracker per camera screen; each owns native MediaPipe resources until closed. */
class HandTrackerFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun create(): HandTracker = MediaPipeHandTracker(context)
}

/**
 * MediaPipe HandLandmarker in LIVE_STREAM mode. GPU delegate first, CPU as fallback.
 *
 * Frames are rotated upright and, for the front camera, mirrored before detection. MediaPipe's
 * handedness assumes a mirrored (selfie) image, so the reported label is the user's real hand and
 * the landmarks line up with the mirrored PreviewView without extra transforms.
 */
private class MediaPipeHandTracker(context: Context) : HandTracker {

    private val lock = Any()
    private val _frames = MutableSharedFlow<TrackedFrame>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _fps = MutableStateFlow(0f)
    private val _failed = MutableStateFlow(false)

    override val frames: Flow<TrackedFrame> = _frames.asSharedFlow()
    override val fps: StateFlow<Float> = _fps.asStateFlow()
    override val failed: StateFlow<Boolean> = _failed.asStateFlow()

    @Volatile override var mirrored: Boolean = true
    @Volatile override var minFrameIntervalMs: Long = 0L
    @Volatile override var paused: Boolean = false

    private var landmarker: HandLandmarker? = null
    private var closed = false
    private var lastSubmitMs = 0L
    private var lastTimestampMs = 0L
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    // Only touched on the analysis thread, read back in the result callback.
    @Volatile private var frameMirrored = true

    init {
        // CPU first: it works on every device, while the GPU delegate can crash in native code
        // (out of reach of any catch) on some GPUs and emulators. One hand at 640×480 is light work.
        landmarker = create(context, Delegate.CPU) ?: create(context, Delegate.GPU)
        _failed.value = landmarker == null
    }

    private fun create(context: Context, delegate: Delegate): HandLandmarker? = try {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .setDelegate(delegate)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(MIN_CONFIDENCE)
            .setMinHandPresenceConfidence(MIN_CONFIDENCE)
            .setMinTrackingConfidence(MIN_CONFIDENCE)
            .setResultListener(::onResult)
            .setErrorListener { error -> Log.w(TAG, "HandLandmarker error", error) }
            .build()
        HandLandmarker.createFromOptions(context, options)
    } catch (e: RuntimeException) {
        Log.w(TAG, "HandLandmarker unavailable on $delegate", e)
        null
    } catch (e: LinkageError) {
        // A broken native or static setup (ExceptionInInitializerError, UnsatisfiedLinkError):
        // show the "hand tracking unavailable" state instead of closing the app.
        Log.e(TAG, "HandLandmarker failed to load on $delegate", e)
        null
    }

    override fun analyze(image: ImageProxy) {
        image.use { proxy ->
            if (paused) return
            val now = SystemClock.uptimeMillis()
            if (now - lastSubmitMs < minFrameIntervalMs) return
            lastSubmitMs = now
            val mirror = mirrored
            val input = proxy.toUprightBitmap(mirror)
            synchronized(lock) {
                val detector = landmarker ?: return
                if (closed) return
                // LIVE_STREAM requires strictly increasing timestamps.
                val timestamp = maxOf(now, lastTimestampMs + 1)
                lastTimestampMs = timestamp
                frameMirrored = mirror
                try {
                    detector.detectAsync(BitmapImageBuilder(input).build(), timestamp)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "detectAsync failed", e)
                }
            }
        }
    }

    private fun ImageProxy.toUprightBitmap(mirror: Boolean): Bitmap {
        val source = toBitmap()
        val matrix = Matrix().apply {
            postRotate(imageInfo.rotationDegrees.toFloat())
            if (mirror) postScale(-1f, 1f)
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false).also {
            if (it !== source) source.recycle()
        }
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        countFps(result.timestampMs())
        val landmarks = result.landmarks().firstOrNull()
        val hand = if (landmarks != null && landmarks.size == HandFrame.LANDMARK_COUNT) {
            val label = result.handedness().firstOrNull()?.firstOrNull()?.categoryName()
            val reported = if (label.equals("Left", ignoreCase = true)) Handedness.Left else Handedness.Right
            HandFrame(
                landmarks = landmarks.map { Landmark(it.x(), it.y(), it.z()) },
                // Labels are only correct for mirrored input; swap them for an unmirrored camera.
                handedness = if (frameMirrored) reported else reported.opposite(),
                timestampMs = result.timestampMs(),
                imageWidth = input.width,
                imageHeight = input.height,
            )
        } else {
            null
        }
        _frames.tryEmit(TrackedFrame(hand, result.timestampMs()))
    }

    private fun countFps(timestampMs: Long) {
        if (fpsWindowStart == 0L) fpsWindowStart = timestampMs
        fpsFrames++
        val elapsed = timestampMs - fpsWindowStart
        if (elapsed >= FPS_WINDOW_MS) {
            _fps.value = fpsFrames * 1000f / elapsed
            fpsFrames = 0
            fpsWindowStart = timestampMs
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            landmarker?.close()
            landmarker = null
        }
    }

    private fun Handedness.opposite() = if (this == Handedness.Left) Handedness.Right else Handedness.Left

    private companion object {
        const val TAG = "HandTracker"
        const val MODEL_ASSET = "hand_landmarker.task"
        const val MIN_CONFIDENCE = 0.5f
        const val FPS_WINDOW_MS = 1000L
    }
}
