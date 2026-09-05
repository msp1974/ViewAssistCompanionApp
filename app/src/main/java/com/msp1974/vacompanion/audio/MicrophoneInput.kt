package com.msp1974.vacompanion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioEnhancerSource {
    const val UNAVAILABLE = "Unavailable"
    const val ANDROID = "Operating System"
    const val SOFTWARE = "VACA"
}

class MicrophoneInput (
    val context: Context,
    val deviceManager: DeviceManager,
) : AutoCloseable {

    private val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ
    private val channelConfig: Int = VACAAudioFormat.CHANNELS
    private val audioFormat: Int = VACAAudioFormat.ENCODING
    private val config: APPConfig = deviceManager.config

    companion object {
        // Resolved AGC/noise-suppression source, updated whenever a MicrophoneInput sets up its
        // audio effects - hardware if the platform effect attached successfully, software if
        // AudioEnhancer's fallback is running instead, unavailable before any mic has started.
        var agcSource: String = AudioEnhancerSource.UNAVAILABLE
            private set
        var nsSource: String = AudioEnhancerSource.UNAVAILABLE
            private set
        // AEC has no software fallback in this app (unlike AGC/NS), so it's purely a hardware
        // capability check - computed once since it can't change at runtime.
        var aecSource: String  = AudioEnhancerSource.UNAVAILABLE
            private set


    }

    private var audioRecord: AudioRecord? = null

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    private val audioEnhancer = AudioEnhancer(sampleRateInHz, context)
    private var totalFramesRead = 0L

    private var audioDSP = AudioDSP()

    // Owns all microphone device selection, the AudioDeviceCallback, and Bluetooth SCO -
    // MicrophoneInput is deliberately blind to any of that beyond calling
    // getPreferredMicrophone() to learn what to capture from and applyPreferredDevice() to
    // route to it. See AudioInRouter for details.
    private val micController = AudioInRouter(context, deviceManager) { onPreferredMicrophoneChanged() }

    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING



    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (audioRecord == null) {
            micController.start()
            audioRecord = createAudioRecord()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {

        val preferred = micController.getPreferredMicrophone()
        Timber.i(
            "Using microphone: ${preferred.device?.productName} ${AudioInRouter.getDeviceTypeName(preferred.device?.type ?: -1)}, AudioSource: ${VACAAudioFormat.getAudioSourceName(preferred.audioSource)}")
        val record = AudioRecord(
            preferred.audioSource,
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }

        val isBuiltIn = preferred.device?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        setupAudioEffects(record, attachNs = !isBuiltIn)

        micController.applyPreferredDevice(record, preferred.device)

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
        Timber.d("Microphone started")
        return record
    }

    // Called by AudioInRouter whenever the preferred microphone changes - just
    // restart the AudioRecord so createAudioRecord() picks up the new device/source.
    private fun onPreferredMicrophoneChanged() {
        if (audioRecord == null) return
        Timber.d("Preferred mic changed, restarting AudioRecord")
        recreateAudioRecord()
    }

    // Permission is guaranteed here: this is only reachable via start() or micController's
    // preferred-mic-changed callback, both after RECORD_AUDIO was granted.
    @SuppressLint("MissingPermission")
    private fun recreateAudioRecord() {
        val wasRecording = isRecording

        agc?.release()
        agc = null
        ns?.release()
        ns = null

        audioRecord?.let {
            if (isRecording) it.stop()
            it.release()
        }

        audioRecord = createAudioRecord()
    }

    fun readBytes(): ByteBuffer {
        val audioShortBuffer = readShort(bufferSize)
        val buffer = ByteBuffer.allocateDirect(audioShortBuffer.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(audioShortBuffer)
        buffer.rewind()
        return buffer
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, applyEnhancement: Boolean = true): ShortArray {
        val audioBuffer = ShortArray(bufferSize)
        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            totalFramesRead += readCount
            val frame = audioBuffer.copyOfRange(0, readCount)
            if (applyEnhancement) {
                // processFrame() internally no-ops on AGC/noise suppression when the
                // device covers them in hardware - so it's always safe/cheap to route
                // through here rather than tracking which sub-feature(s) are actually active.
                audioEnhancer.setMicGainDb(config.micGain.toFloat())
                return audioEnhancer.processFrame(frame)
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

    private fun setupAudioEffects(record: AudioRecord, attachNs: Boolean = true, attachAgc: Boolean = true, attachAec: Boolean = true) {
        val sessionId = record.audioSessionId

        agcSource = AudioEnhancerSource.UNAVAILABLE
        nsSource = AudioEnhancerSource.UNAVAILABLE
        aecSource = AudioEnhancerSource.UNAVAILABLE

        // Catch if issue with audio enhancements and do not load any platform effects -
        // the software AudioEnhancer below still covers AGC/NS on these devices.
        val skipHardwareEffects = UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)

        if (!skipHardwareEffects) {
            if (attachAgc) {
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        if (agc != null) agcSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware AGC: ${e.message}")
                    }
                }
                if (agc == null) {
                    audioEnhancer.agcEnabled = true
                    agcSource = AudioEnhancerSource.SOFTWARE
                }
            }

            if (attachNs) {
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                        if (ns != null) nsSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware noise suppressor: ${e.message}")
                    }
                }
                if (ns == null) {
                    audioEnhancer.noiseSuppressionEnabled = true
                    nsSource = AudioEnhancerSource.SOFTWARE
                }
            }

            if (attachAec) {
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                        if (aec != null) aecSource = AudioEnhancerSource.ANDROID
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware AEC: ${e.message}")
                    }
                }
            }
        } else {
            Timber.d("Skipping hardware audio enhancements on this device")
            if (attachAgc) {
                audioEnhancer.agcEnabled = true
                agcSource = AudioEnhancerSource.SOFTWARE
            }
            if (attachNs) {
                audioEnhancer.noiseSuppressionEnabled = true
                nsSource = AudioEnhancerSource.SOFTWARE
            }
        }

        audioEnhancer.reset()
        Timber.d(
            "Audio enhancement - AGC: ${agcSource}, NS: ${nsSource}, AEC: ${aecSource}"
        )
    }

    override fun close() {
        micController.stop()
        audioEnhancer.release()

        agc?.release()
        agc = null

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
