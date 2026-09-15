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

        // Wall-clock deadline (System.currentTimeMillis()) until which captured mic frames are
        // replaced with digital silence before they reach the AGC, the wake-word engine, or
        // anything streamed onward. Used to blank out the device's own short, non-interactive
        // sound effects (wake-word confirmation chime, error sound) - most devices have no
        // hardware AEC, so without this the acoustic leak-back from speaker to mic both pollutes
        // the AGC's envelope/noise-floor (leaving it too low to amplify the command that follows
        // until it decays back down) and gets forwarded to HA. Never used for TTS/alarm playback,
        // where the mic must stay live for barge-in.
        @Volatile
        private var suppressUntilMs: Long = 0L

        /**
         * Silences captured mic audio for [durationMs] from now. Safe to call repeatedly with a
         * shorter, more precise duration once it's known (e.g. once actual playback is confirmed
         * to have ended) - each call simply replaces the deadline outright.
         */
        fun suppressMicFor(durationMs: Long) {
            suppressUntilMs = System.currentTimeMillis() + durationMs
        }

        private fun isMicSuppressed(): Boolean = System.currentTimeMillis() < suppressUntilMs
    }

    private var audioRecord: AudioRecord? = null
    private var webRtcSdkAudioProcessor: WebRtcSdkAudioProcessor? = null

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
       
        get() = if (useWebRtcApmBackend()) {
            webRtcSdkAudioProcessor?.isRunning() == true
        } else {
            audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }




    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (useWebRtcApmBackend()) {
            if (webRtcSdkAudioProcessor == null) {
                webRtcSdkAudioProcessor = WebRtcSdkAudioProcessor(
                    context = config.context,
                    sampleRateHz = sampleRateInHz,
                    channels = 1,
                    audioSource = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
                    audioFormat = audioFormat,
                    manualGainMultiplierProvider = {
                        // Map micGain (-10..10) to a stronger dB-scale gain curve.
                        // ~1.8 dB per step gives significantly more lift in noisy rooms.
                        val gainDb = config.micGain * 1.8f
                        Math.pow(10.0, (gainDb / 20.0).toDouble()).toFloat().coerceIn(0.1f, 6.0f)
                    }
                )
            }

            if (!isRecording) {
                Timber.d(
                    "Starting microphone source=%d backend=%s webrtc_sdk=true",
                    VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
                    config.experimentalAudioBackend
                )
                webRtcSdkAudioProcessor?.start()
            } else {
                Timber.w("Microphone already started")
            }
            return
        }

        if (audioRecord == null) {
            micController.start()
            audioRecord = createAudioRecord()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {

        val preferred = micController.getPreferredMicrophone()
        val isBuiltIn = preferred.device?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC

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
        micController.applyPreferredDevice(record, preferred.device)
        setupAudioEffects(record, attachNs = !isBuiltIn)

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording()
        } else {
            Timber.w("Microphone already started")
        }
        Timber.i("Microphone started")
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
        audioRecord = null
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
        if (useWebRtcApmBackend()) {
            val sdkSamples = webRtcSdkAudioProcessor?.readSamples(bufferSize) ?: ShortArray(0)
            return if (sdkSamples.isNotEmpty()) sdkSamples else ShortArray(0)
        }

        val audioRecord = this.audioRecord ?: error("Microphone not started")
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            totalFramesRead += readCount
            val frame = audioBuffer.copyOfRange(0, readCount)
            if (isMicSuppressed()) {
                // Return true digital silence without running it through the AGC/NS - this
                // keeps the frame cadence the wake-word engine expects, while leaving the AGC's
                // envelope/noise-floor exactly where they were before the suppressed sound
                // started (rather than dragged up by it), so gain is already correct for real
                // speech the instant suppression lifts.
                return ShortArray(frame.size)
            }
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

    private fun useWebRtcApmBackend(): Boolean {
        return config.experimentalAudioBackend.equals(APPConfig.AUDIO_BACKEND_WEBRTC_APM, ignoreCase = true)
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

        webRtcSdkAudioProcessor?.close()
        webRtcSdkAudioProcessor = null

        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
            audioRecord = null
        }
    }
}
