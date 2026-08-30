package com.msp1974.vacompanion.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.msp1974.vacompanion.device.MotionDetectionEngine.Companion.MOTION_INTERVAL_TIMEOUT
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.ImageProcessing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds

class Camera(val context: Context, val config: APPConfig) : EventListener {

    companion object {
        private const val TARGET_ANALYSIS_FPS = 4
        private const val ANALYSIS_FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_ANALYSIS_FPS
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    
    private val motionEngine = MotionDetectionEngine()
    val motionFlow: SharedFlow<MotionResult> = motionEngine.motionFlow

    private var isRunning: Boolean = false
    private var isStarting: Boolean = false
    
    private var lastDetection: Long = 0
    private val settleDelay: Long = 5000
    private var settleDelayJob: Job? = null

    private var faceDetected = false
    private var motionDetected = false

    private var nextAnalysisTimestampNs = 0L

    init {
        config.eventBroadcaster.addListener(this)
        // Setup motion detection flow subscriber
        scope.launch {
            motionFlow.collect { result ->
                if (config.motionDetectionMode == "face") {
                    handleFaceDetection(result.hasMotion)
                } else {
                    handleStandardMotion(result.hasMotion)
                }
            }
        }
    }

    fun startCamera() {
        if (isRunning || isStarting) return

        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner == null) {
            Timber.e("Camera: Context is not a LifecycleOwner. Cannot start CameraX.")
            return
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Timber.e("Camera: Permission not granted")
            return
        }

        isStarting = true
        Timber.i("Starting CameraX for motion detection")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                // Ensure we are running on the main thread for binding
                bindCameraUseCases(lifecycleOwner)
                isRunning = true
                isStarting = false
                
                // Settle motion detection to reduce false detections at start
                // Face detection can settle faster than pixel diff
                val delayMs = if (config.motionDetectionMode == "face") 1500L else settleDelay
                settleDelayJob?.cancel()
                settleDelayJob = scope.launch {
                    delay(delayMs.milliseconds)
                }
            } catch (e: Exception) {
                Timber.e("Camera: Error starting CameraX: $e")
                isRunning = false
                isStarting = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return

        // Use higher resolution for face detection (better reliability), lower for pixel diff motion (better performance)
        val targetResolution = Size(320, 240)

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(targetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()

        val analysisBuilder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(resolutionSelector)

        // Interop for optimizations (Antibanding)
        Camera2Interop.Extender(analysisBuilder).apply {
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        
        val analysis = analysisBuilder.build().also {
            it.setAnalyzer(cameraExecutor) { image ->
                processImageProxy(image)
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()

            // Check if we should actually be running
            if (config.motionDetectionMode == "none" || config.cameraStreamActive || config.rtspStreamActive) {
                Timber.w("Camera about to bind but motion detection disabled or stream active, skipping")
                isRunning = false
                isStarting = false
                return
            }

            val baseSessionConfig = SessionConfig.Builder(analysis).build()
            val selectedCameraInfo =
                cameraProvider.getCameraInfo(cameraSelector, baseSessionConfig)
            val supportedFrameRateRanges =
                selectedCameraInfo.getSupportedFrameRateRanges(baseSessionConfig)
            val targetFrameRateRange =
                selectFrameRateRange(supportedFrameRateRanges, TARGET_ANALYSIS_FPS)
            nextAnalysisTimestampNs = 0L

            val sessionConfig = SessionConfig.Builder(analysis).apply {
                if (targetFrameRateRange != null) {
                    setFrameRateRange(targetFrameRateRange)
                }
            }.build()

            Timber.i(
                "Camera: Supported FPS ranges: %s; requested: %s",
                supportedFrameRateRanges.sortedBy { it.upper }.joinToString(),
                targetFrameRateRange ?: "CameraX default",
            )

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                sessionConfig,
            )
            
            // Apply light optimizations if needed, but avoid forcing max exposure which ruins face detection
            val cameraControl = camera.cameraControl
            val cameraInfo = camera.cameraInfo
            val camera2CameraInfo = Camera2CameraInfo.from(cameraInfo)
            
            // Use a more balanced exposure boost if in low light and NOT in face mode
            if (config.motionDetectionMode != "face") {
                val range = camera2CameraInfo.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                if (range != null && range.upper > 0) {
                    // Just a small boost for pixel motion if it's dark, not full max
                    cameraControl.setExposureCompensationIndex(range.upper / 2)
                }
            }
            
            Timber.d("CameraX bound to lifecycle successfully")
        } catch (e: Exception) {
            Timber.e("Camera: Use case binding failed: $e")
        }
    }

    private fun selectFrameRateRange(
        supportedRanges: Set<Range<Int>>,
        desiredFps: Int,
    ): Range<Int>? {
        // Prefer the lowest fixed camera rate that can still meet the analysis target.
        val fixedRanges = supportedRanges.filter { it.lower == it.upper }
        return fixedRanges
            .filter { it.upper >= desiredFps }
            .minByOrNull { it.upper }
            ?: fixedRanges.maxByOrNull { it.upper }
    }

    private fun processImageProxy(image: ImageProxy) {
        val timestampNs = image.imageInfo.timestamp
        if (nextAnalysisTimestampNs == 0L) {
            nextAnalysisTimestampNs = timestampNs
        }
        if (timestampNs < nextAnalysisTimestampNs) {
            image.close()
            return
        }

        // Keep processing close to the target FPS when camera frames do not line up exactly.
        nextAnalysisTimestampNs += ANALYSIS_FRAME_INTERVAL_NS
        if (nextAnalysisTimestampNs <= timestampNs) {
            nextAnalysisTimestampNs = timestampNs + ANALYSIS_FRAME_INTERVAL_NS
        }

        var shouldCloseInFinally = true
        try {
            // Check if we are in settle delay
            if (settleDelayJob != null && settleDelayJob!!.isActive) {
                return
            }

            if (config.motionDetectionMode == "face") {
                motionEngine.detectorMode = MotionDetectionMode.FACE_DETECTION
                shouldCloseInFinally = false
                scope.launch {
                    try {
                        motionEngine.processImageProxy(image)
                    } finally {
                        image.close()
                    }
                }
                return
            } else if (config.motionDetectionMode == "motion") {
                motionEngine.detectorMode = MotionDetectionMode.PIXEL_DIFF
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            
            // Extract luma data while respecting stride
            val lumaData = ByteArray(width * height)
            if (rowStride == width) {
                // Contiguous buffer
                buffer.get(lumaData)
            } else {
                // Buffer with padding
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(lumaData, row * width, width)
                }
            }
            
            // Use our luma decoder (which now includes low-light boost)
            val luma = ImageProcessing.decodeYUV420SPtoLuma(lumaData, width, height)
            val rotation = image.imageInfo.rotationDegrees

            scope.launch {
                motionEngine.processFrame(luma, width, height, rotation)
            }
        } catch (e: Exception) {
            Timber.e("Camera: Error processing image: $e")
        } finally {
            if (shouldCloseInFinally) {
                image.close()
            }
        }
    }

    private fun handleFaceDetection(currentDetected: Boolean) {
        var sendBroadcast = false
        if (currentDetected) {
            lastDetection = System.currentTimeMillis()
            if (!faceDetected) {
                Timber.i("Face detected")
                faceDetected = true
                sendBroadcast = true
            }
        } else {
            if (faceDetected && (System.currentTimeMillis() - lastDetection) > 1000) {
                Timber.i("Face no longer detected")
                faceDetected = false
                sendBroadcast = true
            }
        }

        if (sendBroadcast) {
            config.eventBroadcaster.notifyEvent(
                Event(
                    "motion",
                    oldValue = false,
                    newValue = faceDetected
                )
            )
        }
    }

    private fun handleStandardMotion(currentDetected: Boolean) {
        var sendBroadcast = false
        if (currentDetected) {
            lastDetection = System.currentTimeMillis()
            if (!motionDetected) {
                Timber.i("Motion detected")
                motionDetected = true
                sendBroadcast = true
            }
        } else {
            if (motionDetected && (System.currentTimeMillis() - lastDetection) > MOTION_INTERVAL_TIMEOUT) {
                Timber.i("Motion no longer detected")
                motionDetected = false
                sendBroadcast = true
            }
        }


        if (sendBroadcast) {
            config.eventBroadcaster.notifyEvent(
                Event(
                    "motion",
                    oldValue = false,
                    newValue = motionDetected
                )
            )
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "motionDetectionMode" -> {
                if (isRunning && event.newValue != event.oldValue) {
                    scope.launch {
                        Timber.i("Camera: Restarting to apply new motion detection mode: ${event.newValue}")
                        stopCamera()
                        delay(500)
                        startCamera()
                    }
                }
            }
            "enableMotionDetection" -> {
                if (event.newValue == true && !isRunning) {
                    startCamera()
                } else if (event.newValue == false && isRunning) {
                    scope.launch { stopCamera() }
                }
            }
            "cameraStreamActive" -> {
                if (event.newValue == false && !isRunning && config.enableMotionDetection && !config.rtspStreamActive) {
                    // Stream closed, background motion detection can resume
                    Timber.i("Camera: Resuming background motion detection")
                    startCamera()
                } else if (event.newValue == true && isRunning) {
                    // Stream opened, stop background motion detection to free camera
                    Timber.i("Camera: Stopping background motion detection for stream")
                    scope.launch { stopCamera() }
                }
            }
            "rtspStreamActive" -> {
                if (event.newValue == false && !isRunning && config.enableMotionDetection && !config.cameraStreamActive) {
                    // RTSP viewer disconnected, background motion detection can resume
                    Timber.i("Camera: Resuming background motion detection after RTSP stream ended")
                    startCamera()
                } else if (event.newValue == true && isRunning) {
                    // RTSP viewer connected, stop background motion detection to free camera
                    Timber.i("Camera: Stopping background motion detection for RTSP stream")
                    scope.launch { stopCamera() }
                }
            }
            "motionDetectionSensitivity" -> motionEngine.setSensitivity(event.newValue as Int)
        }
    }

    suspend fun stopCamera() {
        Timber.i("Stopping CameraX motion detection")
        isRunning = false
        isStarting = false
        settleDelayJob?.cancel()

        // We must unbind on main thread for CameraX
        withContext(Dispatchers.Main) {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Timber.e("Camera: Error unbinding: $e")
            }
            cameraProvider = null
        }

        motionDetected = false
        faceDetected = false
        lastDetection = 0
        motionEngine.reset()
        
        config.eventBroadcaster.notifyEvent(Event("motion", oldValue = false, newValue = false))
    }

    fun release() {
        config.eventBroadcaster.removeListener(this)
        motionEngine.close()
        job.cancel()
    }
}
