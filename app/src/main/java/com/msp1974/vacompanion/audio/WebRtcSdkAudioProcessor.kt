package com.msp1974.vacompanion.audio

import android.content.Context
import android.media.AudioFormat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Capture and process local microphone audio through the maintained WebRTC SDK stack.
 *
 * This avoids app-owned JNI hooks while still using WebRTC APM (NS/AEC/AGC) settings.
 */
class WebRtcSdkAudioProcessor(
    private val context: Context,
    private val sampleRateHz: Int,
    private val channels: Int = 1,
    private val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    private val audioFormat: Int = VACAAudioFormat.ENCODING,
    private val manualGainMultiplierProvider: () -> Float = { 1.0f },
) : AutoCloseable {
    private val frameQueue = LinkedBlockingQueue<ShortArray>(96)
    private val bufferLock = Any()

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSourceTrack: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var running = false
    private val enqueuedFrameCount = AtomicLong(0)
    private val readEmptyCount = AtomicLong(0)

    private var pendingFrame: ShortArray = ShortArray(0)
    private var pendingOffset: Int = 0

    private val sink = AudioTrackSink { audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames, _ ->
        if (!running) return@AudioTrackSink
        if (bitsPerSample != 16 || numberOfFrames <= 0 || numberOfChannels <= 0) return@AudioTrackSink

        val expectedSamples = numberOfFrames * numberOfChannels
        if (expectedSamples <= 0) return@AudioTrackSink

        // Prefer the AudioBufferCallback path as the primary source.
        // Keep sink for diagnostics and compatibility checks only.
        val frame = toShortArray(audioData, expectedSamples, expectedSamples * 2)
        if (frame.isEmpty()) return@AudioTrackSink

        if (sampleRate != sampleRateHz || numberOfChannels != channels) {
            Timber.v(
                "WebRTC audio frame mismatch sr=%d ch=%d expectedSr=%d expectedCh=%d",
                sampleRate,
                numberOfChannels,
                sampleRateHz,
                channels
            )
        }
    }

    fun isRunning(): Boolean = running

    fun start() {
        if (running) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .createInitializationOptions()
        )

        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setInputSampleRate(sampleRateHz)
            .setOutputSampleRate(sampleRateHz)
            .setAudioSource(audioSource)
            .setAudioFormat(audioFormat)
            .setAudioBufferCallback { buffer, encoding, numberOfChannels, sampleRate, bytesRead, captureTimestampNs ->
                if (!running || bytesRead <= 0 || numberOfChannels <= 0) {
                    return@setAudioBufferCallback captureTimestampNs
                }

                val bytesPerSample = when (encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> 2
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                    else -> 0
                }
                if (bytesPerSample <= 0) {
                    Timber.w("WebRTC callback unsupported encoding=%d bytesRead=%d", encoding, bytesRead)
                    return@setAudioBufferCallback captureTimestampNs
                }
                val expectedSamples = bytesRead / bytesPerSample
                if (expectedSamples <= 0) {
                    return@setAudioBufferCallback captureTimestampNs
                }

                val frame = toShortArray(buffer, expectedSamples, bytesRead)
                if (frame.isNotEmpty()) {
                    applyManualGainInPlace(frame, manualGainMultiplierProvider())
                    enqueueFrame(frame)
                    if (sampleRate != sampleRateHz || numberOfChannels != channels) {
                        Timber.v(
                            "WebRTC callback frame mismatch sr=%d ch=%d expectedSr=%d expectedCh=%d",
                            sampleRate,
                            numberOfChannels,
                            sampleRateHz,
                            channels
                        )
                    }
                }
                captureTimestampNs
            }
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        adm.setMicrophoneMute(false)

        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack("VACA_LOCAL_WAKE_TRACK", source)
        track.addSink(sink)
        track.setEnabled(true)

        audioDeviceModule = adm
        peerConnectionFactory = factory
        audioSourceTrack = source
        audioTrack = track
        running = true

        adm.prewarmRecording()
        adm.requestStartRecording()
        Timber.i("WebRTC SDK audio processor started sr=%d ch=%d", sampleRateHz, channels)
    }

    fun readSamples(maxSamples: Int): ShortArray {
        if (!running || maxSamples <= 0) return ShortArray(0)
        val out = ShortArray(maxSamples)
        var writeOffset = 0

        synchronized(bufferLock) {
            if (pendingOffset < pendingFrame.size) {
                val copyCount = minOf(maxSamples, pendingFrame.size - pendingOffset)
                System.arraycopy(pendingFrame, pendingOffset, out, 0, copyCount)
                pendingOffset += copyCount
                writeOffset += copyCount
                if (pendingOffset >= pendingFrame.size) {
                    pendingFrame = ShortArray(0)
                    pendingOffset = 0
                }
            }
        }

        while (writeOffset < maxSamples) {
            val timeoutMs = if (writeOffset == 0) 120L else 2L
            val frame = frameQueue.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: break
            val toCopy = minOf(frame.size, maxSamples - writeOffset)
            System.arraycopy(frame, 0, out, writeOffset, toCopy)
            writeOffset += toCopy
            if (toCopy < frame.size) {
                synchronized(bufferLock) {
                    pendingFrame = frame
                    pendingOffset = toCopy
                }
                break
            }
        }

        if (writeOffset == 0) {
            val emptyReads = readEmptyCount.incrementAndGet()
            if (emptyReads % 50L == 0L) {
                Timber.w(
                    "WebRTC SDK readSamples returned empty %d times queueSize=%d running=%s",
                    emptyReads,
                    frameQueue.size,
                    running
                )
            }
            return ShortArray(0)
        }
        return out.copyOf(writeOffset)
    }

    override fun close() {
        running = false
        frameQueue.clear()
        synchronized(bufferLock) {
            pendingFrame = ShortArray(0)
            pendingOffset = 0
        }
        readEmptyCount.set(0)

        runCatching { audioDeviceModule?.requestStopRecording() }
        runCatching { audioTrack?.removeSink(sink) }
        runCatching { audioTrack?.dispose() }
        runCatching { audioSourceTrack?.dispose() }
        runCatching { peerConnectionFactory?.dispose() }
        runCatching { audioDeviceModule?.release() }

        audioTrack = null
        audioSourceTrack = null
        peerConnectionFactory = null
        audioDeviceModule = null
    }

    private fun toShortArray(buffer: ByteBuffer, samples: Int, bytesToRead: Int): ShortArray {
        if (samples <= 0) return ShortArray(0)
        if (bytesToRead <= 0) return ShortArray(0)
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.position(0)
        source.limit(minOf(bytesToRead, source.capacity()))
        val shortBuffer = source.asShortBuffer()
        val count = minOf(samples, shortBuffer.remaining())
        if (count <= 0) return ShortArray(0)
        return ShortArray(count).also { shortBuffer.get(it, 0, count) }
    }

    private fun enqueueFrame(frame: ShortArray) {
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()
            frameQueue.offer(frame)
        }
        val count = enqueuedFrameCount.incrementAndGet()
//        if (count % 200L == 0L) {
//            Timber.i(
//                "WebRTC SDK audio frames enqueued=%d queueSize=%d lastSamples=%d",
//                count,
//                frameQueue.size,
//                frame.size
//            )
//        }
    }

    private fun applyManualGainInPlace(frame: ShortArray, gainMultiplier: Float) {
        val gain = gainMultiplier.coerceIn(0.1f, 6.0f)
        if (gain == 1.0f) return

        for (i in frame.indices) {
            frame[i] = (frame[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
