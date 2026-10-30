package com.msp1974.vacompanion.device.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Alternate camera backend for devices where CameraX's own enumeration/validation rejects a
 * camera that opens fine via raw Camera2 (see [DeviceCameraQuirks]). Opens [cameraId] directly
 * and targets every currently-needed output - the analysis [ImageReader] plus any preview/
 * encoder [Surface]s - with a single capture session and one repeating request.
 *
 * Camera2 natively fans one capture request out to multiple target surfaces, so the surfaces it is
 * given can be the outputs themselves. Those get the raw sensor orientation, which is only right
 * when the camera's image is already upright; [CameraController] instead hands this a single
 * [SurfaceTee] input when the image needs turning, and the tee draws it to every output.
 *
 * Opening a camera can block for seconds on some devices (the Portal's camera service waits on a
 * platform service), so [start] and [stop] block and must be called off the main thread.
 *
 * A capture session being configured doesn't mean frames are arriving - on the Portal the virtual
 * camera opens fine but its producer may not be running. So once active, the completed-frame rate
 * is checked every [FRAME_CHECK_MS]: [onFrames] reports a healthy stream and [onNoFrames] a dead one.
 */
class Camera2DirectSource(
    private val context: Context,
    private val cameraId: String,
    private val analysisExecutor: Executor,
    private val analyzerProvider: () -> ImageAnalysis.Analyzer?,
    /** Rotation that makes the camera's image upright for the device's current orientation. */
    private val rotationProvider: () -> Int,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var analysisReader: ImageReader? = null

    @Volatile private var running = false
    @Volatile private var starting = false

    // Bumped on every start() and stop() so callbacks from an earlier camera can tell they are stale
    // and must not tear down the current one.
    @Volatile private var generation = 0
    private val completedFrames = AtomicInteger(0)
    private var framesFlowing = false

    var onOpened: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    /** The stream is delivering frames at a usable rate. */
    var onFrames: (() -> Unit)? = null

    /** The session is active but (almost) no frames are completing. The source is left running; the
     *  owner decides whether to [stop] it. */
    var onNoFrames: (() -> Unit)? = null

    fun isRunning(): Boolean = running

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(outputs: List<Surface>, needsAnalysis: Boolean) {
        if (running || starting) return
        val gen = ++generation

        val targets = mutableListOf<Surface>().apply { addAll(outputs) }

        val handlerThread = HandlerThread("direct-camera-$cameraId").also { it.start() }
        val h = Handler(handlerThread.looper)

        if (needsAnalysis) {
            // Held from handing a frame to the analyzer until it closes that frame, which can be
            // well after analyze() returns (ML Kit closes asynchronously). While held, newer frames
            // are dropped: queueing them would pin every image slot, and acquiring past the limit
            // throws on the camera thread and takes the app down.
            val analyzing = AtomicBoolean(false)
            val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 3).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = try {
                        r.acquireLatestImage()
                    } catch (e: IllegalStateException) {
                        Timber.w("Camera2DirectSource: dropped analysis frame: $e")
                        null
                    } ?: return@setOnImageAvailableListener
                    val analyzer = analyzerProvider()
                    if (analyzer == null || !analyzing.compareAndSet(false, true)) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    val proxy = DirectImageProxy(image, rotationProvider()) { analyzing.set(false) }
                    analysisExecutor.execute {
                        try {
                            analyzer.analyze(proxy)
                        } catch (e: Exception) {
                            Timber.e("Camera2DirectSource: analyzer threw: $e")
                            proxy.close()
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
                    if (gen != generation) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(gen, camera, targets, h)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen != generation) return
                    Timber.w("Camera2DirectSource: camera disconnected (likely claimed by another client)")
                    release()
                    onError?.invoke()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen != generation) return
                    Timber.e("Camera2DirectSource: camera onError code=$error")
                    release()
                    onError?.invoke()
                }
            }, h)
        } catch (e: Exception) {
            Timber.e("Camera2DirectSource: openCamera() threw: $e")
            release()
            onError?.invoke()
        }
    }

    private fun createSession(gen: Int, camera: CameraDevice, targets: List<Surface>, handler: Handler) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                targets.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (gen != generation) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        completedFrames.set(0)
                        framesFlowing = false
                        session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult,
                            ) {
                                completedFrames.incrementAndGet()
                            }
                        }, handler)
                        running = true
                        starting = false
                        Timber.i("Camera2DirectSource: capture session active (${targets.size} target(s))")
                        handler.postDelayed({ checkFrames(gen) }, FRAME_CHECK_MS)
                        onOpened?.invoke()
                    } catch (e: Exception) {
                        Timber.e("Camera2DirectSource: setRepeatingRequest threw: $e")
                        release()
                        onError?.invoke()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (gen != generation) return
                    Timber.e("Camera2DirectSource: capture session configure failed")
                    release()
                    onError?.invoke()
                }
            }, handler)
        } catch (e: Exception) {
            Timber.e("Camera2DirectSource: createCaptureSession threw: $e")
            release()
            onError?.invoke()
        }
    }

    /** Runs on the camera thread every [FRAME_CHECK_MS] while the session is active. */
    private fun checkFrames(gen: Int) {
        if (gen != generation || !running) return
        val frames = completedFrames.getAndSet(0)
        if (frames < MIN_FRAMES_PER_CHECK) {
            Timber.e(
                "Camera2DirectSource: only $frames frame(s) completed in the last ${FRAME_CHECK_MS}ms - " +
                    "the camera is open but not delivering video"
            )
            onNoFrames?.invoke()
            return
        }
        if (!framesFlowing) {
            framesFlowing = true
            Timber.i("Camera2DirectSource: receiving frames ($frames in ${FRAME_CHECK_MS}ms)")
            onFrames?.invoke()
        }
        handler?.postDelayed({ checkFrames(gen) }, FRAME_CHECK_MS)
    }

    /** Closes the camera and frees everything it held. Safe to call at any point, including when
     *  not running. Blocks until the camera is closed. */
    @Synchronized
    fun stop() {
        generation++
        release()
    }

    // Also used when the camera drops or fails, so a dead session never leaves its thread or reader behind.
    @Synchronized
    private fun release() {
        running = false
        starting = false
        handler?.removeCallbacksAndMessages(null)
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

    companion object {
        private const val FRAME_CHECK_MS = 5_000L

        // A working camera completes at least ~15 frames a second; a camera whose producer isn't
        // running (see the class comment) completes about one a second as the pipeline flushes
        // placeholder frames. Under 2 fps is treated as not delivering video.
        private const val MIN_FRAMES_PER_CHECK = 10
    }
}
