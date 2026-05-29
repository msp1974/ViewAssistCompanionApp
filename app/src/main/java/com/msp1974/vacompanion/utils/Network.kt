package com.msp1974.vacompanion.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import timber.log.Timber

class Network(val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var boundedReleaseRunnable: Runnable? = null

    init {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wallPanel:wifiLock")
        wifiLock?.setReferenceCounted(false)
    }

    fun acquireWifiLockBounded(reason: String, timeoutMs: Long = DEFAULT_WIFI_LOCK_TIMEOUT_MS) {
        if (wifiLock == null) {
            Timber.i("WiFi lock acquire skipped: reason=$reason wifiLock=null")
            return
        }
        if (wifiLock?.isHeld == true) {
            Timber.d("WiFi lock acquire skipped: reason=$reason already held")
            return
        }
        Timber.i("WiFi lock acquire: reason=$reason timeoutMs=$timeoutMs")
        wifiLock?.acquire()
        scheduleBoundedRelease(reason, timeoutMs)
    }

    fun acquireWifiLockUnboundedForAlwaysMode(reason: String) {
        if (wifiLock == null) {
            Timber.i("WiFi lock acquire skipped: reason=$reason wifiLock=null")
            return
        }
        if (wifiLock?.isHeld == true) {
            Timber.d("WiFi lock acquire skipped: reason=$reason already held")
            return
        }
        Timber.i("WiFi lock acquire: reason=$reason unbounded=true")
        cancelBoundedRelease()
        wifiLock?.acquire()
    }

    fun releaseWifiLock(reason: String) {
        cancelBoundedRelease()
        if (wifiLock != null && wifiLock!!.isHeld) {
            Timber.i("WiFi lock release: reason=$reason")
            wifiLock?.release()
        }
    }

    private fun scheduleBoundedRelease(reason: String, timeoutMs: Long) {
        cancelBoundedRelease()
        boundedReleaseRunnable = Runnable {
            Timber.i("WiFi lock release: reason=timeout_$reason")
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            boundedReleaseRunnable = null
        }
        handler.postDelayed(boundedReleaseRunnable!!, timeoutMs)
    }

    private fun cancelBoundedRelease() {
        boundedReleaseRunnable?.let { handler.removeCallbacks(it) }
        boundedReleaseRunnable = null
    }

    companion object {
        private const val DEFAULT_WIFI_LOCK_TIMEOUT_MS = 60_000L
    }
}
