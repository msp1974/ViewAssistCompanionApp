package com.msp1974.vacompanion.device.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.msp1974.vacompanion.device.info.DeviceInfo
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.Executors

enum class CameraAvailability { NO_CAMERA, NO_PERMISSION, IN_USE, READY }

/**
 * Single owner of the device's front camera. CameraX only allows one bound set of use cases at a
 * time, so every consumer - motion/face detection ([MotionDetector]), the on-device preview
 * (`CameraStreamLayout`), the RTSP encoder (`H264Encoder`) - attaches here instead of binding its
 * own [ProcessCameraProvider]. A single debounced rebind brings up exactly the use cases
 * currently needed, so these can all run concurrently.
 */
class CameraController(
    private val context: Context,
    private val config: APPConfig,
    private val deviceInfo: DeviceInfo,
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var rebindJob: Job? = null

    // On devices where CameraX's own enumeration/validation rejects a camera that otherwise
    // works fine (see DeviceCameraQuirks), route through raw Camera2 instead. Null for every
    // other device, which keeps using the normal CameraX path below unchanged.
    private val directCameraId: String? = DeviceCameraQuirks.directCameraId()
    private var directSource: Camera2DirectSource? = null

    // Backs off retrying ProcessCameraProvider/bindToLifecycle after a failure, instead of
    // re-attempting (and eating CameraX's own ~5s internal retry loop) on every single consumer
    // attach - e.g. some devices never have a usable camera (OS-level permission the app can
    // never hold), and without this a steady stream of RTSP DESCRIBEs or motion-detection
    // restarts would retry that failure indefinitely.
    private var consecutiveBindFailures = 0
    private var rebindBackoffUntilMs = 0L

    /** Fans the single bound [Preview] out to any number of destination surfaces. */
    val surfaceTee = SurfaceTee()

    @Volatile private var analyzer: ImageAnalysis.Analyzer? = null
    private val outputs = mutableListOf<TeeOutput>()

    // Target fps for the shared sensor stream when a Preview is bound. An attached encoder wants
    // its configured streaming fps; with no encoder we fall back to a low analysis-only rate.
    @Volatile private var encoderFps: Int? = null

    private val _availability = MutableStateFlow(computeStaticAvailability())
    val availability: StateFlow<CameraAvailability> = _availability.asStateFlow()

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        config.eventBroadcaster.addListener(object : EventListener {
            override fun onEventTriggered(event: Event) {
                if (event.eventName == "hasCameraPermission") {
                    refreshAvailability()
                    scheduleRebind()
                }
            }
        })
    }

    /** Best-effort availability, recomputed from permission/hardware state plus the last known
     *  dynamic camera state (e.g. in-use-elsewhere) observed while bound. */
    fun currentAvailability(): CameraAvailability {
        val static = computeStaticAvailability()
        if (static != CameraAvailability.READY) return static
        return if (_availability.value == CameraAvailability.IN_USE) CameraAvailability.IN_USE else CameraAvailability.READY
    }

    /** Re-checks permission/hardware availability. Call after a permission grant/revoke, since
     *  there is no change notification for [APPConfig.hasCameraPermission] to listen for. */
    fun refreshAvailability() {
        _availability.value = currentAvailability()
    }

    private fun computeStaticAvailability(): CameraAvailability {
        if (!deviceInfo.hardware.hasFrontCamera) return CameraAvailability.NO_CAMERA
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return CameraAvailability.NO_PERMISSION
        }
        return CameraAvailability.READY
    }

    fun attachAnalyzer(analyzer: ImageAnalysis.Analyzer) {
        this.analyzer = analyzer
        scheduleRebind()
    }

    fun detachAnalyzer() {
        this.analyzer = null
        scheduleRebind()
    }

    fun setEncoderFps(fps: Int) {
        encoderFps = fps
        scheduleRebind()
    }

    @Synchronized
    fun addSurfaceOutput(output: TeeOutput) {
        outputs.removeAll { it.id == output.id }
        outputs.add(output)
        surfaceTee.addOutput(output)
        scheduleRebind()
    }

    @Synchronized
    fun removeSurfaceOutput(id: String) {
        outputs.removeAll { it.id == id }
        surfaceTee.removeOutput(id)
        scheduleRebind()
    }

    private fun scheduleRebind() {
        rebindJob?.cancel()
        rebindJob = mainScope.launch {
            delay(REBIND_DEBOUNCE_MS)
            rebind()
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private suspend fun rebind() {
        val static = computeStaticAvailability()
        if (static != CameraAvailability.READY) {
            Timber.w("CameraController: camera not available ($static), not binding")
            unbind()
            return
        }

        val needsAnalysis = analyzer != null
        val needsPreview = outputs.isNotEmpty()

        if (!needsAnalysis && !needsPreview) {
            unbind()
            return
        }

        val now = System.currentTimeMillis()
        if (consecutiveBindFailures > 0 && now < rebindBackoffUntilMs) {
            Timber.d("CameraController: skipping rebind, backing off for ${rebindBackoffUntilMs - now}ms after $consecutiveBindFailures consecutive failure(s)")
            return
        }

        if (directCameraId != null) {
            rebindDirect(directCameraId, needsAnalysis)
            return
        }

        val provider = cameraProvider ?: try {
            withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        } catch (e: Exception) {
            Timber.e("CameraController: failed to get ProcessCameraProvider: $e")
            _availability.value = CameraAvailability.IN_USE
            onBindFailure()
            return
        }.also { cameraProvider = it }

        withContext(Dispatchers.Main.immediate) {
            try {
                provider.unbindAll()
                if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
                    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                }

                val useCases = mutableListOf<UseCase>()
                val targetFps = encoderFps ?: ANALYSIS_ONLY_FPS

                if (needsAnalysis) {
                    useCases.add(buildAnalysisUseCase(applyFpsRange = !needsPreview, targetFps = targetFps))
                }
                if (needsPreview) {
                    useCases.add(buildPreviewUseCase(targetFps))
                }

                val camera = provider.bindToLifecycle(
                    this@CameraController,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    *useCases.toTypedArray()
                )

                if (!needsPreview) {
                    // Boost exposure only for the low-res motion/face analysis path - a bound
                    // preview/encoder output wants a natural exposure.
                    val range = camera.cameraInfo.exposureState.exposureCompensationRange
                    if (config.motionDetectionMode != "face" && range.upper > 0) {
                        camera.cameraControl.setExposureCompensationIndex(range.upper / 2)
                    }
                }

                observeCameraState(camera)
                _availability.value = CameraAvailability.READY
                onBindSuccess()
                Timber.i("CameraController: bound (analysis=$needsAnalysis, preview=$needsPreview)")
            } catch (e: Exception) {
                Timber.e("CameraController: bind failed: $e")
                _availability.value = CameraAvailability.IN_USE
                onBindFailure()
            }
        }
    }

    private fun onBindSuccess() {
        consecutiveBindFailures = 0
        rebindBackoffUntilMs = 0L
    }

    private fun onBindFailure() {
        consecutiveBindFailures++
        val backoff = (REBIND_FAILURE_BACKOFF_BASE_MS shl (consecutiveBindFailures - 1).coerceAtMost(5))
            .coerceAtMost(REBIND_FAILURE_BACKOFF_MAX_MS)
        rebindBackoffUntilMs = System.currentTimeMillis() + backoff
        Timber.w("CameraController: bind failure #$consecutiveBindFailures, backing off for ${backoff}ms before the next attempt")
    }

    /** Quirks-only backend for devices where CameraX itself can't open the camera - see
     *  [DeviceCameraQuirks]. Outputs/analyzer only ever change via attach/detach, which already
     *  triggers a debounced rebind, so a full stop/start here is simplest rather than trying to
     *  add/remove live targets on an existing capture session. */
    private fun rebindDirect(id: String, needsAnalysis: Boolean) {
        val source = directSource ?: Camera2DirectSource(
            context = context,
            cameraId = id,
            analysisExecutor = analysisExecutor,
            analyzerProvider = { analyzer },
        ).also {
            it.onOpened = {
                _availability.value = CameraAvailability.READY
                onBindSuccess()
                Timber.i("CameraController: bound via direct Camera2 (id=$id)")
            }
            it.onError = {
                _availability.value = CameraAvailability.IN_USE
                onBindFailure()
            }
            directSource = it
        }

        source.stop()
        source.start(outputs.map { it.surface }, needsAnalysis)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildAnalysisUseCase(applyFpsRange: Boolean, targetFps: Int): ImageAnalysis {
        val analysisResolution = Size(320, 240)
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(analysisResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)

        Camera2Interop.Extender(analysisBuilder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            if (applyFpsRange) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps))
            }
        }

        return analysisBuilder.build().also { useCase ->
            useCase.setAnalyzer(analysisExecutor) { image ->
                val current = analyzer
                if (current != null) current.analyze(image) else image.close()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildPreviewUseCase(targetFps: Int): Preview {
        val streamSize = streamResolution()
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(streamSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val previewBuilder = Preview.Builder().setResolutionSelector(resolutionSelector)
        Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(targetFps, targetFps)
        )
        return previewBuilder.build().also { it.setSurfaceProvider(surfaceTee) }
    }

    private fun observeCameraState(camera: Camera) {
        camera.cameraInfo.cameraState.observe(this) { state ->
            val error = state.error
            _availability.value = if (error != null && (
                    error.code == CameraState.ERROR_CAMERA_IN_USE ||
                        error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE ||
                        error.code == CameraState.ERROR_CAMERA_DISABLED ||
                        error.code == CameraState.ERROR_OTHER_RECOVERABLE_ERROR
                    )) {
                CameraAvailability.IN_USE
            } else {
                CameraAvailability.READY
            }
        }
    }

    private fun streamResolution(): Size {
        return when (config.rtspResolution) {
            "480p" -> Size(854, 480)
            "1080p" -> Size(1920, 1080)
            else -> Size(1280, 720)
        }
    }

    private suspend fun unbind() {
        if (directCameraId != null) {
            directSource?.stop()
            _availability.value = computeStaticAvailability()
            return
        }
        withContext(Dispatchers.Main.immediate) {
            cameraProvider?.unbindAll()
            if (lifecycleRegistry.currentState != Lifecycle.State.CREATED) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
            _availability.value = computeStaticAvailability()
        }
    }

    fun release() {
        mainScope.launch { unbind() }
        surfaceTee.release()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val REBIND_DEBOUNCE_MS = 300L
        private const val ANALYSIS_ONLY_FPS = 4
        private const val REBIND_FAILURE_BACKOFF_BASE_MS = 5_000L
        private const val REBIND_FAILURE_BACKOFF_MAX_MS = 60_000L
    }
}
