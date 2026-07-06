package com.msp1974.vacompanion.device.info

import android.content.Context
import android.os.Build
import android.webkit.WebView

data class DeviceSoftwareData(
    val appVersion: String,
    val sdkVersion: Int,
    val webViewVersion: String,
    val release: String,
    val isAndroidThings: Boolean
)

class DeviceSoftware(
    private val context: Context,
) {
    val softwareInfo = DeviceSoftwareData(
        appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.toString(),
        sdkVersion = Build.VERSION.SDK_INT,
        webViewVersion = getWebViewVersion(),
        release = Build.VERSION.RELEASE.toString(),
        isAndroidThings = isAndroidThings()
    )

    private fun getWebViewVersion(): String {
        try {
            val info = WebView.getCurrentWebViewPackage()
            return info!!.versionName!!
        } catch (e: Exception) {
            return "unknown"
        }
    }

    fun isAndroidThings(): Boolean {
        return context.packageManager.hasSystemFeature("android.hardware.type.embedded")
    }
}
