  package com.msp1974.vacompanion.ui

import android.app.Application
import android.app.NotificationManager
import android.content.Context
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
import com.msp1974.vacompanion.device.MotionDetectionMode
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.data.AvailableAlarm
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSound
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.satellite.AudioRouteOption
import com.msp1974.vacompanion.satellite.SatelliteCustomFilesHandler
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.WakeWordType
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
import androidx.core.net.toUri
import com.msp1974.vacompanion.device.DeviceInfo
import com.msp1974.vacompanion.device.MotionDetectionEngine.Companion.MOTION_INTERVAL_TIMEOUT
import com.msp1974.vacompanion.utils.WebViewGestureDetector

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
    var hasOptionalPermissions: Boolean = false,
    var recordAudio: Boolean = false,
    var camera: Boolean = false,
    var postNotifications: Boolean = false,
    var writeExternalStorage: Boolean = false,
    var writeSettings: Boolean = false,
    var notificationPolicy: Boolean = false,
    var deviceAdmin: Boolean = false
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
    var vadDetection: Boolean = false,
    var motionDetected: Boolean = false,
    var hasCamera: Boolean = false,
    var lastMotionTimestamp: Long = 0,
    var motionInterval: Int = 10000,
    var motionDetectionMode: MotionDetectionMode = MotionDetectionMode.MOTION
)



data class CustomFilesState(
    val microWakeWords: List<String> = emptyList(),
    val openWakeWords: List<String> = emptyList(),
    val openWakeWordsRT: List<String> = emptyList(),
    val sounds: List<AvailableWakeSound> = emptyList(),
    val alarms: List<AvailableAlarm> = emptyList(),
    val isDownloading: Boolean = false,
    val isSyncing: Boolean = false,
    val downloadName: String = "",
    val downloadProgress: Int = 0
)

data class State(
    val statusMessage: String = "Initialising...",
    var orientation: Int = Configuration.ORIENTATION_LANDSCAPE,

    var launchOnBoot: Boolean = true,
    var satelliteRunning: Boolean = false,
    var darkMode: Boolean = false,
    var isDND: Boolean = false,
    var screenBlank: Boolean = true,

    var appInfo: Map<String, String> = mapOf(),
    var diagnosticInfo: DiagnosticInfo = DiagnosticInfo(),

    var showAlertDialog: Boolean = false,
    var alertDialog: VADialog? = null,
    var showMenu: Boolean = false,
    var menuOpenedByAction: Boolean = false,
    var permissions: PermissionsStatus = PermissionsStatus(),
    var updates: UpdateStatus = UpdateStatus(),
    var webViewPageLoadingStage: PageLoadingStage = PageLoadingStage.NOT_STARTED,
    var showUUIDChangeDialog: Boolean = false,
    var isNetworkConnected: Boolean = true,
    var showSettings: Boolean = false,
    var customFiles: CustomFilesState = CustomFilesState(),
    var cameraStreamActive: Boolean = false,
    var motionDetectionSensitivity: Int = 0,
    var motionDetectionMode: MotionDetectionMode = MotionDetectionMode.MOTION
    )

