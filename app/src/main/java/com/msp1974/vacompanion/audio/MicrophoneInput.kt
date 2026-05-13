package com.msp1974.vacompanion.audio

import android.Manifest
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    private val disableAndroidAudioEffects: Boolean by lazy {
        config.disableAndroidAudioEffects || isKnownProblematicAndroidAudioEffectsDevice()
    }

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
            val useSpeex = disableAndroidAudioEffects || !AutomaticGainControl.isAvailable()
            Timber.d(
                "Starting microphone with AndroidAudioEffectsDisabled=$disableAndroidAudioEffects, " +
                    "AGC=${agc != null}, AEC=${aec != null}, NS=${ns != null}, Speex=$useSpeex"
            )
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
            if (useSpeex && (disableAndroidAudioEffects || !AutomaticGainControl.isAvailable())) {
                speex.echoSuppressionEnabled = false
                speex.denoiseEnabled = false
                speex.setMaxAGCGain(10f + (config.micGain * 1.95f))
                return speex.processFrame(audioBuffer.copyOfRange(0, readCount))
            }
            return audioBuffer.copyOfRange(0, readCount)
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
        if (disableAndroidAudioEffects) {
            agc = null
            aec = null
            ns = null
            Timber.w(
                "Android audio effects disabled for stability. " +
                    "manufacturer=${Build.MANUFACTURER}, brand=${Build.BRAND}, model=${Build.MODEL}, " +
                    "device=${Build.DEVICE}, product=${Build.PRODUCT}"
            )
            return
        }

        val sessionId = audioRecord?.audioSessionId ?: return

        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable Android AutomaticGainControl")
            agc = null
        }

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable Android AcousticEchoCanceler")
            aec = null
        }

        try {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to enable Android NoiseSuppressor")
            ns = null
        }
    }

    private fun isKnownProblematicAndroidAudioEffectsDevice(): Boolean {
        return isLenovoTb8505fs()
    }

    private fun isLenovoTb8505fs(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val model = Build.MODEL.orEmpty().lowercase()
        val device = Build.DEVICE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()
        val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()

        val isLenovo =
            manufacturer.contains("lenovo") ||
                brand.contains("lenovo") ||
                fingerprint.contains("lenovo")
        val isTb8505fs =
            model.contains("tb-8505fs") ||
                device.contains("8505") ||
                product.contains("8505") ||
                fingerprint.contains("lenovotb-8505fs") ||
                fingerprint.contains("8505fs")

        return isLenovo && isTb8505fs
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
