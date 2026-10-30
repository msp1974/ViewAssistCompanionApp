package com.msp1974.vacompanion.device.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.view.Surface
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
import java.util.concurrent.TimeUnit

/** [NO_VIDEO]: the camera opens but delivers no frames (see [Camera2DirectSource]). Only reported
 *  while [CameraController] is backing off before it tries again. */
enum class CameraAvailability { NO_CAMERA, NO_PERMISSION, IN_USE, NO_VIDEO, READY }

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

    // The camera's reported orientation - decides whether the image needs re-orienting. Re-read if
    // the device is rotated while the camera is running.
    private val orientation = CameraOrientation(context, directCameraId)

    // Whether the direct backend is currently feeding the outputs through the GL tee (to turn the
    // image upright) rather than straight from the camera.
    private var directViaTee = false

    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null

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
        surfaceTee.setRotationOffset(totalRotationOffset())
        orientation.onRotationChanged = { onOrientationChanged() }
        // CameraX's rotation is worked out from the sensor orientation and the display rotation it
        // was given, so the three only make sense read together.
        surfaceTee.onCameraXTransform = { rotation, mirroring ->
            Timber.i(
                "CameraController: CameraX transform rotation=$rotation mirroring=$mirroring " +
                    "(sensorOrientation=${orientation.sensorOrientation}, " +
                    "displayRotation=${orientation.surfaceRotation}, " +
                    "naturalLandscape=${orientation.isNaturalLandscape}, " +
                    "device=${android.os.Build.MANUFACTURER}/${android.os.Build.DEVICE})"
            )
        }
        orientation.start()
        config.eventBroadcaster.addListener(object : EventListener {
            override fun onEventTriggered(event: Event) {
                when (event.eventName) {
                    "hasCameraPermission" -> {
                        refreshAvailability()
                        scheduleRebind()
                    }
                    // SharedPreferences-backed, so the event carries no typed value - read it back.
                    "camera_rotation_offset" -> mainScope.launch {
                        surfaceTee.setRotationOffset(totalRotationOffset())
                        onOrientationChanged()
                    }
                }
            }
        })
    }

    /**
     * The device was turned, or the user changed the rotation setting: bring the camera image back
     * upright. CameraX is told the new display rotation and re-sends its transform to the tee; the
     * direct backend sets the rotation itself - unless whether it needs the tee at all has changed
     * (an upright image needs none), which takes a rebind.
     */
    private fun onOrientationChanged() {
        previewUseCase?.targetRotation = orientation.surfaceRotation
        analysisUseCase?.targetRotation = orientation.surfaceRotation

        if (directCameraId == null) return
        val needsTee = outputs.isNotEmpty() && directNeedsTransform()
        if (needsTee != directViaTee) {
            scheduleRebind()
        } else if (directViaTee) {
            surfaceTee.setDirectRotation(orientation.sensorOrientation)
        }
    }

    /** Whether frames from the direct backend have to be turned (or the user has asked for it);
     *  when not, they go to the outputs untouched. */
    private fun directNeedsTransform(): Boolean =
        orientation.sensorOrientation != 0 || totalRotationOffset() != 0

    /** The user's rotation setting plus the correction for this model of device, if it has one. */
    private fun totalRotationOffset(): Int =
        (DeviceCameraQuirks.rotationOffsetDegrees() + config.cameraRotationOffset) % 360

    /** Best-effort availability, recomputed from permission/hardware state plus the last known
     *  dynamic camera state (e.g. in-use-elsewhere) observed while bound. */
    fun currentAvailability(): CameraAvailability {
        val static = computeStaticAvailability()
        if (static != CameraAvailability.READY) return static
        return when {
            _availability.value == CameraAvailability.IN_USE -> CameraAvailability.IN_USE
            // Once the backoff is over the camera is worth another try, so report it as ready and
            // let the next consumer to attach start it.
            _availability.value == CameraAvailability.NO_VIDEO && System.currentTimeMillis() < rebindBackoffUntilMs ->
                CameraAvailability.NO_VIDEO
            else -> CameraAvailability.READY
        }
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
            val remaining = rebindBackoffUntilMs - now
            Timber.d("CameraController: skipping rebind, backing off for ${remaining}ms after $consecutiveBindFailures consecutive failure(s)")
            // Whatever is waiting on the camera has no other trigger to try again.
            rebindJob = mainScope.launch {
                delay(remaining)
                rebind()
            }
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

                analysisUseCase = if (needsAnalysis) {
                    buildAnalysisUseCase(applyFpsRange = !needsPreview, targetFps = targetFps)
                } else null
                previewUseCase = if (needsPreview) buildPreviewUseCase(targetFps) else null
                useCases.addAll(listOfNotNull(analysisUseCase, previewUseCase))

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
                Timber.i(
                    "CameraController: bound (analysis=$needsAnalysis, preview=$needsPreview), " +
                        "sensorOrientation=${orientation.sensorOrientation} " +
                        "displayRotation=${orientation.surfaceRotation}"
                )
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
    private suspend fun rebindDirect(id: String, needsAnalysis: Boolean) {
        val source = directSource ?: Camera2DirectSource(
            context = context,
            cameraId = id,
            analysisExecutor = analysisExecutor,
            analyzerProvider = { analyzer },
            rotationProvider = { orientation.sensorOrientation },
        ).also {
            it.onOpened = {
                _availability.value = CameraAvailability.READY
                Timber.i("CameraController: bound via direct Camera2 (id=$id)")
            }
            // Only now is the camera known to work, so only now is the failure backoff cleared.
            it.onFrames = { onBindSuccess() }
            it.onNoFrames = { mainScope.launch { onNoVideo() } }
            it.onError = {
                _availability.value = CameraAvailability.IN_USE
                onBindFailure()
            }
            directSource = it
        }

        // Opening or closing the camera can block for seconds (see Camera2DirectSource), so never
        // on the main thread.
        withContext(Dispatchers.IO) {
            source.stop()
            source.start(directTargets(), needsAnalysis)
        }
    }

    /** The direct camera is open but isn't delivering frames: let go of it so the device's own
     *  camera pipeline isn't held up, tell consumers, and retry once the backoff is over. */
    private suspend fun onNoVideo() {
        Timber.e("CameraController: camera opened but delivers no video, releasing it")
        withContext(Dispatchers.IO) { directSource?.stop() }
        directViaTee = false
        onBindFailure()
        _availability.value = CameraAvailability.NO_VIDEO
        scheduleRebind()
    }

    /**
     * The surfaces the direct camera writes to. Normally that is the outputs themselves. If the
     * image isn't upright for the device's orientation it is a single tee input instead, which
     * turns the image and draws it to every output.
     */
    private suspend fun directTargets(): List<Surface> {
        val viaTee = outputs.isNotEmpty() && directNeedsTransform()
        directViaTee = false
        if (viaTee) {
            try {
                val size = orientation.outputSize(streamResolution())
                surfaceTee.setDirectRotation(orientation.sensorOrientation)
                val input = withContext(Dispatchers.IO) {
                    surfaceTee.createDirectInput(size.width, size.height).get(TEE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                if (input != null) {
                    directViaTee = true
                    Timber.i(
                        "CameraController: re-orienting direct camera feed by " +
                            "${orientation.sensorOrientation}deg (+${totalRotationOffset()}deg offset), " +
                            "${size.width}x${size.height}"
                    )
                    return listOf(input)
                }
            } catch (e: Exception) {
                Timber.e("CameraController: couldn't set up re-orientation, using the raw camera feed: $e")
            }
        }
        // The outputs go straight to the camera, so the tee must give up its hold on them.
        withContext(Dispatchers.IO) {
            surfaceTee.releaseOutputSurfaces().get(TEE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return outputs.map { it.surface }
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

    /** The stream size: the configured height, with the width from the camera's own shape (16:9 if
     *  that can't be read), rounded to a multiple of 16 as encoders prefer. */
    fun streamResolution(): Size {
        val height = when (config.rtspResolution) {
            "480p" -> 480
            "1080p" -> 1080
            else -> 720
        }
        val aspect = orientation.sensorAspect ?: (16f / 9)
        val width = (Math.round(height * aspect / 16f) * 16).coerceAtLeast(16)
        return Size(width, height)
    }

    private suspend fun unbind() {
        if (directCameraId != null) {
            withContext(Dispatchers.IO) { directSource?.stop() }
            directViaTee = false
            _availability.value = computeStaticAvailability()
            return
        }
        withContext(Dispatchers.Main.immediate) {
            previewUseCase = null
            analysisUseCase = null
            cameraProvider?.unbindAll()
            if (lifecycleRegistry.currentState != Lifecycle.State.CREATED) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
            _availability.value = computeStaticAvailability()
        }
    }

    fun release() {
        orientation.stop()
        mainScope.launch { unbind() }
        surfaceTee.release()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TEE_TIMEOUT_SECONDS = 3L
        private const val REBIND_DEBOUNCE_MS = 300L
        private const val ANALYSIS_ONLY_FPS = 4
        private const val REBIND_FAILURE_BACKOFF_BASE_MS = 5_000L
        private const val REBIND_FAILURE_BACKOFF_MAX_MS = 60_000L
    }
}
