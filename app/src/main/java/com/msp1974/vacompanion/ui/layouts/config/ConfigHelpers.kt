package com.msp1974.vacompanion.ui.layouts.config

import android.content.Context
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.compose.ui.graphics.Color
import androidx.core.location.LocationManagerCompat
import com.msp1974.vacompanion.audio.AudioEnhancerSource
import com.msp1974.vacompanion.audio.AudioInRouter
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.theme.CustomColours

// Helpers for working out the values shown by the read-only config items.

// Whether Android's Location setting is on (needed for Bluetooth scanning).
fun locationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}

fun yesNo(value: Boolean) = if (value) "Yes" else "No"

fun yesNoColor(value: Boolean): Color = if (value) CustomColours.GREEN else CustomColours.AMBER

fun enhancementSourceLabel(source: String): String = when (source) {
    AudioEnhancerSource.ANDROID -> "Android"
    AudioEnhancerSource.SOFTWARE -> "Software"
    else -> "Not Available"
}

fun enhancementSourceColor(source: String): Color = when (source) {
    AudioEnhancerSource.ANDROID -> CustomColours.GREEN
    AudioEnhancerSource.SOFTWARE -> CustomColours.AMBER
    else -> CustomColours.RED
}

fun activeOutputLabel(context: Context, config: APPConfig): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val id = AudioOutputRouter.activeOutputId(context, config)
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.id == id }
        ?.let { "${it.productName} (${AudioInRouter.getDeviceTypeName(it.type)})" }
        ?: "Unknown"
}

// Running is green; enabled but not up is red, since something is wrong; otherwise it is simply off.
fun serviceStatus(running: Boolean, enabled: Boolean) = when {
    running -> "Running"
    enabled -> "Not running"
    else -> "Disabled"
}

fun serviceStatusColor(running: Boolean, enabled: Boolean): Color = when {
    running -> CustomColours.GREEN
    enabled -> CustomColours.RED
    else -> CustomColours.AMBER
}

// The SSID of the connected wifi network. Android hides it (as "<unknown ssid>") unless the app
// has location permission, so say so rather than showing that placeholder.
@Suppress("DEPRECATION")
fun wifiSsid(context: Context): String {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ssid = try {
        wifiManager?.connectionInfo?.ssid?.trim('"')
    } catch (e: SecurityException) {
        null
    }
    return when {
        ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID || ssid == "0x" ->
            if (wifiManager?.isWifiEnabled == true) "Unavailable (needs location permission)" else "Not connected"
        else -> ssid
    }
}
