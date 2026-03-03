package com.msp1974.viewassistcompanionapp.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.awaitCancellation
import android.content.Context
import kotlin.coroutines.coroutineContext
import android.annotation.SuppressLint
import com.msp1974.vacompanion.BuildConfig
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.util.Collections
import kotlin.math.min

/**
 * Handles audio recording from device microphone.
 * Emits audio buffers as a Flow for processing.
 */
internal class AudioRecorder(
    private val context: Context
) {
    private val config: APPConfig = APPConfig.getInstance(context)

    private var amplitudeBuffer = CircularArray(10)
    private var targetLevel = 0.25f
    private var minAmplification = 0.003f

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_IN_SHORTS = 1280
        const val MAX_LEVEL = 32768f
        const val MIC_GAIN_STEP = 0.025f
    }

    /**
     * Check if audio recording permission is granted.
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording audio and emit buffers as Flow.
     *
     * @return Flow of float arrays containing audio samples
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<FloatArray> = flow {
        if (BuildConfig.AUDIO_INPUT_DISABLED) {
            Timber.i("Audio input is disabled via BuildConfig. Suspending recording flow.")
            awaitCancellation()
            return@flow
        }

        require(hasRecordPermission()) { "RECORD_AUDIO permission not granted" }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_IN_SHORTS * 2)

        val audioRecord = AudioRecord(
            if (config.useVoiceEnhancer) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }

        val audioBuffer = ShortArray(BUFFER_SIZE_IN_SHORTS)

        try {
            audioRecord.startRecording()

            while (coroutineContext.isActive) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                var audioFloatBuffer: FloatArray

                if (readCount > 0) {
                    audioFloatBuffer = FloatArray(readCount) { i -> audioBuffer[i] / MAX_LEVEL}

                    // Mic boost
                    if (config.useAdvancedGain) {
                        val max = audioFloatBuffer.max()
                        if (max > minAmplification) {
                            amplitudeBuffer.add(max)

                            // define gain
                            val target = targetLevel + (config.micGain * MIC_GAIN_STEP)
                            var gain = target / amplitudeBuffer.average()

                            // Clip max amplitude
                            if (max * gain > 0.9) {
                                gain = min(250.0f, (gain / (max * gain )) * 0.9f)
                            }

                            // Apply gain
                            audioFloatBuffer =
                                (audioFloatBuffer.map { i -> i * gain }).toFloatArray()
                        }
                    }
                    emit(audioFloatBuffer)
                }
            }
        } finally {
            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            Timber.i("Audio input stopped")
        }
    }.flowOn(Dispatchers.Default)
}

class CircularArray(val size: Int) {
    private val buffer = MutableList(size) {0f}
    private var samples: Int = 0

    fun add(value: Float) {
        if (samples < size) samples++
        Collections.rotate(buffer, 1)
        buffer[size - 1] = value
    }

    fun average(): Float {
        return min(1f, buffer.sum() / samples)
    }
}