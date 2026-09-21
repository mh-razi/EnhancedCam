package com.example.enhancedcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

class CapturedFrame(val jpeg: ByteArray, val rotationDegrees: Int)

@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    data class ManualRanges(
        val isoMin: Int,
        val isoMax: Int,
        val expMinNs: Long,
        val expMaxNs: Long
    )

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    // Serial executors: analyzer must be serial; capture callbacks copy ~3 MB of JPEG off-main.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * Binds Preview + ImageCapture + ImageAnalysis (a combination guaranteed on LIMITED-level
     * devices such as the Galaxy A22). Returns the device's manual ISO / shutter ranges.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer
    ): ManualRanges {
        val provider = ProcessCameraProvider.getInstance(context).await()

        val preview = Preview.Builder()
            .setResolutionSelector(selector(Size(1280, 960)))
            .build()

        // Capture is capped at ~6 MP. This keeps native memory (float stacking buffers)
        // and processing time/heat reasonable on 4 GB MediaTek devices.
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setResolutionSelector(selector(Size(2880, 2160)))
            .build()

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(selector(Size(640, 480)))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)

        provider.unbindAll()
        val cam = provider.bindToLifecycle(
            owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
        )
        preview.setSurfaceProvider(previewView.surfaceProvider)

        camera = cam
        imageCapture = capture

        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return ManualRanges(
            isoMin = iso?.lower ?: 100,
            isoMax = iso?.upper ?: 3200,
            expMinNs = max(exp?.lower ?: 100_000L, 100_000L),
            // Cap at 500 ms: longer exposures stall the preview and a burst becomes very slow.
            expMaxNs = minOf(exp?.upper ?: 500_000_000L, 500_000_000L)
        )
    }

    private fun selector(bound: Size): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(bound, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()

    /** Manual exposure via Camera2 interop; applies to preview and still captures. */
    fun applyManual(enabled: Boolean, iso: Int, exposureNs: Long) {
        val control = Camera2CameraControl.from(camera?.cameraControl ?: return)
        if (!enabled) {
            control.clearCaptureRequestOptions()
            return
        }
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION, max(exposureNs, 33_333_333L)
            )
            .build()
        control.setCaptureRequestOptions(options)
    }

    /** In-memory JPEG capture. CameraX requires takePicture on the main thread. */
    suspend fun captureJpeg(): CapturedFrame = suspendCancellableCoroutine { cont ->
        val ic = imageCapture
        if (ic == null) {
            cont.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        ic.takePicture(captureExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    cont.resume(CapturedFrame(bytes, image.imageInfo.rotationDegrees))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    /** Non-enhanced path: CameraX encodes + writes straight into MediaStore. */
    suspend fun captureToMediaStore(): Uri? = suspendCancellableCoroutine { cont ->
        val ic = imageCapture
        if (ic == null) {
            cont.resumeWithException(IllegalStateException("Camera not ready"))
            return@suspendCancellableCoroutine
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStoreSaver.contentValues(MediaStoreSaver.newFileName("IMG"))
        ).build()
        ic.takePicture(opts, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                cont.resume(output.savedUri)
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    fun shutdown() {
        analysisExecutor.shutdown()
        captureExecutor.shutdown()
    }
}
