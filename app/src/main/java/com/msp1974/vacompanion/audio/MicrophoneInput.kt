package com.msp1974.vacompanion.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.FunctionClasses
import com.msp1974.vacompanion.device.UnsupportedFunctionsDevice
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioEnhancerSource {
    const val UNAVAILABLE = "unavailable"
    const val HARDWARE = "hardware"
    const val SOFTWARE = "software"
}

class MicrophoneInput (
    val config: APPConfig,
    val audioSource: Int = VACAAudioFormat.DEFAULT_AUDIO_SOURCE,
    val sampleRateInHz: Int = VACAAudioFormat.SAMPLE_RATE_HZ,
    val channelConfig: Int = VACAAudioFormat.CHANNELS,
    val audioFormat: Int = VACAAudioFormat.ENCODING,
) : AutoCloseable {

    companion object {
        private val activeMicInputListeners = mutableListOf<() -> Unit>()

        // The active mic is only re-resolved when the input device set changes (see
        // registerDeviceCallback) or a MicrophoneInput (re)starts - there's no periodic recompute -
        // so listeners (MicInputSensor) need an explicit nudge whenever this changes rather than
        // polling it.
        var activeMicInput: String = "None"
            private set(value) {
                field = value
                activeMicInputListeners.forEach { it() }
            }

        fun addActiveMicInputListener(listener: () -> Unit) {
            activeMicInputListeners.add(listener)
        }

        fun removeActiveMicInputListener(listener: () -> Unit) {
            activeMicInputListeners.remove(listener)
        }

        // Resolved AGC/noise-suppression source, updated whenever a MicrophoneInput sets up its
        // audio effects - hardware if the platform effect attached successfully, software if
        // AudioEnhancer's fallback is running instead, unavailable before any mic has started.
        var agcSource: String = AudioEnhancerSource.UNAVAILABLE
            private set
        var nsSource: String = AudioEnhancerSource.UNAVAILABLE
            private set

        // AEC has no software fallback in this app (unlike AGC/NS), so it's purely a hardware
        // capability check - computed once since it can't change at runtime.
        val aecSource: String by lazy {
            if (UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)) {
                AudioEnhancerSource.UNAVAILABLE
            } else if (AcousticEchoCanceler.isAvailable()) {
                AudioEnhancerSource.HARDWARE
            } else {
                AudioEnhancerSource.UNAVAILABLE
            }
        }

        fun getDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
                else -> "Other"
            }
        }
    }

    private var audioRecord: AudioRecord? = null
    private val context = config.context

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    // Guards audioRecord against swaps from the device callback while reads are in flight.
    private val recordLock = Any()

    private val audioEnhancer = AudioEnhancer(sampleRateInHz, context)
    private var totalFramesRead = 0L

    private var audioDSP = AudioDSP()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val deviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { isUsbMic(it) || isBluetoothMic(it) }) {
                Timber.d("USB or Bluetooth microphone connected, updating preferred device")
                updatePreferredDevice()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { isUsbMic(it) || isBluetoothMic(it) }) {
                Timber.d("USB or Bluetooth microphone disconnected, updating preferred device")
                updatePreferredDevice()
            }
        }
    }

    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

    val isRecording
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = synchronized(recordLock) {
        if (audioRecord == null) {
            audioRecord = createAudioRecord()
            setupAudioEffects()
            registerDeviceCallback()
        }

        if (!isRecording) {
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

    fun readShort(bufferSize: Int = VACAAudioFormat.DEFAULT_BUFFER_SIZE_IN_SHORTS, applyEnhancement: Boolean = true): ShortArray = synchronized(recordLock) {
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
        } else if (readCount < 0) {
            Timber.e("AudioRecord read error: $readCount")
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
            resolveAudioSource(),
            sampleRateInHz,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }

        updatePreferredDevice(audioRecord)

        return audioRecord
    }

    private fun registerDeviceCallback() {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    private fun unregisterDeviceCallback() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun isBluetoothMic(device: AudioDeviceInfo): Boolean {
        return device.isSource && (
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        )
    }

    private fun isUsbMic(device: AudioDeviceInfo): Boolean {
        return device.isSource && (
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    private fun hasExternalMic(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { isUsbMic(it) || isBluetoothMic(it) }

    // Built-in mic records via VOICE_RECOGNITION; VOICE_COMMUNICATION's near-field telephony
    // processing attenuates far-field speech. Explicit non-default sources are unchanged.
    private fun resolveAudioSource(): Int =
        if (audioSource == VACAAudioFormat.DEFAULT_AUDIO_SOURCE && !hasExternalMic()) {
            VACAAudioFormat.BUILT_IN_MIC_AUDIO_SOURCE
        } else {
            audioSource
        }

    // Mic selection priority: USB > Bluetooth > built-in.
    private fun updatePreferredDevice(record: AudioRecord? = audioRecord) {
        val currentRecord = record ?: return

        // The audio source is fixed at creation time - recreate the record if the required
        // source changed. Skipped for a freshly created record to avoid recursion.
        if (record === audioRecord && currentRecord.audioSource != resolveAudioSource()) {
            restartAudioRecord()
            return
        }

        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            Timber.d("MIC Device: ${device.productName}, type: ${getDeviceTypeName(device.type)}")
        }

        val usbDevice = devices.firstOrNull { isUsbMic(it) }
        val bluetoothDevice = devices.firstOrNull { isBluetoothMic(it) }

        when {
            usbDevice != null -> selectUsbDevice(currentRecord, usbDevice)
            bluetoothDevice != null -> selectBluetoothDevice(currentRecord, bluetoothDevice)
            else -> selectBuiltInDevice(currentRecord, devices)
        }
    }

    private fun selectUsbDevice(currentRecord: AudioRecord, device: AudioDeviceInfo) {
        stopBluetoothScoIfActive()

        Timber.d("Setting preferred microphone: ${device.productName} (${getDeviceTypeName(device.type)})")
        val success = currentRecord.setPreferredDevice(device)
        Timber.d("setPreferredDevice success: $success")

        activeMicInput = "${device.productName} (USB)"
    }

    private fun selectBluetoothDevice(currentRecord: AudioRecord, device: AudioDeviceInfo) {
        // Check for BLUETOOTH_CONNECT permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Timber.w("BLUETOOTH_CONNECT permission not granted, requesting...")
                BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }

        // Explicitly handle SCO for older devices or specific headset behaviors
        if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            try {
                // Ensure speakerphone is off for SCO to work correctly
                if (audioManager.isSpeakerphoneOn) {
                    audioManager.isSpeakerphoneOn = false
                }

                if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                }

                if (!audioManager.isBluetoothScoOn) {
                    Timber.d("Starting Bluetooth SCO")
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
                Timber.d("Bluetooth SCO state: ${audioManager.isBluetoothScoOn}, mode: ${audioManager.mode}")
            } catch (e: Exception) {
                Timber.e(e, "Error starting Bluetooth SCO")
            }
        }

        Timber.d("Setting preferred microphone: ${device.productName}")
        val success = currentRecord.setPreferredDevice(device)
        Timber.d("setPreferredDevice success: $success")

        activeMicInput = "${device.productName}"
    }

    private fun selectBuiltInDevice(currentRecord: AudioRecord, devices: Array<AudioDeviceInfo>) {
        stopBluetoothScoIfActive()

        val builtInMic = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        activeMicInput = builtInMic?.let { "${it.productName} (Built-in Mic)" } ?: "Built-in Mic"

        // Clear any previously set preferred device (e.g. USB/Bluetooth) so recording
        // falls back to the built-in mic.
        currentRecord.setPreferredDevice(builtInMic)
    }

    // Only reachable via the device callback, registered after a permitted start().
    @SuppressLint("MissingPermission")
    private fun restartAudioRecord() = synchronized(recordLock) {
        val wasRecording = isRecording
        Timber.d("Audio source change required (external mic added/removed), recreating AudioRecord")

        releaseAudioEffects()
        audioRecord?.let {
            if (isRecording) {
                it.stop()
            }
            it.release()
        }
        audioRecord = null

        audioRecord = createAudioRecord()
        setupAudioEffects()
        if (wasRecording) {
            audioRecord?.startRecording()
        }
    }

    private fun stopBluetoothScoIfActive() {
        if (audioManager.isBluetoothScoOn || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            Timber.d("Bluetooth SCO stopped and mode set to NORMAL")
        }
    }

    private fun setupAudioEffects(attachNs: Boolean = true, attachAgc: Boolean = true) {
        val sessionId = audioRecord?.audioSessionId ?: return

        // Catch if issue with audio enhancements and do not load any platform effects -
        // the software AudioEnhancer below still covers AGC/NS on these devices.
        val skipHardwareEffects = UnsupportedFunctionsDevice.isIssueDevice(FunctionClasses.AUDIO_ENHANCEMENTS)

        // Software noise suppression runs only for external mics; on the built-in mic it
        // degrades far-field speech.
        val useSoftwareNs = attachNs && hasExternalMic()

        if (!skipHardwareEffects) {
            // VOICE_RECOGNITION streams don't get the platform's auto-attached echo
            // canceler, so attach it explicitly.
            if (audioRecord?.audioSource == VACAAudioFormat.BUILT_IN_MIC_AUDIO_SOURCE &&
                AcousticEchoCanceler.isAvailable()
            ) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                } catch (e: Exception) {
                    Timber.w("Failed to attach hardware echo canceler: ${e.message}")
                }
            }

            if (attachAgc) {
                if (AutomaticGainControl.isAvailable()) {
                    try {
                        agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                        if (agc != null) agcSource = AudioEnhancerSource.HARDWARE
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
                        if (ns != null) nsSource = AudioEnhancerSource.HARDWARE
                    } catch (e: Exception) {
                        Timber.w("Failed to attach hardware noise suppressor: ${e.message}")
                    }
                }
                if (ns == null) {
                    audioEnhancer.noiseSuppressionEnabled = useSoftwareNs
                    nsSource = if (useSoftwareNs) AudioEnhancerSource.SOFTWARE else AudioEnhancerSource.UNAVAILABLE
                }
            }
        } else {
            Timber.d("Skipping hardware audio enhancements on this device")
            if (attachAgc) {
                audioEnhancer.agcEnabled = true
                agcSource = AudioEnhancerSource.SOFTWARE
            }
            if (attachNs) {
                audioEnhancer.noiseSuppressionEnabled = useSoftwareNs
                nsSource = if (useSoftwareNs) AudioEnhancerSource.SOFTWARE else AudioEnhancerSource.UNAVAILABLE
            }
        }

        audioEnhancer.reset()
        Timber.d(
            "Audio enhancement - AGC: ${agcSource}, NS: ${nsSource}, " +
                "AEC: ${if (aec?.enabled == true) "hardware (app attached)" else "platform managed"}, " +
                "source: ${audioRecord?.audioSource}"
        )
    }

    private fun releaseAudioEffects() {
        agc?.release()
        agc = null

        ns?.release()
        ns = null

        aec?.release()
        aec = null

        audioEnhancer.agcEnabled = false
        audioEnhancer.noiseSuppressionEnabled = false
    }

    override fun close() {
        unregisterDeviceCallback()
        audioEnhancer.release()

        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            Timber.d("Bluetooth SCO stopped and mode set to NORMAL in close()")
        }

        synchronized(recordLock) {
            releaseAudioEffects()

            audioRecord?.let {
                if (isRecording) {
                    it.stop()
                }
                it.release()
                audioRecord = null
            }
        }
    }
}