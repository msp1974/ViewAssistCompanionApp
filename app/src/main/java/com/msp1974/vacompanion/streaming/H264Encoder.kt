package com.msp1974.vacompanion.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Wraps a hardware H.264 encoder ([MediaCodec]) configured for Surface input, so CameraX
 * can render camera frames directly onto the encoder's input surface with no extra copy.
 * Emits parsed Annex-B NAL units to the abstract callback methods, matching this
 * codebase's convention for long-lived per-session objects (see WyomingTCPServer,
 * Satellite) rather than a lambda/interface-parameter style.
 */
abstract class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drainJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var spsPps: Pair<ByteArray, ByteArray>? = null
        private set

    /** A new encoded access unit (one video frame, possibly multiple NAL units) is ready. */
    abstract fun onAccessUnit(nals: List<ByteArray>, presentationTimeUs: Long, isKeyFrame: Boolean)

    /** The encoder produced (or re-produced) its SPS/PPS parameter sets. */
    abstract fun onConfig(sps: ByteArray, pps: ByteArray)

    abstract fun onEncoderError(error: Throwable)

    /** Configures and starts the encoder, returning the Surface CameraX should render onto. */
    fun start(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }

        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = mediaCodec.createInputSurface()
        mediaCodec.start()

        codec = mediaCodec
        inputSurface = surface
        drainJob = scope.launch { drainLoop(mediaCodec) }
        return surface
    }

    private suspend fun drainLoop(mediaCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            while (true) {
                val index = mediaCodec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val outputBuffer = mediaCodec.getOutputBuffer(index)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        handleOutput(outputBuffer, bufferInfo)
                    }
                    mediaCodec.releaseOutputBuffer(index, false)
                }
                kotlinx.coroutines.yield()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Timber.e("H264Encoder: drain loop error: $e")
            onEncoderError(e)
        }
    }

    private fun handleOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.get(data)

        val nals = splitAnnexBNalUnits(data)
        if (nals.isEmpty()) return

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Surface-input AVC encoders deliver SPS+PPS together, Annex-B framed, as the
            // first "codec config" buffer rather than via INFO_OUTPUT_FORMAT_CHANGED.
            val sps = nals.firstOrNull { (it[0].toInt() and 0x1F) == NAL_TYPE_SPS }
            val pps = nals.firstOrNull { (it[0].toInt() and 0x1F) == NAL_TYPE_PPS }
            if (sps != null && pps != null) {
                spsPps = sps to pps
                onConfig(sps, pps)
            }
            return
        }

        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        onAccessUnit(nals, info.presentationTimeUs, isKeyFrame)
    }

    suspend fun stop() {
        // cancelAndJoin (not just cancel) so the drain loop's in-flight
        // dequeueOutputBuffer call - cancellation is cooperative and doesn't interrupt
        // a call already blocked in it - has actually returned before we stop/release
        // the codec out from under it on another thread.
        drainJob?.cancelAndJoin()
        drainJob = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            Timber.w("H264Encoder: error stopping codec: $e")
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Timber.w("H264Encoder: error releasing codec: $e")
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
        spsPps = null
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 100_000L
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8
    }
}

private data class AnnexBStartCode(val codeStart: Int, val payloadStart: Int)

/**
 * Splits an Annex-B buffer (NAL units prefixed with 0x000001 or 0x00000001 start codes)
 * into individual NAL units with the start codes stripped.
 */
fun splitAnnexBNalUnits(data: ByteArray): List<ByteArray> {
    val codes = mutableListOf<AnnexBStartCode>()
    var i = 0
    while (i + 2 < data.size) {
        if (data[i] == ZERO && data[i + 1] == ZERO) {
            if (data[i + 2] == ONE) {
                codes.add(AnnexBStartCode(i, i + 3))
                i += 3
                continue
            } else if (i + 3 < data.size && data[i + 2] == ZERO && data[i + 3] == ONE) {
                codes.add(AnnexBStartCode(i, i + 4))
                i += 4
                continue
            }
        }
        i++
    }
    if (codes.isEmpty()) return emptyList()

    val nals = mutableListOf<ByteArray>()
    for (idx in codes.indices) {
        val start = codes[idx].payloadStart
        val end = if (idx + 1 < codes.size) codes[idx + 1].codeStart else data.size
        if (end > start) nals.add(data.copyOfRange(start, end))
    }
    return nals
}

private val ZERO = 0.toByte()
private val ONE = 1.toByte()
