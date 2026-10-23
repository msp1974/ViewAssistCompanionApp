package com.msp1974.vacompanion.webrtc

import com.msp1974.vacompanion.rtsp.EncodedFrame
import com.msp1974.vacompanion.rtsp.H264Encoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import android.os.SystemClock
import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Hands libwebrtc one H.264 stream that is encoded once, however many viewers there are.
 *
 * libwebrtc normally starts a hardware encoder per viewer. A device only has a few, so a card that
 * keeps reconnecting makes the next one fail to start - and libwebrtc then leaves its output thread
 * spinning on the dead codec. Instead [H264Encoder] (the one RTSP uses, set to Baseline profile so
 * it matches what is offered) encodes the camera, and each viewer's [PassThroughEncoder] just passes
 * those frames on.
 *
 * Other codecs (VP8 for a viewer without H.264) still come from [fallback].
 */
class SharedH264EncoderFactory(
    private val encoder: H264Encoder,
    private val fallback: VideoEncoderFactory,
) : VideoEncoderFactory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val viewers = CopyOnWriteArrayList<PassThroughEncoder>()

    init {
        encoder.frames.onEach { frame -> viewers.forEach { it.onEncoded(frame) } }.launchIn(scope)
    }

    fun close() {
        scope.cancel()
        viewers.clear()
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> {
        val h264 = VideoCodecInfo(
            "H264",
            mapOf(
                "level-asymmetry-allowed" to "1",
                "packetization-mode" to "1",
                "profile-level-id" to "42e01f",
            ),
            emptyList()
        )
        val others = fallback.supportedCodecs.filter { !it.name.equals("H264", ignoreCase = true) }
        return (listOf(h264) + others).toTypedArray()
    }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (!info.name.equals("H264", ignoreCase = true)) return fallback.createEncoder(info)
        return PassThroughEncoder(encoder, viewers)
    }

    /**
     * One viewer's encoder. libwebrtc calls [encode] for each camera frame and only accepts an
     * encoded image whose capture time is one of those frames' - so frames and encoded pictures are
     * queued and paired in order, which works because both come from the same camera frames.
     *
     * [encode] only queues and returns: libwebrtc's encoder thread waits on it, and it must not
     * wait on anything libwebrtc is holding. Pictures are handed back from [delivery], a thread of
     * their own, as a hardware encoder's output thread would.
     */
    private class PassThroughEncoder(
        private val source: H264Encoder,
        private val viewers: CopyOnWriteArrayList<PassThroughEncoder>,
    ) : VideoEncoder {

        @Volatile private var callback: VideoEncoder.Callback? = null
        // Made by initEncode and shut down by release: libwebrtc releases and re-initialises the
        // same encoder whenever it reconfigures (a bitrate or size change), so it can't be once only.
        @Volatile private var delivery: ExecutorService? = null

        private val lock = Any()

        // Held while a picture is being handed to libwebrtc. release() takes it too, so by the time
        // release() returns nothing is still calling into an encoder libwebrtc has destroyed (which
        // crashes the app): the same guarantee a hardware encoder gets by joining its output thread.
        private val deliverLock = ReentrantLock()
        private val timestamps = ArrayDeque<Long>()
        private val pictures = ArrayDeque<EncodedFrame>()

        // After dropping a picture the rest are undecodable until the next keyframe.
        private var waitingForKeyFrame = true
        private var lastKeyFrameRequest = 0L

        override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
            synchronized(lock) {
                this.callback = callback
                timestamps.clear()
                pictures.clear()
                waitingForKeyFrame = true
            }
            delivery?.shutdown()
            delivery = Executors.newSingleThreadExecutor { Thread(it, "VacaWebRtcDelivery") }
            viewers.add(this)
            source.requestKeyFrame()
            return VideoCodecStatus.OK
        }

        override fun release(): VideoCodecStatus {
            viewers.remove(this)
            callback = null
            synchronized(lock) {
                timestamps.clear()
                pictures.clear()
            }
            delivery?.shutdown()
            delivery = null
            if (deliverLock.tryLock(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) deliverLock.unlock()
            else Timber.w("SharedH264EncoderFactory: a delivery was still in progress at release")
            return VideoCodecStatus.OK
        }

        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
            // The source makes a keyframe every second anyway; asking more than a couple of times a
            // second only makes it send a stream of oversized frames.
            if (info.frameTypes.any { it == EncodedImage.FrameType.VideoFrameKey }) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastKeyFrameRequest >= KEY_FRAME_REQUEST_GAP_MS) {
                    lastKeyFrameRequest = now
                    source.requestKeyFrame()
                }
            }
            synchronized(lock) {
                timestamps.addLast(frame.timestampNs)
                while (timestamps.size > MAX_QUEUED) timestamps.removeFirst()
            }
            deliverSoon()
            return VideoCodecStatus.OK
        }

        fun onEncoded(picture: EncodedFrame) {
            synchronized(lock) {
                if (callback == null) return
                if (waitingForKeyFrame && !picture.isKeyFrame) return
                if (picture.isKeyFrame) waitingForKeyFrame = false
                pictures.addLast(picture)
                while (pictures.size > MAX_QUEUED) {
                    pictures.removeFirst()
                    waitingForKeyFrame = true
                }
            }
            deliverSoon()
        }

        private fun deliverSoon() {
            try {
                delivery?.execute { deliver() }
            } catch (_: RejectedExecutionException) {
                // Released while this was being queued.
            }
        }

        private fun deliver() {
            while (true) {
                deliverLock.lock()
                try {
                    val target = callback ?: return
                    val picture: EncodedFrame
                    val capturedAt: Long
                    synchronized(lock) {
                        if (timestamps.isEmpty() || pictures.isEmpty()) return
                        picture = pictures.removeFirst()
                        capturedAt = timestamps.removeFirst()
                    }
                    try {
                        target.onEncodedFrame(toImage(picture, capturedAt), VideoEncoder.CodecSpecificInfo())
                    } catch (e: Exception) {
                        Timber.w("SharedH264EncoderFactory: couldn't pass a frame on: $e")
                    }
                } finally {
                    deliverLock.unlock()
                }
            }
        }

        private fun toImage(picture: EncodedFrame, capturedAtNs: Long): EncodedImage {
            // Annex-B: each NAL unit behind a start code.
            val size = picture.nals.sumOf { it.size + START_CODE.size }
            val buffer = ByteBuffer.allocateDirect(size)
            picture.nals.forEach { buffer.put(START_CODE).put(it) }
            buffer.flip()
            return EncodedImage.builder()
                .setBuffer(buffer, null)
                .setEncodedWidth(source.width)
                .setEncodedHeight(source.height)
                .setCaptureTimeNs(capturedAtNs)
                .setFrameType(
                    if (picture.isKeyFrame) EncodedImage.FrameType.VideoFrameKey
                    else EncodedImage.FrameType.VideoFrameDelta
                )
                .setRotation(0)
                .createEncodedImage()
        }

        // The stream is fixed by H264Encoder's own settings, not by libwebrtc's rate control.
        override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, frameRate: Int) = VideoCodecStatus.OK
        override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF
        override fun getImplementationName() = "VacaSharedH264"
        override fun isHardwareEncoder() = true
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val MAX_QUEUED = 8
        private const val KEY_FRAME_REQUEST_GAP_MS = 500L
        private const val RELEASE_WAIT_MS = 2000L
    }
}
