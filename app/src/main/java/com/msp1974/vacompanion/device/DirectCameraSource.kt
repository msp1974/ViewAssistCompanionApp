package com.msp1974.vacompanion.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.ImageProcessing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Camera source that bypasses CameraX and opens the camera via Camera2 with
 * a hardcoded id. Used on devices where CameraX 1.6+'s validator rejects the
 * available camera due to non-standard metadata (e.g. Portal+ reports
 * LENS_FACING=BACK on a physically forward-facing camera). Selected when
 * `CameraDirectAPI in DeviceFunctionQuirks.quirks`.
 *
 * Feeds frames into the same MotionDetectionEngine the CameraX path uses, so
 * downstream consumers (motionFlow subscribers, Camera.kt handlers) see
 * identical output.
 */
class DirectCameraSource(
    val context: Context,
    val config: APPConfig,
    private val motionEngine: MotionDetectionEngine,
    private val cameraId: String = "0",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isRunning = false
    @Volatile private var isStarting = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var lastFrameTime = 0L
    private val settleDelayMs: Long = 5000
    private var settleUntilMs = 0L

    /**
     * Optional preview output. When non-null, the capture session also drives
     * this Surface (e.g. a `SurfaceView`'s holder) so the camera stream view
     * can show what the camera sees. Set before calling [start]; changes while
     * running require a stop/start cycle to take effect.
     */
    var previewSurface: Surface? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning || isStarting) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Timber.e("DirectCameraSource: CAMERA permission not granted")
            return
        }
        isStarting = true
        Timber.i("Starting DirectCameraSource (Camera2 direct, id=$cameraId)")

        thread = HandlerThread("direct-camera").also { it.start() }
        handler = Handler(thread!!.looper)

        // Settle delay — discard the first frames so motion-detection background model can stabilize.
        val delayMs = if (config.motionDetectionMode == MotionDetectionMode.FACE) 1500L else settleDelayMs
        settleUntilMs = System.currentTimeMillis() + delayMs

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cm.openCamera(cameraId, deviceCallback, handler)
        } catch (e: Throwable) {
            Timber.e(e, "DirectCameraSource: openCamera threw")
            isStarting = false
            cleanup()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Timber.i("DirectCameraSource: camera opened")
            cameraDevice = camera
            createCaptureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Timber.w("DirectCameraSource: onDisconnected (something else claimed the camera)")
            camera.close()
            cameraDevice = null
            isRunning = false
            isStarting = false
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Timber.e("DirectCameraSource: onError code=$error")
            camera.close()
            cameraDevice = null
            isRunning = false
            isStarting = false
        }
    }

    private fun createCaptureSession(camera: CameraDevice) {
        imageReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleFrame(image)
            }, handler)
        }
        val analysisSurface = imageReader!!.surface
        val outputs = listOfNotNull(analysisSurface, previewSurface)
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(analysisSurface)
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(request.build(), null, handler)
                            isRunning = true
                            isStarting = false
                            Timber.i("DirectCameraSource: capture session active")
                        } catch (e: Throwable) {
                            Timber.e(e, "DirectCameraSource: setRepeatingRequest failed")
                            isStarting = false
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Timber.e("DirectCameraSource: capture session configure FAILED")
                        isStarting = false
                    }
                },
                handler,
            )
        } catch (e: Throwable) {
            Timber.e(e, "DirectCameraSource: createCaptureSession threw")
            isStarting = false
        }
    }

    private fun handleFrame(image: Image) {
        val mode = config.motionDetectionMode
        var deferredClose = false
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameTime < 250) return
            lastFrameTime = now
            if (now < settleUntilMs) return

            if (mode == MotionDetectionMode.FACE) {
                motionEngine.detectorMode = DetectorMode.FACE_DETECTION
                deferredClose = true
                scope.launch {
                    try {
                        motionEngine.processMediaImage(image, 0, image.width, image.height)
                    } catch (e: Throwable) {
                        Timber.e(e, "DirectCameraSource: processMediaImage failed")
                    } finally {
                        image.close()
                    }
                }
                return
            }
            if (mode == MotionDetectionMode.MOTION) {
                motionEngine.detectorMode = DetectorMode.PIXEL_DIFF
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

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

            scope.launch {
                motionEngine.processFrame(luma, width, height, 0)
            }
        } catch (e: Throwable) {
            Timber.e(e, "DirectCameraSource: frame processing error")
        } finally {
            if (!deferredClose) image.close()
        }
    }

    fun stop() {
        if (!isRunning && !isStarting) return
        Timber.i("Stopping DirectCameraSource")
        isRunning = false
        isStarting = false
        cleanup()
        motionEngine.reset()
    }

    private fun cleanup() {
        try { captureSession?.close() } catch (_: Throwable) {}
        try { cameraDevice?.close() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        thread?.quitSafely()
        captureSession = null
        cameraDevice = null
        imageReader = null
        thread = null
        handler = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}
