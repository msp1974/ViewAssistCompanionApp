package com.msp1974.vacompanion.service

import android.Manifest
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import com.jjoe64.motiondetection.motiondetection.AggregateLumaMotionDetection
import com.jjoe64.motiondetection.motiondetection.ImageProcessing
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.AuthUtils.Companion.log
import com.msp1974.vacompanion.utils.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class CameraBackgroundTask(val context: Context) {

    private var config: APPConfig = APPConfig.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Default)

    private var checkInterval: Long = 500
    private var lastCheck: Long = 0
    private val faceDetectorOptions = FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build()
    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)
    private var isFaceCurrentlyDetected = false

    // Camera2-related stuff
    private var cameraManager: CameraManager? = null
    private var previewSize: Size? = null
    private var cameraDevice: CameraDevice? = null
    private var captureRequest: CaptureRequest? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private val settleDelay: Long = 5000
    private var settleDelayJob: Job? = null
    private var lastDetection: Long = 0

    private var isRunning: Boolean = false


    init {
        setSensitivity(config.motionDetectionSensitivity)
    }

    companion object {
        const val MOTION_INTERVAL = 10000
        const val MAX_LENIENCY = 50
    }

    fun setSensitivity(sensitivity: Int) {
    }

    fun startCamera() {
        if (!isRunning) {
            // Cant start camera if screen off so turn on and wait
            scope.launch {
                initCam()
                isRunning = true
            }
        }
    }

    fun stopCamera() {
        if (isRunning) {
            try {
                captureSession?.close()
                captureSession = null

                cameraDevice?.close()
                cameraDevice = null

                imageReader?.close()
                imageReader = null

            } catch (e: Exception) {
                Timber.e("Error closing camera: $e")
            } finally {
                isRunning = false
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {}

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {}
    }


    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader?.acquireLatestImage()
        if (image != null) {
            val inputImage = InputImage.fromMediaImage(image, 0)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (settleDelayJob != null && !settleDelayJob?.isActive!!) {
                        
                        if (faces.isNotEmpty()) {
                            // A face just entered the frame
                            if (!isFaceCurrentlyDetected) {
                                isFaceCurrentlyDetected = true
                                log.d("Face detected as motion")
                                config.eventBroadcaster.notifyEvent(Event("faceStateChanged", "", true))
                            }
                        } else {
                            // The face just left the frame
                            if (isFaceCurrentlyDetected) {
                                isFaceCurrentlyDetected = false
                                log.d("Face no longer detected")
                                config.eventBroadcaster.notifyEvent(Event("faceStateChanged", "", false))
                            }
                        }
                        
                    }
                }
                .addOnCompleteListener {
                    image.close()
                }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(currentCameraDevice: CameraDevice) {
            cameraDevice = currentCameraDevice
            createCaptureSession()
        }

        override fun onDisconnected(currentCameraDevice: CameraDevice) {
            currentCameraDevice.close()
            cameraDevice = null
        }

        override fun onError(currentCameraDevice: CameraDevice, error: Int) {
            currentCameraDevice.close()
            cameraDevice = null
        }
    }

    private fun initCam() {

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }


        cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager

        var camId: String? = null

        for (id in cameraManager!!.cameraIdList) {
            val characteristics = cameraManager!!.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                camId = id.toString()
                break
            }
        }

        log.i("Camera ID: $camId")

        previewSize = chooseSupportedSize(camId!!, 320, 240)
        Timber.d("Camera preview size is $previewSize")


        try {
            cameraManager!!.openCamera(camId, stateCallback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            log.w("Error accessing camera: $e")
        }

        // Settle motion detection to reduce false detections at start
        try {
            log.d("Motion detection running....")
            if (settleDelayJob != null && settleDelayJob!!.isActive) {
                settleDelayJob?.cancel()
            }
            settleDelayJob = scope.launch {
                delay(settleDelay)
            }
        } catch (e: Exception) {
            log.e("Error on settle job: $e")
        }

    }

    private fun chooseSupportedSize(camId: String, textureViewWidth: Int, textureViewHeight: Int): Size {

        val manager = context.getSystemService(CAMERA_SERVICE) as CameraManager

        // Get all supported sizes for TextureView
        val characteristics = manager.getCameraCharacteristics(camId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedSizes = map?.getOutputSizes(SurfaceTexture::class.java)

        // We want to find something near the size of our TextureView
        val texViewArea = textureViewWidth * textureViewHeight
        val texViewAspect = textureViewWidth.toFloat()/textureViewHeight.toFloat()

        // Check for match
        val match = supportedSizes?.firstOrNull {
            it.width == textureViewWidth && it.height == textureViewHeight
        }

        if (match != null) {
            return match
        }

        // Find closest)
        val nearestToFurthestSz = supportedSizes?.sortedWith(compareBy(
            // First find something with similar aspect
            {
                val aspect = if (it.width < it.height) it.width.toFloat() / it.height.toFloat()
                else it.height.toFloat()/it.width.toFloat()
                (aspect - texViewAspect).absoluteValue
            },
            // Also try to get similar resolution
            {
                (texViewArea - it.width * it.height).absoluteValue
            }
        ))
        if (nearestToFurthestSz != null) {
            if (nearestToFurthestSz.isNotEmpty())
                return nearestToFurthestSz[0]
        }

        return Size(320, 240)
    }

    private fun createCaptureSession() {
        try {
            // Prepare surfaces we want to use in capture session
            val targetSurfaces = ArrayList<Surface>()

            // Prepare CaptureRequest that can be used with CameraCaptureSession
            val requestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {

                // Configure target surface for background processing (ImageReader)
                imageReader = ImageReader.newInstance(
                    previewSize!!.width, previewSize!!.height,
                    ImageFormat.YUV_420_888, 2
                )
                imageReader!!.setOnImageAvailableListener(imageListener, null)

                targetSurfaces.add(imageReader!!.surface)
                addTarget(imageReader!!.surface)

                val fps = Range(5, 5)

                // Set some additional parameters for the request
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fps)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // Prepare CameraCaptureSession
            cameraDevice!!.createCaptureSession(targetSurfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        // The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }

                        captureSession = cameraCaptureSession
                        try {
                            // Now we can start capturing
                            captureRequest = requestBuilder.build()
                            captureSession!!.setRepeatingRequest(captureRequest!!, captureCallback, null)


                        } catch (e: CameraAccessException) {
                            log.e("createCaptureSession - $e")
                        }

                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        log.e("createCaptureSession()")
                    }
                }, null
            )
        } catch (e: CameraAccessException) {
            log.e("createCaptureSession - $e")
        }
    }
}