@HiltViewModel
class VAViewModel @Inject constructor(
    application: Application,
    val config: APPConfig,
    val deviceInfo: DeviceInfo,
    val networkStatusManager: NetworkStatusManager
): ViewModelBase(application), EventListener, Closeable {

    private val _vacaState = MutableStateFlow(State())
    val vacaState: StateFlow<State> = _vacaState.asStateFlow()

    var resources: Resources = application.resources
    var permissions: Permissions = Permissions(application.applicationContext, config, deviceInfo)
    val network = Network(application.applicationContext)
    val customFileDownloader = CustomFileDownloader(application, config)

    val changedNetworkStatus = networkStatusManager.networkStatus
        .dropWhile { it.status == NetworkStatus.Available }
        .shareIn(viewModelScope, SharingStarted.Eagerly, 1)

    init {
        _vacaState.value = State()

        network.setWifiLock()

        config.eventBroadcaster.addListener(this)
        initValues()
        buildAppInfo()
        startNetworkMonitor()
    }

    fun initValues() {
        _vacaState.update { currentState ->
            currentState.copy(
                launchOnBoot = config.startOnBoot,
                motionDetectionSensitivity = config.motionDetectionSensitivity,
                motionDetectionMode = config.motionDetectionMode,
                // TODO: Move this into a dedicated configuration observer pattern to handle live updates.
                diagnosticInfo = currentState.diagnosticInfo.copy(
                    show = config.diagnosticsEnabled,
                    engine = config.wakeWordEngine,
                    muted = config.isMuted,
                    hasCamera = deviceInfo.hardware.hasFrontCamera,
                    motionDetectionMode = config.motionDetectionMode
                )
            )
        }
    }

    override fun close() {
        network.releaseWifiLock()
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
            "openSettings" -> onOpenSettingsAction()
            "darkMode" -> {
                _vacaState.update { currentState ->
                    currentState.copy(
                        darkMode = event.newValue as Boolean
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
                    data.motionDetected = currentState.diagnosticInfo.motionDetected
                    data.hasCamera = currentState.diagnosticInfo.hasCamera
                    data.lastMotionTimestamp = currentState.diagnosticInfo.lastMotionTimestamp
                    data.motionInterval = currentState.diagnosticInfo.motionInterval
                    currentState.copy(
                        diagnosticInfo = data
                    )
                }
            }
            "motionDetectionSensitivity" -> {
                val sensitivity = event.newValue as Int
                _vacaState.update { currentState ->
                    currentState.copy(
                        motionDetectionSensitivity = sensitivity
                    )
                }
            }
            "motionDetectionMode" -> {
                val mode = event.newValue as MotionDetectionMode
                _vacaState.update { currentState ->
                    currentState.copy(
                        motionDetectionMode = mode,
                        diagnosticInfo = currentState.diagnosticInfo.copy(
                            motionDetectionMode = mode
                        )
                    )
                }
            }
            "motion" -> {
                val value = event.newValue as? Boolean ?: true
                if (value) {
                    val now = System.currentTimeMillis()
                    _vacaState.update { currentState ->
                        currentState.copy(
                            diagnosticInfo = currentState.diagnosticInfo.copy(
                                motionDetected = true,
                                lastMotionTimestamp = now,
                                motionInterval = MOTION_INTERVAL_TIMEOUT
                            )
                        )
                    }
                } else {
                    _vacaState.update { currentState ->
                        currentState.copy(
                            diagnosticInfo = currentState.diagnosticInfo.copy(
                                motionDetected = false
                            )
                        )
                    }
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

    fun onShowDiagnostics(show: Boolean) {
        config.diagnosticsEnabled = show
    }

    fun onToggleDND(enabled: Boolean) {
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notificationManager.isNotificationPolicyAccessGranted) {
            val filter = if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            notificationManager.setInterruptionFilter(filter)
            config.doNotDisturb = enabled
        } else {
            requestNotificationPolicyAccess()
        }
    }

    fun onOpenSettingsAction() {
        _vacaState.update { currentState ->
            currentState.copy(
                showMenu = true,
                menuOpenedByAction = true
            )
        }
        config.settingsOpen = true
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
        _vacaState.update { currentState ->
            currentState.copy(
                isNetworkConnected = status == NetworkStatus.Available
            )
        }
        when (status) {
            NetworkStatus.Unavailable  -> setStatusMessage(application.getString(R.string.status_waiting_for_network))
            NetworkStatus.Available -> setStatusMessage(getString(application.applicationContext, R.string.status_waiting_for_connection))
        }
        buildAppInfo()
    }

    fun onGesture(gestureEvent: WebViewGestureDetector.GestureEvent) {
        config.eventBroadcaster.notifyEvent(Event("gesture", "", gestureEvent))
    }

    fun hideSystemUI() {
        config.eventBroadcaster.notifyEvent(Event("hideSystemUI", "", ""))
    }

    private fun buildAppInfo() {
       _vacaState.update { currentState ->
            currentState.copy(
                appInfo = mapOf(
                    "Version" to config.version,
                    "IP Address" to (if (Helpers.isNetworkAvailable(config.context)) Helpers.getIpv4HostAddress() else ""),
                    "Port" to APPConfig.SERVER_PORT.toString(),
                    "Device ID" to config.uuid,
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

    fun refreshPermissionsStatus() {
        _vacaState.update { currentState ->
            currentState.copy(
                permissions = PermissionsStatus(
                    hasCorePermissions = permissions.hasCorePermissions(),
                    hasOptionalPermissions = permissions.hasOptionalPermissions(),
                    recordAudio = permissions.hasPermission(android.Manifest.permission.RECORD_AUDIO),
                    camera = permissions.hasPermission(android.Manifest.permission.CAMERA),
                    postNotifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.hasPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else true,
                    writeExternalStorage = if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.hasPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else true,
                    writeSettings = permissions.hasWriteSettingsPermission(),
                    notificationPolicy = permissions.hasNotificationAccessPolicyPermission(),
                    deviceAdmin = permissions.isDeviceAdmin()
                )
            )
        }
    }

    fun togglePermission(permission: String) {
        if (this.permissions.hasPermission(permission)) {
            openAppSettings()
        } else {
            // For runtime permissions, we trigger the system request
            BroadcastSender.sendBroadcast(config.context, BroadcastSender.REQUEST_MISSING_PERMISSIONS, permission)
        }
    }

    fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${config.context.packageName}".toUri()
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        config.context.startActivity(intent)
    }

    fun requestWriteSettingsPermission() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "package:${config.context.packageName}".toUri()
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        config.context.startActivity(intent)
    }

    fun requestNotificationPolicyAccess() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        config.context.startActivity(intent)
    }

    fun requestDeviceAdmin() {
        val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                android.content.ComponentName(config.context, com.msp1974.vacompanion.VACADeviceAdminReceiver::class.java)
            )
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Device admin is required to allow the app to lock/blank the screen."
            )
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        config.context.startActivity(intent)
    }

    fun setPermissionsStatus(core: Boolean, optional: Boolean) {
        refreshPermissionsStatus()
    }

    fun clearPairedDevice() {
        config.pairedDeviceID = ""
        config.accessToken = ""
        config.refreshToken = ""
        config.tokenExpiry = 0
    }

    fun setUUID(uuid: String = "") {
        // TODO: Add validation
        if (uuid != "" && uuid != config.uuid) {
            config.uuid = uuid
            clearPairedDevice()
            buildAppInfo()
            config.eventBroadcaster.notifyEvent(Event("restartZeroconf", "", ""))
        }
    }

    fun setShowMenu(show: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                showMenu = show,
                menuOpenedByAction = if (!show) false else currentState.menuOpenedByAction
            )
        }
        config.settingsOpen = show
    }

    fun setShowSettings(show: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                showSettings = show
            )
        }
        config.settingsOpen = show
    }

    fun setCameraStreamActive(active: Boolean) {
        _vacaState.update { currentState ->
            currentState.copy(
                cameraStreamActive = active
            )
        }
        config.cameraStreamActive = active
        config.eventBroadcaster.notifyEvent(Event("cameraStreamActive", "", active))
    }

    fun refreshCustomFiles() {
        viewModelScope.launch {
            val wakeSounds = AvailableWakeSounds(app, config).get()
            val alarms = AvailableAlarms(app, config).get()
            
            // Update config for server info (includes assets)
            config.availableWakeSounds = wakeSounds
            config.availableAlarms = alarms

            _vacaState.update { currentState ->
                currentState.copy(
                    customFiles = CustomFilesState(
                        microWakeWords = customFileDownloader.listCustomWakeWordModels(WakeWordType.MICROWAKEWORD),
                        openWakeWords = customFileDownloader.listCustomWakeWordModels(WakeWordType.OPENWAKEWORD),
                        openWakeWordsRT = customFileDownloader.listCustomWakeWordModels(WakeWordType.OPENWAKEWORD_RT),
                        // For management UI, we only want to show custom files (not assets)
                        sounds = customFileDownloader.listAvailableCustomWakeSounds(),
                        alarms = customFileDownloader.listAvailableCustomAlarms()
                    )
                )
            }
            config.eventBroadcaster.notifyEvent(Event("updateCustomFiles","",""))
        }
    }

    fun refreshAvailableWakeWords() {
        viewModelScope.launch {
            config.availableWakeWords = AvailableWakeWords(app).get()
            config.eventBroadcaster.notifyEvent(Event("updateAvailableWakeWords", "", ""))
        }
    }

    fun syncCustomFiles() {
        viewModelScope.launch {
            _vacaState.update { currentState ->
                currentState.copy(
                    customFiles = currentState.customFiles.copy(isSyncing = true)
                )
            }
            try {
                val handler = SatelliteCustomFilesHandler(app, config, this@VAViewModel)
                handler.syncAllCustomFiles()
                refreshCustomFiles()
                refreshAvailableWakeWords()
            } finally {
                _vacaState.update { currentState ->
                    currentState.copy(
                        customFiles = currentState.customFiles.copy(isSyncing = false)
                    )
                }
            }
        }
    }

    fun deleteWakeWordModel(type: WakeWordType, name: String) {
        customFileDownloader.deleteWakeWordModel(type, name)
        refreshCustomFiles()
        refreshAvailableWakeWords()
    }

    fun deleteWakeWordModels(type: WakeWordType, names: List<String>) {
        names.forEach { name ->
            customFileDownloader.deleteWakeWordModel(type, name)
        }
        refreshCustomFiles()
        refreshAvailableWakeWords()
    }

    fun deleteCustomFile(subDir: String, name: String) {
        customFileDownloader.deleteCustomFile(subDir, name)
        refreshCustomFiles()
    }

    fun deleteCustomFiles(subDir: String, names: List<String>) {
        names.forEach { name ->
            customFileDownloader.deleteCustomFile(subDir, name)
        }
        refreshCustomFiles()
    }

    fun setDownloadProgress(name: String, progress: Int) {
        _vacaState.update { currentState ->
            currentState.copy(
                customFiles = currentState.customFiles.copy(
                    isDownloading = true,
                    downloadName = name,
                    downloadProgress = progress
                )
            )
        }
    }

    fun clearDownloadProgress() {
        _vacaState.update { currentState ->
            currentState.copy(
                customFiles = currentState.customFiles.copy(
                    isDownloading = false,
                    downloadName = "",
                    downloadProgress = 0
                )
            )
        }
    }
}

