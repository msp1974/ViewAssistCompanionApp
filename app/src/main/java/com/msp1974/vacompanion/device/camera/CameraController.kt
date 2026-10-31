package com.msp1974.vacompanion.device.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.view.Surface
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
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
 * Single owner of the device's front camera. Only one capture session can hold the camera at a
 * time, so every consumer - motion/face detection ([MotionDetector]), the on-device preview
 * (`CameraStreamLayout`), the RTSP encoder (`H264Encoder`) - attaches here instead of opening its
 * own. A single debounced rebind brings up exactly the outputs currently needed, so these can all
 * run concurrently. The camera is driven through raw Camera2 by [Camera2DirectSource], and the
 * picture is drawn to the outputs by [SurfaceTee], which turns, mirrors and fits it.
 */
class CameraController(
    private val context: Context,
    private val config: APPConfig,
    private val deviceInfo: DeviceInfo,
) {

    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var rebindJob: Job? = null
    private var source: Camera2DirectSource? = null

    // The camera's reported orientation - decides whether the image needs re-orienting. Re-read if
    // the device is rotated while the camera is running.
    private val orientation = CameraOrientation(context, null)

    // Whether the camera is currently feeding the outputs through the GL tee (to turn, mirror and
    // fit the image) rather than straight from the camera.
    private var viaTee = false

    // Backs off retrying to open the camera after a failure, instead of re-attempting on every
    // single consumer attach - e.g. some devices never have a usable camera (OS-level permission
    // the app can never hold), and without this a steady stream of RTSP DESCRIBEs or
    // motion-detection restarts would retry that failure indefinitely.
    private var consecutiveBindFailures = 0
    private var rebindBackoffUntilMs = 0L

    /** Draws the camera's frames to any number of destination surfaces. */
    val surfaceTee = SurfaceTee()

    @Volatile private var analyzer: ImageAnalysis.Analyzer? = null
    private val outputs = mutableListOf<TeeOutput>()

    // Target fps for the shared sensor stream when there are outputs. An attached encoder wants
    // its configured streaming fps; with no encoder we fall back to a modest preview rate.
    @Volatile private var encoderFps: Int? = null

    private val _availability = MutableStateFlow(computeStaticAvailability())
    val availability: StateFlow<CameraAvailability> = _availability.asStateFlow()

    init {
        surfaceTee.bufferIsTransposed = orientation.isFrameSqueezed
        surfaceTee.userMirror = config.cameraMirror
        surfaceTee.setRotationOffset(config.cameraRotationOffset)
        orientation.onRotationChanged = { onOrientationChanged() }
        orientation.start()
        Timber.i(
            "CameraController: camera=${orientation.cameraId} sensorOrientation=${orientation.sensorOrientation} " +
                "displayRotation=${orientation.surfaceRotation} naturalLandscape=${orientation.isNaturalLandscape} " +
                "device=${android.os.Build.MANUFACTURER}/${android.os.Build.DEVICE}"
        )
        config.eventBroadcaster.addListener(object : EventListener {
            override fun onEventTriggered(event: Event) {
                when (event.eventName) {
                    "hasCameraPermission" -> {
                        refreshAvailability()
                        scheduleRebind()
                    }
                    // SharedPreferences-backed, so the event carries no typed value - read it back.
                    "camera_rotation_offset" -> mainScope.launch {
                        surfaceTee.setRotationOffset(config.cameraRotationOffset)
                        onOrientationChanged()
                    }
                    "camera_mirror", "cameraMirror" -> surfaceTee.userMirror = config.cameraMirror
                }
            }
        })
    }

    /**
     * The device was turned, or the user changed the rotation setting: bring the camera image back
     * upright. The tee is given the new rotation; no rebind is needed unless whether there is
     * anything to draw to has changed.
     */
    private fun onOrientationChanged() {
        val needsTee = outputs.isNotEmpty()
        if (needsTee != viaTee) {
            scheduleRebind()
        } else if (viaTee) {
            surfaceTee.setDirectRotation(orientation.imageRotation)
        }
    }

    /** The angle the tee draws the camera image at for the device as it is now, with the user's
     *  rotation setting. */
    private fun currentUprightRotation(): Int = (orientation.imageRotation + config.cameraRotationOffset) % 360

    /**
     * The rotation to give the motion and face detectors: degrees clockwise to turn the frame
     * upright, as the camera reports it (see [CameraOrientation.reportedRotation]) plus the user's
     * setting. It isn't the tee's angle, which is worked out differently. Where the boxes they find
     * are drawn is worked out by [SurfaceTee.imageToOutput].
     */
    private fun analysisRotation(reported: Int): Int = (reported + config.cameraRotationOffset) % 360

    /** Whether the camera's frames hold a picture of the transposed shape (see
     *  [CameraOrientation.isFrameSqueezed]). */
    val frameIsSqueezed: Boolean get() = orientation.isFrameSqueezed

    /** The shape of the preview's image (see [SurfaceTee.imageAspect]). */
    fun previewImageAspect(): Float? = surfaceTee.imageAspect()

    /** Where a rectangle in the camera frame appears in the preview's image (see
     *  [SurfaceTee.imageToOutput]). */
    fun previewRect(rect: RectF): RectF? = surfaceTee.imageToOutput(rect, outputMirror = true)

    /** Best-effort availability, recomputed from permission/hardware state plus the last known
     *  dynamic camera state (e.g. in-use-elsewhere) observed while open. */
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

    private suspend fun rebind() {
        val static = computeStaticAvailability()
        if (static != CameraAvailability.READY) {
            Timber.w("CameraController: camera not available ($static), not opening")
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

        val id = orientation.cameraId
        if (id == null) {
            Timber.e("CameraController: no front camera found")
            _availability.value = CameraAvailability.NO_CAMERA
            return
        }
        rebindSource(id, needsAnalysis, needsPreview)
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

    /** Outputs/analyzer only ever change via attach/detach, which already triggers a debounced
     *  rebind, so a full stop/start here is simplest rather than trying to add/remove live targets
     *  on an existing capture session. */
    private suspend fun rebindSource(id: String, needsAnalysis: Boolean, needsPreview: Boolean) {
        val camera = source ?: Camera2DirectSource(
            context = context,
            cameraId = id,
            analysisExecutor = analysisExecutor,
            analyzerProvider = { analyzer },
            rotationProvider = { analysisRotation(orientation.reportedRotation) },
        ).also {
            it.onOpened = {
                _availability.value = CameraAvailability.READY
                Timber.i("CameraController: camera open (id=$id)")
            }
            // Only now is the camera known to work, so only now is the failure backoff cleared.
            it.onFrames = { onBindSuccess() }
            it.onNoFrames = { mainScope.launch { onNoVideo() } }
            it.onError = {
                _availability.value = CameraAvailability.IN_USE
                onBindFailure()
            }
            source = it
        }

        // Boost exposure only for the low-res motion/face analysis path - a preview/encoder
        // output wants a natural exposure.
        val boostExposure = !needsPreview && config.motionDetectionMode != "face"
        val fps = if (needsPreview) encoderFps ?: PREVIEW_FPS else ANALYSIS_ONLY_FPS

        // Opening or closing the camera can block for seconds (see Camera2DirectSource), so never
        // on the main thread.
        withContext(Dispatchers.IO) {
            camera.stop()
            camera.start(targets(), needsAnalysis, fps, boostExposure)
        }
    }

    /** The camera is open but isn't delivering frames: let go of it so the device's own
     *  camera pipeline isn't held up, tell consumers, and retry once the backoff is over. */
    private suspend fun onNoVideo() {
        Timber.e("CameraController: camera opened but delivers no video, releasing it")
        withContext(Dispatchers.IO) { source?.stop() }
        viaTee = false
        onBindFailure()
        _availability.value = CameraAvailability.NO_VIDEO
        scheduleRebind()
    }

    /**
     * The surfaces the camera writes to: a single tee input, which draws the image to every output
     * turned upright, mirrored and fitted. If the tee can't be set up the outputs themselves are
     * used, which get the camera's frames exactly as the sensor produced them.
     */
    private suspend fun targets(): List<Surface> {
        viaTee = false
        if (outputs.isNotEmpty()) {
            try {
                // The camera's sizes are in its own orientation, landscape, whichever way up the
                // image ends up.
                val stream = streamResolution()
                val size = orientation.outputSize(Size(maxOf(stream.width, stream.height), minOf(stream.width, stream.height)))
                surfaceTee.setDirectRotation(orientation.imageRotation)
                val input = withContext(Dispatchers.IO) {
                    surfaceTee.createDirectInput(size.width, size.height).get(TEE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                if (input != null) {
                    viaTee = true
                    Timber.i(
                        "CameraController: drawing the camera feed at ${currentUprightRotation()}deg " +
                            "(frame rotation ${orientation.imageRotation}deg, +${config.cameraRotationOffset}deg setting, " +
                            "squeezed=${orientation.isFrameSqueezed}), ${size.width}x${size.height}"
                    )
                    return listOf(input)
                }
            } catch (e: Exception) {
                Timber.e("CameraController: couldn't set up the tee, using the raw camera feed: $e")
            }
        }
        // The outputs go straight to the camera, so the tee must give up its hold on them.
        withContext(Dispatchers.IO) {
            surfaceTee.releaseOutputSurfaces().get(TEE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        return outputs.map { it.surface }
    }

    /** The stream size: the configured resolution is the short side, and the long side comes from
     *  the camera's own shape (16:9 if that can't be read), rounded to a multiple of 16 as encoders
     *  prefer. The stream is portrait when the upright image is, so it isn't cropped to a
     *  landscape frame; if the device turns later, the tee fits the image in the fixed size. */
    fun streamResolution(): Size {
        val short = when (config.rtspResolution) {
            "480p" -> 480
            "1080p" -> 1080
            else -> 720
        }
        val aspect = orientation.sensorAspect ?: (16f / 9)
        val long = (Math.round(short * aspect / 16f) * 16).coerceAtLeast(16)
        // A squeezed frame holds the transposed shape, so it is portrait when not turned.
        val portrait = (currentUprightRotation() % 180 != 0) != orientation.isFrameSqueezed
        return if (portrait) Size(short, long) else Size(long, short)
    }

    private suspend fun unbind() {
        withContext(Dispatchers.IO) { source?.stop() }
        viaTee = false
        _availability.value = computeStaticAvailability()
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
        private const val PREVIEW_FPS = 15
        private const val REBIND_FAILURE_BACKOFF_BASE_MS = 5_000L
        private const val REBIND_FAILURE_BACKOFF_MAX_MS = 60_000L
    }
}
