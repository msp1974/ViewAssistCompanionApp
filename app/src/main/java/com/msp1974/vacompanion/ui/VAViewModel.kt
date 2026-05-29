  package com.msp1974.vacompanion.ui

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.ContextCompat.getString
import androidx.datastore.core.Closeable
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.data.NetworkStatus
import com.msp1974.vacompanion.data.NetworkStatusManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.settings.WifiLockMode
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.satellite.AudioRouteOption
import com.msp1974.vacompanion.utils.Network
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

class VADialog(
    val title: String = "AlertDialog",
    val message: String = "Message",
    val confirmText: String = "Yes",
    val dismissText: String = "No",
    val confirmCallback: () -> Unit,
    val dismissCallback: () -> Unit
) {
    fun onConfirm() {
        confirmCallback()
    }

    fun onDismiss() {
        dismissCallback()
    }
}

data class UpdateStatus(
    var updateAvailable: Boolean = false,
    var availableVersion: String = "0.0.0"
)

data class PermissionsStatus(
    var hasCorePermissions: Boolean = false,
    var hasOptionalPermissions: Boolean = false
)

data class DiagnosticInfo(
    var show: Boolean = false,
    var engine: String = "",
    var muted: Boolean = false,
    var audioLevel: Float = 0f,
    var detectionThreshold: Float = 0f,
    var detectionLevel: Float = 0f,
    var mode: AudioRouteOption = AudioRouteOption.NONE,
    var wakeWord: String = "",
    var vadDetection: Boolean = false
)



data class State(
    val statusMessage: String = "",
    var orientation: Int = Configuration.ORIENTATION_LANDSCAPE,

    var launchOnBoot: Boolean = true,
    var satelliteRunning: Boolean = false,
    var swipeRefreshEnabled: Boolean = false,
    var darkMode: Boolean = false,
    var isDND: Boolean = false,
    var screenBlank: Boolean = true,

    var appInfo: Map<String, String> = mapOf(),
    var diagnosticInfo: DiagnosticInfo = DiagnosticInfo(),

    var showAlertDialog: Boolean = false,
    var alertDialog: VADialog? = null,
    var permissions: PermissionsStatus = PermissionsStatus(),
    var updates: UpdateStatus = UpdateStatus(),
    var webViewPageLoadingStage: PageLoadingStage = PageLoadingStage.NOT_STARTED,
    var showUUIDChangeDialog: Boolean = false,
    var isNetworkConnected: Boolean = true
    )

