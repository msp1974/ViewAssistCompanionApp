package com.msp1974.vacompanion.device.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import timber.log.Timber
import java.util.concurrent.Executor

/**
 * Alternate camera backend for devices where CameraX's own enumeration/validation rejects a
 * camera that opens fine via raw Camera2 (see [DeviceCameraQuirks]). Opens [cameraId] directly
 * and targets every currently-needed output - the analysis [ImageReader] plus any preview/
 * encoder [Surface]s - with a single capture session and one repeating request.
 *
 * Camera2 natively fans one capture request out to multiple target surfaces, so - unlike
 * [CameraController]'s normal CameraX path - no GL tee is needed here to share the feed. The
 * trade-off: every output gets the same raw sensor orientation with no per-output transform
 * (there is no [SurfaceTee] in this path), so on these devices the local preview and the RTSP
 * stream share whatever orientation/mirroring the sensor natively reports, rather than each
 * getting its own corrected transform the way other devices do.
 */
class Camera2DirectSource(
    private val context: Context,
    private val cameraId: String,
    private val analysisExecutor: Executor,
    private val analyzerProvider: () -> ImageAnalysis.Analyzer?,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var analysisReader: ImageReader? = null

    @Volatile private var running = false
    @Volatile private var starting = false

    /** Fixed camera-to-display rotation. Portal devices are a fixed wall/desk mount with no
     *  rotation sensor concerns, so - unlike CameraX's dynamic per-frame transform - this is
     *  computed once from SENSOR_ORIENTATION and reused for every frame. */
    private val sensorOrientation: Int by lazy {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) {
            Timber.e("Camera2DirectSource: failed to read sensor orientation: $e")
            0
        }
    }

    var onOpened: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    fun isRunning(): Boolean = running

    @SuppressLint("MissingPermission")
    fun start(outputs: List<Surface>, needsAnalysis: Boolean) {
        if (running || starting) return

        val targets = mutableListOf<Surface>().apply { addAll(outputs) }

        val handlerThread = HandlerThread("direct-camera-$cameraId").also { it.start() }
        val h = Handler(handlerThread.looper)

        if (needsAnalysis) {
            val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val analyzer = analyzerProvider()
                    if (analyzer == null) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    analysisExecutor.execute {
                        try {
                            analyzer.analyze(DirectImageProxy(image, sensorOrientation))
                        } catch (e: Exception) {
                            Timber.e("Camera2DirectSource: analyzer threw: $e")
                            image.close()
                        }
                    }
                }, h)
            }
            analysisReader = reader
            targets.add(reader.surface)
        }

        if (targets.isEmpty()) {
            handlerThread.quitSafely()
            return
        }

        starting = true
        thread = handlerThread
        handler = h

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession(camera, targets, h)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Timber.w("Camera2DirectSource: camera disconnected (likely claimed by another client)")
                    camera.close()
                    cameraDevice = null
                    running = false
                    starting = false
                    onError?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Timber.e("Camera2DirectSource: camera onError code=$error")
                    camera.close()
                    cameraDevice = null
                    running = false
                    starting = false
                    onError?.invoke()
                }
            }, h)
        } catch (e: Exception) {
            Timber.e("Camera2DirectSource: openCamera() threw: $e")
            starting = false
            onError?.invoke()
        }
    }

    private fun createSession(camera: CameraDevice, targets: List<Surface>, handler: Handler) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(request.build(), null, handler)
                        running = true
                        starting = false
                        Timber.i("Camera2DirectSource: capture session active (${targets.size} target(s))")
                        onOpened?.invoke()
                    } catch (e: Exception) {
                        Timber.e("Camera2DirectSource: setRepeatingRequest threw: $e")
                        starting = false
                        onError?.invoke()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Timber.e("Camera2DirectSource: capture session configure failed")
                    starting = false
                    onError?.invoke()
                }
            }, handler)
        } catch (e: Exception) {
            Timber.e("Camera2DirectSource: createCaptureSession threw: $e")
            starting = false
            onError?.invoke()
        }
    }

    fun stop() {
        if (!running && !starting) return
        running = false
        starting = false
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { analysisReader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        captureSession = null
        cameraDevice = null
        analysisReader = null
        thread = null
        handler = null
    }
}
