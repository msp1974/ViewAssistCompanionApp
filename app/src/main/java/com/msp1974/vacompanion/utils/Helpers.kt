package com.msp1974.vacompanion.utils

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresPermission
import java.net.Inet4Address
import java.net.NetworkInterface

class Helpers {
    companion object {
        fun getIpv4HostAddress(): String {
            NetworkInterface.getNetworkInterfaces()?.toList()?.map { networkInterface ->
                networkInterface.inetAddresses?.toList()?.find {
                    !it.isLoopbackAddress && it is Inet4Address
                }?.let { return it.hostAddress }
            }
            return ""
        }

        fun getAndroidVersion(): String {
            val release = Build.VERSION.RELEASE
            val sdkVersion = Build.VERSION.SDK_INT
            return "SDK: $sdkVersion ($release)"
        }

        @SuppressLint("HardwareIds")
        fun getDeviceName(): String? {
            val manufacturer = Build.MANUFACTURER
            val model = Build.MODEL
            if (model.lowercase().startsWith(manufacturer.lowercase())) {
                return model
            } else {
                return "$manufacturer $model"
            }
        }

        @RequiresPermission(permission.ACCESS_NETWORK_STATE)
        fun isNetworkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            return cm.activeNetwork != null
        }
    }
}