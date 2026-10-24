package com.msp1974.vacompanion.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import timber.log.Timber

private const val DEVICE_CHANGE_DEBOUNCE_MS = 1000L
private const val SCO_CONNECT_TIMEOUT_MS = 8000L
private const val SCO_RECONNECT_MIN_INTERVAL_MS = 3000L

/**
 * The currently preferred microphone: the device to route to (null = built-in) and the
 * AudioSource that must be used to capture from it.
 */
data class PreferredMicrophone(val device: AudioDeviceInfo?, val audioSource: Int)

/**
 * Owns microphone device selection (USB > Bluetooth > Wired > built-in), the AudioDeviceCallback that
 * tracks mic connect/disconnect, permission handling, and Bluetooth SCO start/stop/recovery.
 *
 * The preferred mic is tracked as internal state ([preferredDevice]), not recomputed on demand:
 * [start] seeds it to the built-in mic, then evaluates what's actually connected. A candidate
 * only ever becomes preferred - and [onPreferredMicrophoneChanged] only ever fires - once it's
 * confirmed usable: for Bluetooth that means BLUETOOTH_CONNECT is granted (requested and waited
 * for otherwise) and Bluetooth SCO has actually connected. MicrophoneInput is deliberately blind
 * to all of this: it calls [getPreferredMicrophone] to learn what to capture from and with what
 * AudioSource, and [applyPreferredDevice] to route an AudioRecord to it.
 */
