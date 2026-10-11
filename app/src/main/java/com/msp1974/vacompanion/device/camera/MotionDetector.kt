package com.msp1974.vacompanion.device.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.msp1974.vacompanion.device.MotionDetectionEngine
import com.msp1974.vacompanion.device.MotionDetectionEngine.Companion.MOTION_INTERVAL_TIMEOUT
import com.msp1974.vacompanion.device.MotionDetectionMode
import com.msp1974.vacompanion.device.MotionResult
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.ImageProcessing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * Drives the shared [CameraController]'s image analysis use case for motion/face detection.
 * Replaces the old `Camera` class, which bound its own CameraX session - that made it exclusive
 * with the on-device camera preview and the RTSP encoder. Mode/sensitivity changes no longer
 * need a camera restart: they just retarget the same analysis stream live.
 */
class MotionDetector(
    private val cameraController: CameraController,
    private val config: APPConfig,
) : EventListener, ImageAnalysis.Analyzer {

    companion object {
        private const val TARGET_ANALYSIS_FPS = 4
        private const val ANALYSIS_FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_ANALYSIS_FPS
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    private val motionEngine = MotionDetectionEngine()
    val motionFlow: SharedFlow<MotionResult> = motionEngine.motionFlow

    private var isRunning = false

    private var lastDetection: Long = 0
    private val settleDelay: Long = 5000
    private var settleDelayJob: Job? = null

    private var faceDetected = false
    private var motionDetected = false

    private var nextAnalysisTimestampNs = 0L

    init {
        config.eventBroadcaster.addListener(this)
        scope.launch {
            motionFlow.collect { result ->
                if (config.motionDetectionMode == "face") {
                    handleFaceDetection(result.hasMotion)
                } else {
                    handleStandardMotion(result.hasMotion)
                }
                config.eventBroadcaster.notifyEvent(Event("motionBoxes", "", result))
            }
        }
    }

    fun start() {
        if (config.motionDetectionMode == "none") return
        if (isRunning) return
        isRunning = true
        motionEngine.detectorMode = if (config.motionDetectionMode == "face") MotionDetectionMode.FACE_DETECTION else MotionDetectionMode.PIXEL_DIFF
        nextAnalysisTimestampNs = 0L
        startSettleDelay()

        cameraController.attachAnalyzer(this)
        Timber.i("MotionDetector: started (mode=${config.motionDetectionMode})")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        cameraController.detachAnalyzer()
        settleDelayJob?.cancel()
        motionDetected = false
        faceDetected = false
        lastDetection = 0
        motionEngine.reset()
        config.eventBroadcaster.notifyEvent(Event("motion", oldValue = false, newValue = false))
        Timber.i("MotionDetector: stopped")
    }

    private fun startSettleDelay() {
        // Settle motion detection to reduce false detections at start/mode-switch. Face
        // detection can settle faster than pixel diff.
        val delayMs = if (config.motionDetectionMode == "face") 1500L else settleDelay
        settleDelayJob?.cancel()
        settleDelayJob = scope.launch { delay(delayMs.milliseconds) }
    }

    override fun analyze(image: ImageProxy) {
        processImageProxy(image)
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
                buffer.get(lumaData)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.get(lumaData, row * width, width)
                }
            }

            val luma = ImageProcessing.decodeYUV420SPtoLuma(lumaData, width, height)
            val rotation = image.imageInfo.rotationDegrees

            scope.launch {
                motionEngine.processFrame(luma, width, height, rotation)
            }
        } catch (e: Exception) {
            Timber.e("MotionDetector: Error processing image: $e")
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
            config.eventBroadcaster.notifyEvent(Event("motion", oldValue = false, newValue = faceDetected))
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
            config.eventBroadcaster.notifyEvent(Event("motion", oldValue = false, newValue = motionDetected))
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "motionDetectionMode" -> {
                if (isRunning && event.newValue != event.oldValue) {
                    val mode = event.newValue as? String ?: "none"
                    if (mode == "none") {
                        stop()
                        return
                    }
                    Timber.i("MotionDetector: switching mode to $mode")
                    motionEngine.detectorMode = if (mode == "face") MotionDetectionMode.FACE_DETECTION else MotionDetectionMode.PIXEL_DIFF
                    motionEngine.reset()
                    startSettleDelay()
                }
            }
            "motionDetectionSensitivity" -> motionEngine.setSensitivity(event.newValue as Int)
        }
    }

    fun release() {
        config.eventBroadcaster.removeListener(this)
        if (isRunning) cameraController.detachAnalyzer()
        motionEngine.close()
        job.cancel()
    }
}
