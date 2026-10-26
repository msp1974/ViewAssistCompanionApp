package com.msp1974.vacompanion.utils

import android.content.Context
import android.net.wifi.WifiManager
import timber.log.Timber

class Network(val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null

    init {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vaca:wifiLock")
    }

    fun setWifiLock() {
        if (wifiLock != null && !wifiLock!!.isHeld) {
            Timber.i("Acquiring wifi lock")
            wifiLock?.acquire()
        }
    }

    fun releaseWifiLock() {
        if (wifiLock != null && wifiLock!!.isHeld) {
            Timber.i("Releasing wifi lock")
            wifiLock?.release()
        }
    }
}