class AudioInRouter(
    private val context: Context,
    private val deviceManager: DeviceManager,
    private val onPreferredMicrophoneChanged: () -> Unit,
) : EventListener {
    companion object {
        private val activeMicInputListeners = mutableListOf<() -> Unit>()

        // Only re-resolved when the input device set changes (see deviceCallback) or a
        // MicrophoneInput (re)starts - there's no periodic recompute - so listeners
        // (MicInputSensor) need an explicit nudge whenever this changes rather than polling it.
        // Id of the device behind activeMicInput (AudioDeviceInfo.getId), or -1 if none is in use.
        // Set before activeMicInput so a listener told of the change sees both.
        @Volatile
        var activeMicInputId: Int = -1
            private set

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

        // Covers both input (mic-routing) and output types - also used by DeviceHardware to
        // name entries in audioInputs/audioOutputs, so this is the single source of truth for
        // AudioDeviceInfo.TYPE_* -> friendly name across the app.
        fun getDeviceTypeName(type: Int): String {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in Earpiece"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Built-in Speaker (Safe Media Volume)"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Analog"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line Digital"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
                AudioDeviceInfo.TYPE_DOCK -> "Dock"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
                AudioDeviceInfo.TYPE_FM -> "FM"
                AudioDeviceInfo.TYPE_FM_TUNER -> "FM Tuner"
                AudioDeviceInfo.TYPE_TV_TUNER -> "TV Tuner"
                AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
                AudioDeviceInfo.TYPE_AUX_LINE -> "Aux Line"
                AudioDeviceInfo.TYPE_IP -> "IP"
                AudioDeviceInfo.TYPE_BUS -> "Bus"
                AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing Aid"
                AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote Submix"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
                AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE Speaker"
                AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE Broadcast"
                else -> "Other"
            }
        }

        // Types AudioManager reports under GET_DEVICES_INPUTS that are genuinely usable as a
        // general-purpose microphone - as opposed to tuner inputs, telephony uplink, loopback
        // capture, etc., which also show up there but aren't something MicInputSensor should
        // offer as a "mic".
        fun isUsableMicrophoneType(type: Int): Boolean {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID -> true
                else -> false
            }
        }

        // Types AudioManager reports under GET_DEVICES_OUTPUTS that are genuinely usable as a
        // general-purpose playback output - as opposed to tuner loopback, telephony, internal
        // routing buses, etc., which also show up there but aren't a real "speaker" to a user.
        fun isUsableOutputType(type: Int): Boolean {
            return when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
                AudioDeviceInfo.TYPE_DOCK,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                AudioDeviceInfo.TYPE_HEARING_AID -> true
                else -> false
            }
        }

        fun isBluetoothMic(device: AudioDeviceInfo): Boolean {
            return device.isSource && (
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            )
        }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Multiple mics can connect/disconnect in the same burst (e.g. a USB hub, or a Bluetooth
    // device that reports several devices at once), each firing its own callback - debounce so
    // a burst only triggers one evaluation. Also reused as the general-purpose delayed-work
    // handler for the SCO connect timeout below.
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val evaluateRunnable = Runnable { evaluatePreferredMicrophone() }
    private fun scheduleEvaluate() {
        debounceHandler.removeCallbacks(evaluateRunnable)
        debounceHandler.postDelayed(evaluateRunnable, DEVICE_CHANGE_DEBOUNCE_MS)
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { isUsbMic(it) || isBluetoothMic(it) || isWiredMic(it) }) {
                Timber.d("USB, Bluetooth or wired microphone connected, re-evaluating preferred device")
                scheduleEvaluate()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { isUsbMic(it) || isBluetoothMic(it) || isWiredMic(it) }) {
                Timber.d("USB, Bluetooth or wired microphone disconnected, stopping SCO and re-evaluating preferred device")
                stopSco()
                scheduleEvaluate()
            }
        }
    }

    fun start() {
        preferredDevice = null
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        deviceManager.config.eventBroadcaster.addListener(this)
        evaluatePreferredMicrophone()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        deviceManager.config.eventBroadcaster.removeListener(this)
        debounceHandler.removeCallbacks(evaluateRunnable)
        unregisterPermissionGrantedReceiver()
        preferredDevice = null
        stopSco()
    }

    // bluetoothMicEnabled is SharedPreferences-backed, so it comes through as a raw pref-key
    // event (see APPConfig.onSharedPreferenceChangedListener) rather than a typed Delegates.
    // observable one - re-evaluate on any change, whichever way it flips.
    override fun onEventTriggered(event: Event) {
        if (event.eventName == "bluetooth_mic_enabled") {
            Timber.d("Bluetooth mic enabled setting changed, re-evaluating preferred microphone")
            if (!deviceManager.config.bluetoothMicEnabled && preferredDevice?.let { isBluetoothMic(it) } == true) {
                stopSco()
            }
            scheduleEvaluate()
        } else if (event.eventName == "audioInputId") {
            Timber.d("Preferred mic input id setting changed, re-evaluating preferred microphone")
            scheduleEvaluate()
        }
    }

    private fun isUsbMic(device: AudioDeviceInfo): Boolean {
        return device.isSource && (
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    private fun isWiredMic(device: AudioDeviceInfo): Boolean {
        return device.isSource && device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
    }

    // Built-in/USB mic uses VOICE_RECOGNITION, except on AndroidThings devices where that source
    // isn't supported and MIC is used instead. Bluetooth needs VOICE_COMMUNICATION as the
    // AudioSource for SCO routing to engage correctly.
    private fun resolveAudioSource(device: AudioDeviceInfo?): Int {
        return when {
            device != null && isBluetoothMic(device) -> VACAAudioFormat.BLUETOOTH_AUDIO_SOURCE
            deviceManager.deviceInfo.software.isAndroidThings -> VACAAudioFormat.FALLBACK_AUDIO_SOURCE
            else -> VACAAudioFormat.DEFAULT_AUDIO_SOURCE
        }
    }

    // --- Preferred mic evaluation ---

    // The confirmed preferred mic - set only once a candidate is known usable (see
    // switchPreferredDevice()). null means built-in.
    private var preferredDevice: AudioDeviceInfo? = null

    // Live scan of what's actually connected right now, in priority order: USB > Bluetooth >
    // Wired > built-in. This is a candidate to evaluate, not necessarily what's preferred yet.
    private fun resolveBestAvailableDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            Timber.d("MIC Device: ${device.productName}, type: ${getDeviceTypeName(device.type)}")
        }

        // A forced device (audioInputId >= 0) takes priority over the auto-selection logic
        // below, provided it's actually connected - otherwise fall back to auto-selection.
        val forcedId = deviceManager.config.audioInputId
        if (forcedId >= 0) {
            val forced = devices.firstOrNull { it.id == forcedId }
            if (forced != null) return forced
            Timber.w("Configured mic input id=$forcedId not currently connected - falling back to automatic selection")
        }

        return devices.firstOrNull { isUsbMic(it) }
            ?: devices.firstOrNull { deviceManager.config.bluetoothMicEnabled && isBluetoothMic(it) }
            ?: devices.firstOrNull { isWiredMic(it) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    // Resolves the best available device and, if it's Bluetooth, makes sure it's actually
    // usable (permission + SCO) before adopting it - see trySwitchToBluetooth(). Anything else
    // has nothing to wait on, so it's applied straight away.
    private fun evaluatePreferredMicrophone() {
        val candidate = resolveBestAvailableDevice()
        if (candidate != null && isBluetoothMic(candidate)) {
            trySwitchToBluetooth(candidate)
        } else {
            stopSco()
            switchPreferredDevice(candidate)
        }
    }

    // Bluetooth mics need BLUETOOTH_CONNECT (Android 12+) granted and Bluetooth SCO actually
    // connected before becoming preferred. Runtime permission grants don't deliver a callback
    // we can listen to directly, so MainActivity broadcasts BroadcastSender.PERMISSION_GRANTED
    // once the user responds to the request, and that re-runs the evaluation from scratch.
    private fun trySwitchToBluetooth(device: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("BLUETOOTH_CONNECT permission not granted for ${device.productName}, requesting...")
            registerPermissionGrantedReceiver()
            BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        // BLE_HEADSET (Android 12+) doesn't need explicit SCO management.
        if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            switchPreferredDevice(device)
            return
        }

        startSco { success ->
            if (success) {
                switchPreferredDevice(device)
            } else {
                Timber.w("Failed to start Bluetooth SCO for ${device.productName}, staying on ${preferredDevice?.productName ?: "Built-in Mic"}")
            }
        }
    }

    private fun switchPreferredDevice(device: AudioDeviceInfo?) {
        if (device?.id == preferredDevice?.id) return

        preferredDevice = device
        Timber.d("Preferred microphone changed to: ${device?.productName ?: "Built-in Mic"}")
        onPreferredMicrophoneChanged()
    }

    private var permissionGrantedReceiver: BroadcastReceiver? = null

    private fun registerPermissionGrantedReceiver() {
        if (permissionGrantedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getStringExtra("extra") == Manifest.permission.BLUETOOTH_CONNECT) {
                    Timber.d("BLUETOOTH_CONNECT permission granted, re-evaluating preferred microphone")
                    unregisterPermissionGrantedReceiver()
                    evaluatePreferredMicrophone()
                }
            }
        }
        permissionGrantedReceiver = receiver
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter(BroadcastSender.PERMISSION_GRANTED))
    }

    private fun unregisterPermissionGrantedReceiver() {
        permissionGrantedReceiver?.let {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(it)
            permissionGrantedReceiver = null
        }
    }

    // Single entry point MicrophoneInput uses to learn what to capture from - reflects the
    // confirmed preferredDevice, not a fresh scan, so it's always consistent with what
    // permission/SCO checks have actually verified.
    fun getPreferredMicrophone(): PreferredMicrophone {
        return PreferredMicrophone(preferredDevice, resolveAudioSource(preferredDevice))
    }

    // Routes the given AudioRecord to the given device (null = built-in) and updates the
    // diagnostics label. Permission checks and Bluetooth SCO were already handled when this
    // device became preferred - see evaluatePreferredMicrophone() - so this is pure AudioRecord
    // plumbing. Pass the device from the same getPreferredMicrophone() result used to create
    // the AudioRecord.
    fun applyPreferredDevice(record: AudioRecord, device: AudioDeviceInfo?) {
        Timber.d("Setting preferred microphone: ${device?.productName ?: "Built-in Mic"}")
        val success = record.setPreferredDevice(device)
        Timber.d("setPreferredDevice success: $success")

        activeMicInputId = (device
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC })?.id ?: -1
        activeMicInput = when {
            device == null -> "Built-in Mic"
            isUsbMic(device) -> "${device.productName} (USB)"
            isBluetoothMic(device) -> device.productName.toString()
            isWiredMic(device) -> "${device.productName} (Wired)"
            else -> "${device.productName} (Built-in Mic)"
        }
    }

    // --- Bluetooth SCO / communication device ---

    // Android 12+ (API 31) replaces manual SCO management with
    // AudioManager.setCommunicationDevice()/clearCommunicationDevice(), which reports success
    // synchronously, and the SCO broadcast with OnCommunicationDeviceChangedListener. Below
    // API 31, startBluetoothSco() is documented to take up to SCO_CONNECT_TIMEOUT_MS to actually
    // connect on some devices, so success there is confirmed via the SCO state broadcast rather
    // than assumed.
    private var scoStateReceiver: BroadcastReceiver? = null
    private var communicationDeviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var lastScoReconnectAttemptAtMs = 0L
    private var scoWanted = false

    // Attempts to engage the Bluetooth mic, reporting success/failure via onResult once known
    // (synchronously on Android 12+, otherwise once the state broadcast confirms it or it times
    // out).
    private fun startSco(onResult: (Boolean) -> Unit) {
        scoWanted = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            onResult(engageCommunicationDevice())
        } else {
            engageLegacySco(onResult)
        }
    }

    fun stopSco() {
        scoWanted = false
        debounceHandler.removeCallbacks(scoConnectTimeoutRunnable)
        pendingScoResult = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            disengageCommunicationDevice()
        } else {
            disengageLegacySco()
        }
    }

    // On some Android 9 devices/headsets, playing output audio (e.g. TTS) while the mic's
    // Bluetooth link is active causes it to silently drop without the preferred device actually
    // disconnecting - so this retries in place, rate-limited, rather than going through a full
    // re-evaluation (which manual toggling of SCO is what fixes on-device).
    private fun attemptScoReconnect() {
        val device = preferredDevice ?: return
        if (!isBluetoothMic(device)) return

        val now = System.currentTimeMillis()
        if (now - lastScoReconnectAttemptAtMs < SCO_RECONNECT_MIN_INTERVAL_MS) {
            Timber.w("Skipping Bluetooth SCO reconnect, already attempted recently")
            return
        }
        lastScoReconnectAttemptAtMs = now

        Timber.w("Bluetooth SCO disconnected unexpectedly while mic active, attempting to reconnect")
        startSco { success ->
            if (!success) {
                Timber.w("Failed to reconnect Bluetooth SCO for ${device.productName}")
            }
        }
    }

    // --- Android 12+ ---

    @RequiresApi(Build.VERSION_CODES.S)
    private fun engageCommunicationDevice(): Boolean {
        return try {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (device == null) {
                Timber.w("No Bluetooth communication device available")
                return false
            }

            registerCommunicationDeviceListener()

            val success = audioManager.setCommunicationDevice(device)
            Timber.d("setCommunicationDevice(${device.productName}) success: $success")
            success
        } catch (e: Exception) {
            Timber.e(e, "Error setting Bluetooth communication device")
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun disengageCommunicationDevice() {
        unregisterCommunicationDeviceListener()
        audioManager.clearCommunicationDevice()
        Timber.d("Cleared communication device")
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerCommunicationDeviceListener() {
        if (communicationDeviceListener != null) return
        val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
            if (scoWanted && device?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                Timber.w("Bluetooth communication device lost unexpectedly while mic active, restarting")
                attemptScoReconnect()
            }
        }
        communicationDeviceListener = listener
        audioManager.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterCommunicationDeviceListener() {
        communicationDeviceListener?.let {
            audioManager.removeOnCommunicationDeviceChangedListener(it)
            communicationDeviceListener = null
        }
    }

    // --- Below Android 12 ---

    // Set while waiting for the state receiver to confirm a startBluetoothSco() call actually
    // connected (or for the timeout below to give up on it).
    private var pendingScoResult: ((Boolean) -> Unit)? = null
    private val scoConnectTimeoutRunnable = Runnable {
        Timber.w("Timed out after ${SCO_CONNECT_TIMEOUT_MS}ms waiting for Bluetooth SCO to connect")
        val callback = pendingScoResult
        pendingScoResult = null
        callback?.invoke(false)
    }

    private fun engageLegacySco(onResult: (Boolean) -> Unit) {
        try {
            // Ensure speakerphone is off for SCO to work correctly
            audioManager.isSpeakerphoneOn = false

            if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }

            registerScoStateReceiver()

            if (audioManager.isBluetoothScoOn) {
                onResult(true)
                return
            }

            pendingScoResult = onResult
            debounceHandler.postDelayed(scoConnectTimeoutRunnable, SCO_CONNECT_TIMEOUT_MS)

            Timber.d("Starting Bluetooth SCO, waiting up to ${SCO_CONNECT_TIMEOUT_MS}ms for it to connect")
            audioManager.startBluetoothSco()
        } catch (e: Exception) {
            Timber.e(e, "Error starting Bluetooth SCO")
            onResult(false)
        }
    }

    private fun disengageLegacySco() {
        unregisterScoStateReceiver()
        if (audioManager.isBluetoothScoOn || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            Timber.d("Bluetooth SCO stopped and mode set to NORMAL")
        }
    }

    private fun registerScoStateReceiver() {
        if (scoStateReceiver != null) return
        scoStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR
                )
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Timber.d("Bluetooth SCO connected")
                        audioManager.isBluetoothScoOn = true
                        debounceHandler.removeCallbacks(scoConnectTimeoutRunnable)
                        val callback = pendingScoResult
                        pendingScoResult = null
                        callback?.invoke(true)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        debounceHandler.removeCallbacks(scoConnectTimeoutRunnable)
                        val callback = pendingScoResult
                        pendingScoResult = null
                        if (callback != null) {
                            Timber.w("Bluetooth SCO failed to connect")
                            callback.invoke(false)
                        } else if (scoWanted) {
                            attemptScoReconnect()
                        }
                    }
                }
            }
        }
        context.registerReceiver(scoStateReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
    }

    private fun unregisterScoStateReceiver() {
        scoStateReceiver?.let {
            context.unregisterReceiver(it)
            scoStateReceiver = null
        }
    }
}
