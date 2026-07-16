package com.msp1974.vacompanion.audio

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class MicrophoneInput (
    val config: APPConfig,
    val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    val channelConfig: Int = VACAAudioFormat.CHANNELS,
    val audioFormat: Int = VACAAudioFormat.ENCODING,
    val frameSize: Int = 0,
) : AutoCloseable {
    private var audioRecord: AudioRecord? = null

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private var audioDSP = AudioDSP()

    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    val speex = SpeexProcessor(sampleRate = sampleRateInHz, frameSize = if (frameSize > 0) frameSize else bufferSize )

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (audioRecord == null) {
            audioRecord = createAudioRecord()
            setupAudioEffects()
        }

        if (!isRecording) {
            Timber.d("Starting microphone with AGC=${agc != null}, AEC=${aec != null}, NS=${ns != null}")
            audioRecord?.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
    }

    fun readBytes(): ByteBuffer {
        val audioShortBuffer = readShort(bufferSize)
        val buffer = ByteBuffer.allocateDirect(audioShortBuffer.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(audioShortBuffer)
        buffer.rewind()
        return buffer
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, useSpeex: Boolean = true): ShortArray {
        val audioBuffer = ShortArray(bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            val frame = audioBuffer.copyOfRange(0, readCount)
            if (useSpeex && !AutomaticGainControl.isAvailable() && config.micGain != 0) {
                // mic_gain is a plain dB trim (-10..+10 dB); 0 = unprocessed
                val gain = 10.0.pow(config.micGain / 20.0).toFloat()
                for (i in frame.indices) {
                    frame[i] = (frame[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            return frame
        }
        return ShortArray(0)
    }

    fun readFloat(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS): FloatArray {
        val audioBuffer = readShort(bufferSize)

        if (audioBuffer.isNotEmpty()) {
            return audioDSP.normaliseAudioBuffer(audioBuffer)
        }
        return FloatArray(0)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        val audioRecord = AudioRecord(
            audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }
        return audioRecord
    }

    private fun setupAudioEffects() {
        val sessionId = audioRecord?.audioSessionId ?: return

        // Catch if issue with audio enhancements and do not load them
        if (UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)) return

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
            }

            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            }
        } catch (e: Exception) {}
    }

    override fun close() {

        agc?.release()
        agc = null

        aec?.release()
        aec = null

        ns?.release()
        ns = null

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
    }
}