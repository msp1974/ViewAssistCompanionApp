package com.msp1974.vacompanion.ui

import android.content.res.Configuration
import android.content.res.Resources
import androidx.lifecycle.ViewModel
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.service.AudioRouteOption
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.utils.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber


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
    var showUUIDChangeDialog: Boolean = false
    )

class VAViewModel: ViewModel(), EventListener {
    private val log = Logger()

    private val _vacaState = MutableStateFlow(State())
    val vacaState: StateFlow<State> = _vacaState.asStateFlow()

    var config: APPConfig? = null
    var resources: Resources? = null
    var permissions: Permissions? = null


    init {
        _vacaState.value = State()
    }

    fun bind(config: APPConfig, resources: Resources) {
        this.config = config
        this.resources = resources
        this.permissions = Permissions(config.context)
        this.config?.eventBroadcaster?.addListener(this)

        initValues()
        buildAppInfo()
    }

    fun initValues() {
        _vacaState.update { currentState ->
            currentState.copy(
                launchOnBoot = config!!.startOnBoot,
                swipeRefreshEnabled = config!!.swipeRefresh
            )
        }
    }

    var launchOnBoot: Boolean
        get() = config!!.startOnBoot
        set(value) {
            _vacaState.update { currentState ->
                currentState.copy(
                    launchOnBoot = value
                )
            }
            config!!.startOnBoot = value
        }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
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
            log.d("ViewModel - Event: ${event.eventName} - ${event.newValue}")
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

    fun onNetworkStateChange() {
        buildAppInfo()
    }

    private fun buildAppInfo() {
       _vacaState.update { currentState ->
            currentState.copy(
                appInfo = mapOf(
                    "Version" to config!!.version,
                    "IP Address" to (if (Helpers.isNetworkAvailable(config!!.context)) Helpers.getIpv4HostAddress() else ""),
                    "Port" to APPConfig.SERVER_PORT.toString(),
                    "UUID" to config!!.uuid,
                    "Paired to" to config!!.pairedDeviceID,
                )
           )
       }
    }

    fun checkForUpdate() {
        BroadcastSender.sendBroadcast(config!!.context, BroadcastSender.VERSION_MISMATCH)
    }

    fun requestPermissions() {
        BroadcastSender.sendBroadcast(config!!.context, BroadcastSender.REQUEST_MISSING_PERMISSIONS)
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
        config!!.pairedDeviceID = ""
        config!!.accessToken = ""
        config!!.refreshToken = ""
        config!!.tokenExpiry = 0
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
        if (uuid != "" && uuid != config!!.uuid) {
            config!!.uuid = uuid
            showUUIDChangeDialog(false)
            clearPairedDevice()
            buildAppInfo()
            config!!.eventBroadcaster.notifyEvent(Event("restartZeroconf", "", ""))
        }
    }
}

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
    var audioLevel: Float = 0f,
    var detectionThreshold: Float = 0f,
    var detectionLevel: Float = 0f,
    var mode: AudioRouteOption = AudioRouteOption.NONE,
    var wakeWord: String = "",
    var vadDetection: Boolean = false
)