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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.msp1974.vacompanion.VACADeviceAdminReceiver
import com.msp1974.vacompanion.device.DeviceManager
import timber.log.Timber

class Permissions(val context: Context, val deviceManager: DeviceManager) {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    companion object {
        const val CAMERA = Manifest.permission.CAMERA
        const val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO
        const val WRITE_EXTERNAL_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        const val POST_NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    }

    fun hasCorePermissions(): Boolean {
        val permissions = mutableListOf(RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(POST_NOTIFICATIONS)
        }

        for (permission in permissions) {
            if (!hasPermission(permission)) {
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
        val result = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        Timber.d("Permission $permission = $result")
        return result

    }

    fun hasWriteSettingsPermission(): Boolean {
        val result = Settings.System.canWrite(context)
        Timber.d("Write settings permission = $result")
        return result
    }

    fun hasNotificationAccessPolicyPermission(): Boolean {
        val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        var result = false

        if (!config.canSetNotificationPolicyAccess) {
            result = true
        } else {
            result = notificationManager.isNotificationPolicyAccessGranted
        }
        Timber.d("Notification access policy permission = $result")
        return result
    }

    fun isDeviceAdmin(): Boolean {
        val dpm: DevicePolicyManager = context.getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val mDeviceAdmin = ComponentName(context, VACADeviceAdminReceiver::class.java)
        val result = dpm.isAdminActive(mDeviceAdmin)
        Timber.d("Device admin permission = $result")
        return result
    }
}