package com.msp1974.vacompanion.device.camera

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import androidx.camera.core.FlashState
import androidx.camera.core.ImageInfo
import androidx.camera.core.ImageProxy
import androidx.camera.core.ImmutableImageInfo
import androidx.camera.core.impl.TagBundle
import java.nio.ByteBuffer

/**
 * Minimal [ImageProxy] wrapping a raw [Image] from an [android.media.ImageReader]. Lets a frame
 * captured by [Camera2DirectSource] (the quirks-only direct-Camera2 backend - see
 * [DeviceCameraQuirks]) be handed to the exact same [androidx.camera.core.ImageAnalysis.Analyzer]
 * callback CameraX would otherwise drive, so `MotionDetector` never needs to know which backend
 * produced the frame.
 *
 * [ImmutableImageInfo.create]/[TagBundle.emptyBundle] are `@RestrictTo`'d to CameraX's own
 * library group, hence the suppression below - but they're the only way to build an [ImageInfo]
 * without CameraX's internal pipeline, and are exactly what this bridge needs.
 */
@SuppressLint("RestrictedApi", "UnsafeOptInUsageError")
class DirectImageProxy(
    private val image: Image,
    rotationDegrees: Int,
) : ImageProxy {

    private val info: ImageInfo = ImmutableImageInfo.create(
        TagBundle.emptyBundle(),
        image.timestamp,
        rotationDegrees,
        Matrix(),
        FlashState.UNKNOWN,
    )

    override fun close() = image.close()
    override fun getCropRect(): Rect = image.cropRect
    override fun setCropRect(rect: Rect?) { image.cropRect = rect }
    override fun getFormat(): Int = image.format
    override fun getHeight(): Int = image.height
    override fun getWidth(): Int = image.width
    override fun getImageInfo(): ImageInfo = info
    override fun getImage(): Image = image

    override fun getPlanes(): Array<ImageProxy.PlaneProxy> =
        image.planes.map { plane ->
            object : ImageProxy.PlaneProxy {
                override fun getRowStride(): Int = plane.rowStride
                override fun getPixelStride(): Int = plane.pixelStride
                override fun getBuffer(): ByteBuffer = plane.buffer
            }
        }.toTypedArray()
}
