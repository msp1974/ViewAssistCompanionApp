package com.msp1974.vacompanion.utils

import android.Manifest
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Context.DEVICE_POLICY_SERVICE
import android.content.Context.NOTIFICATION_SERVICE
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.VACADeviceAdminReceiver
import com.msp1974.vacompanion.device.DeviceManager
import timber.log.Timber

/**
 * Permission checks, plus - when constructed with an [activity] - the ability to request one or
 * more runtime permissions.
 *
 * [requestPermission]/[requestPermissions] each go through their own dedicated
 * ActivityResultLauncher with its own dedicated callback, registered once up front. This is
 * deliberate: the legacy ActivityCompat.requestPermissions() + Activity.onRequestPermissionsResult()
 * pattern funnels every in-flight request through one shared callback, so requesting an
 * unrelated permission mid-session (e.g. BLUETOOTH_CONNECT for a Bluetooth mic) would trigger
 * whatever tail logic that shared callback ran for the *last* request - in this app's case, the
 * whole startup permission cascade. Per-request callbacks make that impossible.
 *
 * [activity] is optional because Permissions is also constructed from non-Activity scopes
 * (a foreground service, a ViewModel) purely to check permission state - requestPermission(s)
 * is only usable when an activity was actually provided.
 */
class Permissions(
    val context: Context,
    val deviceManager: DeviceManager,
    private val activity: ComponentActivity? = null,
) {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    companion object {
        const val CAMERA = Manifest.permission.CAMERA
        const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
        const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    }

    // --- Requesting ---

    // Callers (e.g. MicrophoneInputController re-evaluating on every Bluetooth profile
    // connect event while a permission dialog is still up) can end up calling requestPermission
    // again for the same permission before the first request has resolved. Re-launching the
    // ActivityResultLauncher in that case would silently drop the first caller's callback - the
    // system only ever delivers one result to whichever callback is registered when it arrives
    // - so any duplicate request for a permission already in flight is dropped rather than
    // relaunched; only the original caller's onResult fires.
    private var singleRequestedPermission: String? = null
    private var pendingSingleResult: ((Boolean) -> Unit)? = null
    private val singlePermissionLauncher = activity?.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        singleRequestedPermission = null
        val callback = pendingSingleResult
        pendingSingleResult = null
        callback?.invoke(granted)
    }

    private var multipleRequestedPermissions: List<String>? = null
    private var pendingMultipleResult: ((Map<String, Boolean>) -> Unit)? = null
    private val multiplePermissionsLauncher = activity?.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        multipleRequestedPermissions = null
        val callback = pendingMultipleResult
        pendingMultipleResult = null
        callback?.invoke(results)
    }

    // Requests a single runtime permission, invoking onResult with the grant outcome. Calls
    // back immediately (without prompting) if already granted. If a request for this same
    // permission is already in flight, this call is dropped entirely - onResult is not invoked.
    fun requestPermission(permission: String, onResult: (Boolean) -> Unit = {}) {
        if (hasPermission(permission)) {
            onResult(true)
            return
        }
        if (singleRequestedPermission == permission) {
            Timber.d("Permission request for $permission already in flight, dropping duplicate request")
            return
        }
        val launcher = singlePermissionLauncher
        if (launcher == null) {
            Timber.e("Cannot request $permission - Permissions was constructed without an activity")
            onResult(false)
            return
        }

        Timber.d("Requesting permission: $permission")
        singleRequestedPermission = permission
        pendingSingleResult = onResult
        launcher.launch(permission)
    }

    // Requests a list of runtime permissions together, invoking onResult with each requested
    // permission's grant outcome. Calls back immediately if all are already granted. If the
    // same set of permissions is already in flight, this call is dropped entirely - onResult is
    // not invoked.
    fun requestPermissions(permissionsToRequest: List<String>, onResult: (Map<String, Boolean>) -> Unit = {}) {
        val missing = permissionsToRequest.filter { !hasPermission(it) }
        if (missing.isEmpty()) {
            onResult(permissionsToRequest.associateWith { true })
            return
        }
        if (multipleRequestedPermissions == missing) {
            Timber.d("Permission request for $missing already in flight, dropping duplicate request")
            return
        }
        val launcher = multiplePermissionsLauncher
        if (launcher == null) {
            Timber.e("Cannot request $missing - Permissions was constructed without an activity")
            onResult(missing.associateWith { false })
            return
        }

        Timber.d("Requesting permissions: $missing")
        multipleRequestedPermissions = missing
        pendingMultipleResult = onResult
        launcher.launch(missing.toTypedArray())
    }

    // The core runtime permissions required at startup. Requests whichever are missing and
    // invokes onComplete once resolved - immediately if none were missing. Mirrors the previous
    // behaviour of only recording each hasXxxPermission config flag when it was already granted
    // at check time, not when freshly granted via the request.
    fun requestCorePermissions(onComplete: () -> Unit = {}) {
        val required = mutableListOf<String>()

        if (hasPermission(RECORD_AUDIO)) {
            config.hasRecordAudioPermission = true
            Timber.d("Permission: RECORD_AUDIO = true")
        } else {
            Timber.d("Permission: RECORD_AUDIO = false")
            required += RECORD_AUDIO
        }

        if (deviceInfo.hardware.hasFrontCamera) {
            if (hasPermission(CAMERA)) {
                config.hasCameraPermission = true
                Timber.d("Permission: CAMERA = true")
            } else {
                Timber.d("Permission: CAMERA = false")
                required += CAMERA
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (hasPermission(POST_NOTIFICATIONS)) {
                config.hasPostNotificationPermission = true
                Timber.d("Permission: POST_NOTIFICATIONS = true")
            } else {
                Timber.d("Permission: POST_NOTIFICATIONS = false")
                required += POST_NOTIFICATIONS
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (hasPermission(WRITE_EXTERNAL_STORAGE)) {
                config.hasWriteExternalStoragePermission = true
                Timber.d("Permission: WRITE_EXTERNAL_STORAGE = true")
            } else {
                Timber.d("Permission: WRITE_EXTERNAL_STORAGE = false")
                required += WRITE_EXTERNAL_STORAGE
            }
        }

        if (required.isEmpty()) {
            Timber.d("Main permissions already granted")
            onComplete()
            return
        }

        Timber.d("Requesting main permissions: $required")
        requestPermissions(required) { onComplete() }
    }

    // --- Checking ---

    fun hasCorePermissions(): Boolean {
        val permissions = mutableListOf(RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(POST_NOTIFICATIONS)
        }

        for (permission in permissions) {
            val granted = hasPermission(permission)
            if (!granted) {
                return false
            }
        }
        return true
    }

    fun hasOptionalPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            permissions.add(WRITE_EXTERNAL_STORAGE)
        }
        if (deviceInfo.hardware.hasFrontCamera) {
            permissions.add(CAMERA)
        }

        for (permission in permissions) {
            if (!hasPermission(permission)) {
                return false
            }
        }

        if (!hasWriteSettingsPermission()) {
            return false
        }

        if (!hasNotificationAccessPolicyPermission()) {
            return false
        }

        if (!isDeviceAdmin()) {
            return false
        }

        return true
    }

    fun hasAllPermissions(): Boolean {
        return hasCorePermissions() && hasOptionalPermissions()
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasWriteSettingsPermission(): Boolean {
        return Settings.System.canWrite(context)
    }

    fun hasNotificationAccessPolicyPermission(): Boolean {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (!config.canSetNotificationPolicyAccess) {
            return true
        } else {
            return notificationManager.isNotificationPolicyAccessGranted
        }
    }

    fun isDeviceAdmin(): Boolean {
        val dpm: DevicePolicyManager = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val mDeviceAdmin = ComponentName(context, VACADeviceAdminReceiver::class.java)
        return dpm.isAdminActive(mDeviceAdmin)
    }
}
