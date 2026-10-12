package com.msp1974.vacompanion.utils

import android.Manifest.permission
import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.CONNECTIVITY_SERVICE
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresPermission
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.pow


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
            val device = Build.DEVICE
            if (model.lowercase().startsWith(manufacturer.lowercase())) {
                return "$model ($device)"
            } else {
                return "$manufacturer $model ($device)"
            }
        }

        @RequiresPermission(permission.ACCESS_NETWORK_STATE)
        fun isNetworkAvailable(context: Context): Boolean {
            val connMgr = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            return connMgr.activeNetwork != null
        }

        fun enableWifi(context: Context, enable: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.setWifiEnabled(enable)
            }
        }

        fun isNumber(input: String): Boolean {
            val integerChars = '0'..'9'
            var dotOccurred = 0
            return input.all { it in integerChars || it == '.' && dotOccurred++ < 1 }
        }

        fun Float.round(decimals: Int = 2): Float  = (this * 10f.pow(decimals)).toInt() / 10f.pow(decimals)

        fun String.capitalizeWords(delimiter: String = " ") =
            split(delimiter).joinToString(delimiter) { word ->

                val smallCaseWord = word.lowercase()
                smallCaseWord.replaceFirstChar(Char::titlecaseChar)

            }
    }
}