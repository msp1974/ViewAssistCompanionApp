package com.msp1974.vacompanion.device.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Display
import android.view.Surface
import timber.log.Timber
import kotlin.math.abs

/**
 * The rotation to apply to the camera image so it appears upright. That is the camera's own
 * [sensorOrientation] - the device reports it already allowing for how it is oriented, so it is
 * used as it stands rather than being worked out again from the display rotation.
 *
 * It is re-read whenever the display rotates, so a device that updates it as it is turned (such as
 * the rotating Portal+) is followed. [CameraController] uses it to orient the raw frames of the
 * direct Camera2 backend, which has no CameraX transform at all.
 *
 * @param cameraId the camera to describe, or null for the device's front camera.
 */
class CameraOrientation(context: Context, cameraId: String?) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val resolvedCameraId: String? = try {
        cameraId ?: cameraManager.cameraIdList.firstOrNull {
            cameraManager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    } catch (e: Exception) {
        Timber.w("CameraOrientation: couldn't find the camera: $e")
        null
    }

    private fun readSensorOrientation(): Int = try {
        resolvedCameraId
            ?.let { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) }
            ?: 0
    } catch (e: Exception) {
        Timber.w("CameraOrientation: couldn't read the sensor orientation: $e")
        0
    }

    /**
     * Degrees (0, 90, 180 or 270) the camera image must be rotated to be upright, as reported by
     * the camera. Cached, because the direct backend asks for it on every analysis frame; it is
     * refreshed whenever the display rotates.
     */
    @Volatile
    var sensorOrientation: Int = readSensorOrientation()
        private set

    /** The default display's rotation, as a [Surface] ROTATION_* constant. CameraX is given this as
     *  its target rotation; it is not used to work out [sensorOrientation]. */
    val surfaceRotation: Int
        get() = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0

    /**
     * Whether the device's natural orientation - the display at rotation 0 - is landscape (most
     * tablets and panels) rather than portrait (phones). Only used for diagnostics, to tell
     * whether a camera's reported orientation fits how the device is built.
     */
    val isNaturalLandscape: Boolean
        get() {
            val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            val size = android.graphics.Point().also { display.getRealSize(it) }
            val turned = surfaceRotation == Surface.ROTATION_90 || surfaceRotation == Surface.ROTATION_270
            val naturalWidth = if (turned) size.y else size.x
            val naturalHeight = if (turned) size.x else size.y
            return naturalWidth > naturalHeight
        }

    /** Called on the main thread when the display has rotated, after [sensorOrientation] has been
     *  re-read. */
    var onRotationChanged: (() -> Unit)? = null

    private var lastRotation = surfaceRotation

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val rotation = surfaceRotation
            if (rotation == lastRotation) return
            lastRotation = rotation
            sensorOrientation = readSensorOrientation()
            Timber.d("CameraOrientation: display rotated, sensor orientation now ${sensorOrientation}deg")
            onRotationChanged?.invoke()
        }

        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    fun start() {
        lastRotation = surfaceRotation
        sensorOrientation = readSensorOrientation()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    fun stop() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    /**
     * The camera sensor's own shape as width / height, always 1 or more (landscape): the active
     * pixel array, which is how the camera frames the world before any turning. Null if the camera
     * can't be read.
     */
    val sensorAspect: Float? by lazy {
        try {
            resolvedCameraId
                ?.let { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) }
                ?.takeIf { it.width() > 0 && it.height() > 0 }
                ?.let { maxOf(it.width(), it.height()).toFloat() / minOf(it.width(), it.height()) }
        } catch (e: Exception) {
            Timber.w("CameraOrientation: couldn't read the sensor size: $e")
            null
        }
    }

    /**
     * The camera output size, for a SurfaceTexture, closest to [target]: the same shape if the
     * camera offers one, otherwise the nearest in area. The direct backend has no CameraX to pick
     * a size for it.
     */
    fun outputSize(target: Size): Size {
        val sizes = try {
            resolvedCameraId?.let { cameraManager.getCameraCharacteristics(it) }
        } catch (e: Exception) {
            null
        }
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.takeIf { it.isNotEmpty() }
            ?: return target
        val targetAspect = target.width.toFloat() / target.height
        val targetArea = target.width.toLong() * target.height
        return sizes.minByOrNull { size ->
            val aspectMiss = abs(size.width.toFloat() / size.height - targetAspect)
            val areaMiss = abs(size.width.toLong() * size.height - targetArea).toFloat() / targetArea
            // A different shape would be stretched, so it costs far more than a different size.
            (if (aspectMiss > 0.02f) 10f else 0f) + areaMiss
        } ?: target
    }
}