@HiltViewModel
class VAViewModel @Inject constructor(
    application: Application,
    val config: APPConfig,
    val networkStatusManager: NetworkStatusManager
): ViewModelBase(application), EventListener, Closeable {

    private val _vacaState = MutableStateFlow(State())
    val vacaState: StateFlow<State> = _vacaState.asStateFlow()

    var resources: Resources = application.resources
    var permissions: Permissions = Permissions(application.applicationContext, config)
    val network = Network(application.applicationContext)
    private var currentNetworkStatus = NetworkStatus.Available

    val changedNetworkStatus = networkStatusManager.networkStatus
        .dropWhile { it.status == NetworkStatus.Available }
        .shareIn(viewModelScope, SharingStarted.Eagerly, 1)

    init {
        _vacaState.value = State()

        config.eventBroadcaster.addListener(this)
        initValues()
        applyWifiLockMode(currentNetworkStatus, "initial")
        buildAppInfo()
        startNetworkMonitor()
    }

    fun initValues() {
        _vacaState.update { currentState ->
            currentState.copy(
                launchOnBoot = config.startOnBoot,
                swipeRefreshEnabled = config.swipeRefresh,
                // TODO: Move this into a dedicated configuration observer pattern to handle live updates.
                diagnosticInfo = currentState.diagnosticInfo.copy(
                    show = config.diagnosticsEnabled,
                    engine = config.wakeWordEngine,
                    muted = config.isMuted,
                )
            )
        }
    }

    override fun close() {
        releaseResources("view_model_close")
    }

    override fun onCleared() {
        releaseResources("view_model_cleared")
        super.onCleared()
    }

    private fun releaseResources(reason: String) {
        network.releaseWifiLock(reason)
        config.eventBroadcaster.removeListener(this)
    }

    fun startNetworkMonitor() {
        viewModelScope.launch(Dispatchers.Default) {
            changedNetworkStatus.collect {
                onNetworkStateChange(it.status)
            }
        }
    }

    var launchOnBoot: Boolean
        get() = config.startOnBoot
        set(value) {
            _vacaState.update { currentState ->
                currentState.copy(
                    launchOnBoot = value
                )
            }
            config.startOnBoot = value
        }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "isMuted" -> {
                val isMuted = event.newValue as Boolean
                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = currentState.diagnosticInfo.copy(
                            muted = isMuted,
                            audioLevel = 0f,
                            detectionLevel = 0f,
                            mode = if (isMuted || config.wakeWord == "none") AudioRouteOption.NONE else AudioRouteOption.DETECT
                        )
                    )
                }
            }
            "wakeWord" -> {
                _vacaState.update { currentState ->
                    val wakeWord = event.newValue as String
                    currentState.copy(
                        diagnosticInfo = currentState.diagnosticInfo.copy(
                            wakeWord = wakeWord,
                        )
                    )
                }
            }
            "wakeWordEngine" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = currentState.diagnosticInfo.copy(
                            engine = event.newValue as String
                        )
                    )
                }
            }
            "pairedDeviceID" -> buildAppInfo()
            "darkMode" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        darkMode = event.newValue as Boolean
                    )
                }
            }
            "swipeRefresh" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        swipeRefreshEnabled = event.newValue as Boolean
                    )
                }
            }
            "doNotDisturb" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        isDND = event.newValue as Boolean
                    )
                }
            }
            "diagnosticsEnabled" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = _vacaState.value.diagnosticInfo.copy(
                            show = event.newValue as Boolean
                        )
                    )
                }
            }
            "wifiLockMode", "wifi_lock_mode" -> applyWifiLockMode(currentNetworkStatus, "setting_changed")
            "diagnosticStats" -> {
                val data = event.newValue as DiagnosticInfo
                consumed = false  //Do not log event as very numerous

                _vacaState.update { currentState ->
                    currentState.copy(
                        diagnosticInfo = data
                    )
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("ViewModel - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun showUpdateDialog(alertDialog: VADialog) {
        val alert = VADialog(
            title = alertDialog.title,
            message = alertDialog.message,
            confirmText = alertDialog.confirmText,
            dismissText = alertDialog.dismissText,
            confirmCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.confirmCallback()
            },
            dismissCallback = {
                _vacaState.update { currentState ->
                    currentState.copy(
                        alertDialog = null
                    )
                }
                alertDialog.dismissCallback()
            },
        )

        _vacaState.update { currentState ->
            currentState.copy(
                alertDialog = alert,
            )
        }
    }

    fun setSatelliteRunning(isRunning: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                satelliteRunning = isRunning
            )
        }
    }

    fun setStatusMessage(statusMessage: String) {
        _vacaState.update { currentState ->
            currentState.copy(
                statusMessage = statusMessage
            )
        }
    }

    fun setScreenBlank(screenOn: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                screenBlank = screenOn
            )
        }
    }

    fun setWebViewPageLoadingState(stage: PageLoadingStage) {
        Timber.d("WebView page loading state: $stage")
        _vacaState.update { currentState ->
            currentState.copy(
                webViewPageLoadingStage = stage
            )
        }
    }

    fun onNetworkStateChange(status: NetworkStatus) {
        Timber.d("Network status: $status")
        currentNetworkStatus = status
        _vacaState.update { currentState ->
            currentState.copy(
                isNetworkConnected = status == NetworkStatus.Available
            )
        }
        applyWifiLockMode(status, "network_status_$status")
        when (status) {
            NetworkStatus.Unavailable  -> setStatusMessage(application.getString(R.string.status_waiting_for_network))
            NetworkStatus.Available -> setStatusMessage(getString(application.applicationContext, R.string.status_waiting_for_connection))
        }
        buildAppInfo()
    }

    private fun applyWifiLockMode(status: NetworkStatus, reason: String) {
        Timber.i("WiFi lock mode: ${config.wifiLockMode} reason=$reason networkStatus=$status")
        when (config.wifiLockMode) {
            WifiLockMode.OFF -> {
                Timber.i("WiFi lock acquire skipped: mode=OFF reason=$reason")
                network.releaseWifiLock("mode_off_$reason")
            }
            WifiLockMode.RECONNECT_ONLY -> {
                when (status) {
                    NetworkStatus.Unavailable -> network.acquireWifiLockBounded("network_unavailable_$reason")
                    NetworkStatus.Available -> network.releaseWifiLock("network_available_$reason")
                }
            }
            WifiLockMode.ALWAYS -> network.acquireWifiLockUnboundedForAlwaysMode("always_mode_$reason")
        }
    }

    private fun buildAppInfo() {
       _vacaState.update { currentState ->
            currentState.copy(
                appInfo = mapOf(
                    "Version" to config.version,
                    "IP Address" to (if (Helpers.isNetworkAvailable(config.context)) Helpers.getIpv4HostAddress() else ""),
                    "Port" to APPConfig.SERVER_PORT.toString(),
                    "UUID" to config.uuid,
                    "Paired to" to config.pairedDeviceID,
                )
           )
       }
    }

    fun checkForUpdate() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.VERSION_MISMATCH)
    }

    fun requestPermissions() {
        BroadcastSender.sendBroadcast(config.context, BroadcastSender.REQUEST_MISSING_PERMISSIONS)
    }

    fun setPermissionsStatus(core: Boolean, optional: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                permissions = PermissionsStatus(core, optional)
            )
        }
    }

    fun showClearPairedDeviceDialog() {
        val d = VADialog(
            title = "Clear Paired Device Entry",
            message = "This will delete the currently paired Home Assistant server and allow another server to connect and pair to this device.",
            confirmText = "Confirm",
            dismissText = "Cancel",
            confirmCallback = {
                clearPairedDevice()
            },
            dismissCallback = {}
        )
        showUpdateDialog(d)
    }

    private fun clearPairedDevice() {
        config.pairedDeviceID = ""
        config.accessToken = ""
        config.refreshToken = ""
        config.tokenExpiry = 0
    }

    fun showUUIDChangeDialog(show: Boolean = true) {
        _vacaState.update { currentState ->
            currentState.copy(
                showUUIDChangeDialog = show
            )
        }
    }

    fun setUUID(uuid: String = "") {
        // TODO: Add validation
        if (uuid != "" && uuid != config.uuid) {
            config.uuid = uuid
            showUUIDChangeDialog(false)
            clearPairedDevice()
            buildAppInfo()
            config.eventBroadcaster.notifyEvent(Event("restartZeroconf", "", ""))
        }
    }
}

