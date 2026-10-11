package com.msp1974.vacompanion.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import com.msp1974.vacompanion.device.camera.CameraController
import com.msp1974.vacompanion.device.camera.TeeOutput
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

data class EncodedFrame(val nals: List<ByteArray>, val ptsUs: Long, val isKeyFrame: Boolean)

/**
 * Encodes the shared camera feed to H.264 via [MediaCodec], drawing frames from
 * [CameraController]'s [com.msp1974.vacompanion.device.camera.SurfaceTee] so it runs alongside
 * motion/face detection and the on-device preview rather than taking the camera for itself.
 */
class H264Encoder(
    private val cameraController: CameraController,
    private val config: APPConfig,
) {
    private var codec: MediaCodec? = null
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val running = AtomicBoolean(false)

    private val _frames = MutableSharedFlow<EncodedFrame>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<EncodedFrame> = _frames

    @Volatile private var spsPps: ByteArray? = null
    private var configReady = CompletableDeferred<Unit>()

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (running.get()) return
        val (w, h) = resolutionFor(config.rtspResolution)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.rtspBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.rtspFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.rtspKeyframeIntervalSecs)
        }

        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
        codec = mediaCodec
        running.set(true)
        configReady = CompletableDeferred()
        spsPps = null

        cameraController.setEncoderFps(config.rtspFps)
        cameraController.addSurfaceOutput(
            TeeOutput(id = OUTPUT_ID, surface = inputSurface, width = w, height = h, mirror = false)
        )

        scope.launch { drainOutput(mediaCodec) }
        Timber.i("H264Encoder: started ${w}x${h}@${config.rtspFps}fps, ${config.rtspBitrate}bps")
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) {
            Timber.w("H264Encoder: requestKeyFrame failed: $e")
        }
    }

    suspend fun awaitConfig(timeoutMs: Long = 4000): ByteArray? {
        return try {
            withTimeout(timeoutMs) {
                configReady.await()
                spsPps
            }
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        cameraController.removeSurfaceOutput(OUTPUT_ID)
        job.cancelChildren()
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Timber.w("H264Encoder: error stopping codec: $e")
        }
        codec = null
        Timber.i("H264Encoder: stopped")
    }

    private suspend fun drainOutput(mediaCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = try {
                mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            } catch (e: Exception) {
                if (running.get()) Timber.e("H264Encoder: dequeue error: $e")
                break
            }
            if (index < 0) continue

            val buffer = mediaCodec.getOutputBuffer(index)
            if (buffer != null && bufferInfo.size > 0) {
                buffer.position(bufferInfo.offset)
                buffer.limit(bufferInfo.offset + bufferInfo.size)
                val data = ByteArray(bufferInfo.size)
                buffer.get(data)

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    spsPps = data
                    if (!configReady.isCompleted) configReady.complete(Unit)
                } else {
                    val nals = NalUtils.splitNalUnits(data)
                    if (nals.isNotEmpty()) {
                        val isKey = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        // Re-send SPS/PPS ahead of every IDR so a client joining mid-stream (or
                        // one that dropped earlier packets) can always resync.
                        val toSend = if (isKey) {
                            NalUtils.splitNalUnits(spsPps ?: ByteArray(0)) + nals
                        } else {
                            nals
                        }
                        _frames.tryEmit(EncodedFrame(toSend, bufferInfo.presentationTimeUs, isKey))
                    }
                }
            }
            mediaCodec.releaseOutputBuffer(index, false)
        }
    }

    private fun resolutionFor(name: String): Pair<Int, Int> = when (name) {
        "480p" -> 854 to 480
        "1080p" -> 1920 to 1080
        else -> 1280 to 720
    }

    companion object {
        private const val OUTPUT_ID = "rtsp_encoder"
        private const val DEQUEUE_TIMEOUT_US = 100_000L
    }
}
