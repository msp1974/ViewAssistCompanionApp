package com.msp1974.vacompanion.utils

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat

// Shared by the BLE scanner and settings UI. BLE scan results require Location to be on
// because the manifest does not declare neverForLocation (which would hide beacons).
fun isSystemLocationEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        // AndroidX checks GPS/network providers before API 28. Devices such as the
        // NSPanel Pro have neither provider, but can scan BLE when Location is on.
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF,
        ) != Settings.Secure.LOCATION_MODE_OFF
    }
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return LocationManagerCompat.isLocationEnabled(lm)
}
