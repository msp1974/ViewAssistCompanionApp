package com.msp1974.vacompanion.device

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.msp1974.vacompanion.audio.AudioInRouter
import com.msp1974.vacompanion.device.authentication.AuthenticationManager
import com.msp1974.vacompanion.device.authentication.Token
import com.msp1974.vacompanion.device.info.DeviceInfo
import com.msp1974.vacompanion.device.sensors.NetworkState
import com.msp1974.vacompanion.device.sensors.SensorManager
import com.msp1974.vacompanion.device.sensors.SensorState
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.wyoming.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class WyomingServerStatus(
    val state: ServerState = ServerState.STOPPED,
    val connected: Boolean = false,
    val satelliteRunning: Boolean = false
)

data class Status(
    val network: NetworkState = NetworkState(),
    val wyoming: WyomingServerStatus = WyomingServerStatus(),
    val sensors: SensorState = SensorState(),
    val sessionToken: Token? = null,

    val isDND: Boolean = false,
    val darkMode: Boolean = false,
    val isMuted: Boolean = false,
    val webViewPageLoadingStage: PageLoadingStage = PageLoadingStage.NOT_STARTED,
    val cameraStreamActive: Boolean = false,
    val screenBlank: Boolean = true,
    val bluetoothMicConnected: Boolean = false
)

@Singleton
class DeviceManager @Inject constructor(
    val context: Context,
) {
    private val job = SupervisorJob()
    val deviceManagerScope = CoroutineScope(Dispatchers.Default + job)

    val config: APPConfig = APPConfig(context)
    val deviceInfo: DeviceInfo = DeviceInfo(context)
    val sensorsManager = SensorManager(context, this)
    val authenticationManager: AuthenticationManager = AuthenticationManager(this.config)

    // Network status handling
    private val _networkStatus = MutableStateFlow(NetworkState())
    val networkStatus: StateFlow<NetworkState> = _networkStatus.asStateFlow()

    // Server (Wyoming) connection status handling
    private val _serverStatus = MutableStateFlow(WyomingServerStatus())
    val serverStatus: StateFlow<WyomingServerStatus> = _serverStatus.asStateFlow()

    // Sensors - Holds only the latest updates for Wyoming
    private val _sensors = MutableStateFlow(emptyMap<String, Any>())
    val sensors: StateFlow<Map<String, Any>> = _sensors.asStateFlow()

    // Status class to hold all connection-related states - includes full sensor state
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Tracks whether a Bluetooth mic is physically connected, independent of
    // config.bluetoothMicEnabled, so the UI can hide the quick-actions toggle when there's
    // nothing to route to while still persisting the enabled/disabled preference underneath.
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothMicDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateBluetoothMicConnected()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateBluetoothMicConnected()
    }

    private fun updateBluetoothMicConnected() {
        val connected = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .any { AudioInRouter.isBluetoothMic(it) }
        _status.update { it.copy(bluetoothMicConnected = connected) }
    }

    init {
        runListeners()
        audioManager.registerAudioDeviceCallback(bluetoothMicDeviceCallback, null)
        updateBluetoothMicConnected()
    }

    private fun runListeners() {
        // Collect full state for internal Status (UI)
        deviceManagerScope.launch {
            sensorsManager.sensorState.collect { state ->
                _status.update { it.copy(sensors = state) }
                state.network?.let { info ->
                    _networkStatus.value = info
                    _status.update { it.copy(network = info) }
                }
            }
        }

        // Collect only updates for Wyoming message data
        deviceManagerScope.launch {
            sensorsManager.sensorUpdates.collect { updates ->
                _sensors.value = updates
            }
        }
    }

    fun updateServerState(state: WyomingServerStatus) {
        _serverStatus.value = state
        _status.update { it.copy(wyoming = state) }
    }

    fun updateDNDStatus(enabled: Boolean) {
        _status.update { it.copy(isDND = enabled) }
    }

    fun updateDarkModeStatus(enabled: Boolean) {
        _status.update { it.copy(darkMode = enabled) }
    }

    fun updateMuteStatus(enabled: Boolean) {
        _status.update { it.copy(isMuted = enabled) }
    }

    fun updateWebViewPageLoadingStage(stage: PageLoadingStage) {
        _status.update { it.copy(webViewPageLoadingStage = stage) }
    }

    fun updateCameraStreamActive(active: Boolean) {
        _status.update { it.copy(cameraStreamActive = active) }
    }

    fun updateScreenBlankStatus(blank: Boolean) {
        _status.update { it.copy(screenBlank = blank) }
    }
}
