package dev.kaff.app.ui.components

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Front camera with an [ImageAnalysis] use case feeding [analyzer], plus a visible preview when
 * [showPreview] is true. The camera is bound to the current lifecycle only while [active] is true,
 * so it stops on timeout, on leaving the screen and whenever the app goes to the background.
 * Without a preview only analysis is bound, which uses less power.
 *
 * Preview and analysis both use 4:3 so the landmark overlay lines up with the visible preview.
 */
@Composable
fun HandCameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    active: Boolean,
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
    onCameraSelected: (frontFacing: Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notifySelected by rememberUpdatedState(onCameraSelected)
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed so the preview can be clipped to rounded shapes.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    if (showPreview) AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(active, lifecycleOwner, analyzer, showPreview) {
        if (!active) return@DisposableEffect onDispose { }
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        providerFuture.addListener({
            if (disposed) return@addListener
            // A device without a usable camera (or one held by another app) must not crash Kaff.
            val cameraProvider = try {
                providerFuture.get().also { provider = it }
            } catch (e: Exception) {
                Log.w(TAG, "Camera unavailable", e)
                return@addListener
            }
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build(),
                )
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
            val front = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            val back = runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            if (!front && !back) {
                Log.w(TAG, "No camera on this device")
                return@addListener
            }
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            notifySelected(front)
            try {
                cameraProvider.unbindAll()
                if (showPreview) {
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } else {
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, analysis)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
}

private const val TAG = "HandCameraPreview"
private val ANALYSIS_SIZE = Size(640, 480)
