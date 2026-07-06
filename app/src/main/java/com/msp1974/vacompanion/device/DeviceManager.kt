package com.msp1974.vacompanion.device

import android.content.Context
import com.msp1974.vacompanion.data.NetworkInfo
import com.msp1974.vacompanion.data.NetworkStatusManager
import com.msp1974.vacompanion.device.authentication.AuthenticationManager
import com.msp1974.vacompanion.device.authentication.Token
import com.msp1974.vacompanion.device.info.DeviceInfo
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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class WyomingServerStatus(
    val state: ServerState = ServerState.STOPPED,
    val connected: Boolean = false,
    val satelliteRunning: Boolean = false
)

data class Status(
    val network: NetworkInfo = NetworkInfo(),
    val wyoming: WyomingServerStatus = WyomingServerStatus(),
    val sensors: SensorState = SensorState(),
    val sessionToken: Token? = null,

    val isDND: Boolean = false,
    val darkMode: Boolean = false,
    val isMuted: Boolean = false,
    val webViewPageLoadingStage: PageLoadingStage = PageLoadingStage.NOT_STARTED,
    val cameraStreamActive: Boolean = false,
    val screenBlank: Boolean = true
)

@Singleton
class DeviceManager @Inject constructor(
    val context: Context,
) {
    private val job = SupervisorJob()
    val deviceManagerScope = CoroutineScope(Dispatchers.Default + job)

    val config: APPConfig = APPConfig(context)
    val deviceInfo: DeviceInfo = DeviceInfo(context)
    val networkStatusManager: NetworkStatusManager = NetworkStatusManager(context)
    val sensorsManager = SensorManager(context, this)
    val authenticationManager: AuthenticationManager = AuthenticationManager(config)

    // Network status handling
    private val _networkStatus = MutableStateFlow(networkStatusManager.networkInfo)
    val networkStatus: StateFlow<NetworkInfo> = _networkStatus.asStateFlow()

    // Server (Wyoming) connection status handling
    private val _serverStatus = MutableStateFlow(WyomingServerStatus())
    val serverStatus: StateFlow<WyomingServerStatus> = _serverStatus.asStateFlow()

    // Sensors - Holds only the latest updates for Wyoming
    private val _sensors = MutableStateFlow(emptyMap<String, Any>())
    val sensors: StateFlow<Map<String, Any>> = _sensors.asStateFlow()

    // Status class to hold all connection-related states - includes full sensor state
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    val isActive = _serverStatus.value.connected && _serverStatus.value.satelliteRunning

    init {
        runListeners()
    }

    private fun runListeners() {
        deviceManagerScope.launch {
            networkStatusManager.getNetworkStatus().collect { info ->
                _networkStatus.value = info
                _status.update { it.copy(network = info) }
            }
        }
        
        // Collect full state for internal Status (UI)
        deviceManagerScope.launch {
            sensorsManager.sensorState.collect { state ->
                _status.update { it.copy(sensors = state) }
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
