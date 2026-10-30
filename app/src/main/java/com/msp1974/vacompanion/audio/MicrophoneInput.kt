package com.msp1974.vacompanion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

object AudioEffectSource {
    const val UNAVAILABLE = "Unavailable"
    const val ANDROID = "Operating System"
    const val WEBRTC = "WebRTC"
}

class MicrophoneInput (
    val context: Context,
    val deviceManager: DeviceManager,
    private val suppressor: MicSuppressor = MicSuppressor(),
    // Fed every frame as it is read, before any processing, so the real loudness can be told.
    private val level: MicLevelMonitor? = null,
) : AutoCloseable {

    private val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ
    private val channelConfig: Int = VACAAudioFormat.CHANNELS
    private val audioFormat: Int = VACAAudioFormat.ENCODING
    private val config: APPConfig = deviceManager.config

    companion object {
        // Where the gain control and noise suppression come from: WebRTC, or unavailable if it could
        // not be started on this device (the microphone is then not levelled at all). Updated
        // whenever a MicrophoneInput sets up its audio effects.
        var agcSource: String = AudioEffectSource.UNAVAILABLE
            private set
        var nsSource: String = AudioEffectSource.UNAVAILABLE
            private set
        // Echo cancellation is only ever the platform's, so this is purely a hardware capability
        // check - computed once since it can't change at runtime.
        var aecSource: String  = AudioEffectSource.UNAVAILABLE
            private set

        // The `mic_gain` setting is clamped to this either side of 0 dB.
        private const val MIC_GAIN_LIMIT_DB = 10f
    }

    // Guards reads/writes of audioRecord: recreateAudioRecord() runs on the main thread (via
    // AudioInRouter's device/route-change callback) while readShort() runs on the wake-word
    // engine's background coroutine - without this, readShort() could observe audioRecord as
    // null mid-swap and throw, permanently killing wake-word capture (see issue #65).
    private val audioRecordLock = Any()
    private var audioRecord: AudioRecord? = null

    // Set (under audioRecordLock) by close(). Route-change callbacks arrive on the main thread
    // and can race a close() on another thread - without this, one that had already passed its
    // null check would still recreate the AudioRecord after close() released it, leaving the
    // microphone open for good.
    private var closed = false

    private var aec: AcousticEchoCanceler? = null

    // Null if WebRTC could not be started on this device.
    private val webRtc: WebRtcMicProcessor? = WebRtcMicProcessor.create()
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
        synchronized(audioRecordLock) {
            if (!closed && audioRecord == null) {
                micController.start()
                audioRecord = createAudioRecord()
            }
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
        micController.applyPreferredDevice(record, preferred.device)
        setupAudioEffects(record)

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
        synchronized(audioRecordLock) {
            if (closed || audioRecord == null) return

            audioRecord?.let {
                if (isRecording) it.stop()
                it.release()
            }
            audioRecord = null
            audioRecord = createAudioRecord()
        }
    }

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, applyEnhancement: Boolean = true): ShortArray {
        val audioBuffer = ShortArray(bufferSize)
        // Snapshot the current record under the lock rather than null-checking then reading -
        // otherwise a concurrent recreateAudioRecord() swap could be observed as null right
        // after the check, throwing and permanently ending the caller's read loop. A mid-swap
        // read now just yields no audio for this frame, same as any other short read.
        val audioRecord = synchronized(audioRecordLock) { this.audioRecord } ?: return ShortArray(0)
        val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)
        if (readCount > 0) {
            val frame = audioBuffer.copyOfRange(0, readCount)
            level?.update(frame, sampleRateInHz)
            if (applyEnhancement) {
                // WebRTC levels the signal to a fixed output level; the mic gain setting then
                // raises or lowers that, unless the microphone is being turned right down.
                val micGainDb = config.micGain.toFloat().coerceIn(-MIC_GAIN_LIMIT_DB, MIC_GAIN_LIMIT_DB)
                return suppressor.applyTo(webRtc?.process(frame) ?: frame, micGainDb)
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

    private fun setupAudioEffects(record: AudioRecord) {
        val sessionId = record.audioSessionId

        // Gain control and noise suppression are WebRTC's. The platform's own are deliberately not
        // attached: two gain controls in a row would fight each other.
        agcSource = if (webRtc != null) AudioEffectSource.WEBRTC else AudioEffectSource.UNAVAILABLE
        nsSource = agcSource
        aecSource = AudioEffectSource.UNAVAILABLE

        // Catch if issue with audio enhancements and do not load any platform effects.
        val skipHardwareEffects = UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)

        if (!skipHardwareEffects) {
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    if (aec != null) aecSource = AudioEffectSource.ANDROID
                } catch (e: Exception) {
                    Timber.w("Failed to attach hardware AEC: ${e.message}")
                }
            }
        } else {
            Timber.d("Skipping hardware audio enhancements on this device")
        }

        Timber.d(
            "Audio enhancement - AGC: ${agcSource}, NS: ${nsSource}, AEC: ${aecSource}"
        )
    }

    override fun close() {
        // Stop route-change callbacks from recreating the AudioRecord before releasing it.
        synchronized(audioRecordLock) { closed = true }

        // Each step is isolated so that a failure in one can never leave the AudioRecord (and so
        // the microphone) held open.
        runCatching { micController.stop() }
            .onFailure { Timber.w(it, "Error stopping microphone controller") }
        runCatching { webRtc?.close() }
            .onFailure { Timber.w(it, "Error releasing WebRTC audio processing") }

        synchronized(audioRecordLock) {
            audioRecord?.let {
                try {
                    if (isRecording) it.stop()
                } catch (e: Exception) {
                    Timber.w(e, "Error stopping AudioRecord")
                } finally {
                    runCatching { it.release() }
                        .onFailure { e -> Timber.w(e, "Error releasing AudioRecord") }
                    audioRecord = null
                }
            }
        }
    }
}
