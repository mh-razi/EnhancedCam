package com.example.enhancedcam

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.OpenCVLoader
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

data class UiState(
    val enhanced: Boolean = true,
    val burstCount: Int = 4,
    val manualEnabled: Boolean = false,
    val iso: Int = 400,
    val shutterNs: Long = 16_666_666L,
    val ranges: CameraController.ManualRanges? = null,
    val isCapturing: Boolean = false,
    val processingJobs: Int = 0,
    val thermalThrottled: Boolean = false,
    val status: String = "",
    val lastSavedUri: Uri? = null
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = CameraController(app)
    private val opencvReady: Boolean = OpenCVLoader.initLocal()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _liveFrame = MutableStateFlow<Bitmap?>(null)
    val liveFrame: StateFlow<Bitmap?> = _liveFrame.asStateFlow()

    private val processMutex = Mutex() // one heavy stack at a time -> bounded RAM + heat
    private var lastLiveMs = 0L

    private val powerManager = app.getSystemService(PowerManager::class.java)
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val hot = status >= PowerManager.THERMAL_STATUS_MODERATE
        _ui.update { it.copy(thermalThrottled = hot) }
        if (hot) _liveFrame.value = null
    }

    init {
        if (!opencvReady) _ui.update { it.copy(status = "OpenCV failed to initialise") }
        powerManager?.addThermalStatusListener(ContextCompat.getMainExecutor(app), thermalListener)
    }

    // ------------------------------------------------------------------ camera

    suspend fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        try {
            val ranges = controller.bind(owner, previewView) { proxy -> onAnalysisFrame(proxy) }
            _ui.update {
                it.copy(
                    ranges = ranges,
                    iso = 400.coerceIn(ranges.isoMin, ranges.isoMax),
                    shutterNs = 16_666_666L.coerceIn(ranges.expMinNs, ranges.expMaxNs),
                    status = ""
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(status = "Camera error: ${e.message}") }
        }
    }

    /** Runs on the analysis executor (background thread), never on Main. */
    private fun onAnalysisFrame(proxy: ImageProxy) {
        try {
            val s = _ui.value
            val now = SystemClock.elapsedRealtime()
            if (!s.enhanced || s.thermalThrottled || !opencvReady || now - lastLiveMs < 120) return
            lastLiveMs = now
            _liveFrame.value = ImageProcessor.enhanceLiveFrame(proxy)
        } catch (e: Exception) {
            // Drop this preview frame; never crash the analyzer.
        } finally {
            proxy.close()
        }
    }

    // ---------------------------------------------------------------- controls

    fun setEnhanced(on: Boolean) {
        _ui.update { it.copy(enhanced = on) }
        if (!on) _liveFrame.value = null
    }

    fun setBurstCount(n: Int) = _ui.update { it.copy(burstCount = n) }

    fun setManualEnabled(on: Boolean) {
        _ui.update { it.copy(manualEnabled = on) }
        pushManual()
    }

    fun setIso(iso: Int) {
        _ui.update { it.copy(iso = iso) }
        pushManual()
    }

    /** t in 0..1, mapped logarithmically between min and max exposure time. */
    fun setShutterFraction(t: Float) {
        val r = _ui.value.ranges ?: return
        val lo = ln(r.expMinNs.toDouble())
        val hi = ln(r.expMaxNs.toDouble())
        val ns = exp(lo + t.coerceIn(0f, 1f) * (hi - lo)).toLong()
        _ui.update { it.copy(shutterNs = ns.coerceIn(r.expMinNs, r.expMaxNs)) }
        pushManual()
    }

    private fun pushManual() {
        val s = _ui.value
        controller.applyManual(s.manualEnabled, s.iso, s.shutterNs)
    }

    // ----------------------------------------------------------------- capture

    fun capture() {
        val start = _ui.value
        if (start.isCapturing || start.processingJobs >= 2 || !opencvReady) return

        // viewModelScope runs on Main, which CameraX takePicture() requires.
        viewModelScope.launch {
            _ui.update { it.copy(isCapturing = true) }
            try {
                val s = _ui.value
                if (!s.enhanced) {
                    val uri = controller.captureToMediaStore()
                    _ui.update { it.copy(status = "Saved", lastSavedUri = uri) }
                    return@launch
                }

                // Under thermal pressure, shoot fewer frames.
                val n = if (s.thermalThrottled) min(s.burstCount, 3) else s.burstCount
                val frames = ArrayList<ByteArray>(n)
                var rotation = 0
                for (i in 1..n) {
                    _ui.update { it.copy(status = "Capturing $i/$n - hold still") }
                    val f = controller.captureJpeg()
                    frames += f.jpeg
                    rotation = f.rotationDegrees
                }
                // Shutter is free again; stacking + saving continue in the background.
                launchProcessing(frames, rotation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(status = "Capture failed: ${e.message}") }
            } finally {
                _ui.update { it.copy(isCapturing = false) }
            }
        }
    }

    private fun launchProcessing(frames: List<ByteArray>, rotation: Int) {
        val app = getApplication<Application>()
        _ui.update { it.copy(processingJobs = it.processingJobs + 1, status = "Processing...") }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                processMutex.withLock {
                    val jpeg = ImageProcessor.processBurst(frames, rotation)
                    val uri = MediaStoreSaver.saveJpeg(app, jpeg)
                    _ui.update { it.copy(status = "Saved", lastSavedUri = uri) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(status = "Processing failed: ${e.message}") }
            } finally {
                _ui.update { it.copy(processingJobs = it.processingJobs - 1) }
            }
        }
    }

    override fun onCleared() {
        powerManager?.removeThermalStatusListener(thermalListener)
        controller.shutdown()
        super.onCleared()
    }
}
