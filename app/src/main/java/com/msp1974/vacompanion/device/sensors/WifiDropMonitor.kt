package com.msp1974.vacompanion.device.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.msp1974.vacompanion.settings.APPConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Records Wi-Fi drops (and roams) to a small persistent log and, if network recovery is enabled,
 * force-reconnects Wi-Fi when the connection stays down for [RECONNECT_AFTER_MS], using root as a
 * last resort. Root is given up on the first time it fails.
 *
 * Fed by [NetworkSensor]'s default-network callback. The log is written to
 * `<external files dir>/wifi_drops.log` (pullable with adb from
 * /sdcard/Android/data/com.msp1974.vacompanion/files/) and mirrored to Timber.
 */
class WifiDropMonitor(context: Context, private val config: APPConfig) {

    companion object {
        const val RECONNECT_AFTER_MS = 30_000L
        private const val ROOT_TIMEOUT_S = 15L
        private const val LOG_FILE = "wifi_drops.log"
        private const val MAX_LOG_BYTES = 256 * 1024L
    }

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var lastSnapshot: Snapshot? = null
    private var downSince = 0L
    private var watchdog: Job? = null

    private data class Snapshot(val ssid: String, val bssid: String, val rssi: Int, val freq: Int, val speed: Int) {
        override fun toString() = "ssid=$ssid bssid=$bssid rssi=$rssi freq=$freq link=${speed}Mbps"
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun snapshot(): Snapshot? = try {
        val info: WifiInfo = wifiManager.connectionInfo
        if (info.networkId == -1) null
        else Snapshot(info.ssid.trim('"'), info.bssid ?: "unknown", info.rssi, info.frequency, info.linkSpeed)
    } catch (e: Exception) {
        Timber.e(e, "Unable to read wifi info")
        null
    }

    /** True if the system's default network is currently a Wi-Fi one. */
    private fun isWifiDefault(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /** Wi-Fi is the active network (or its capabilities changed): track signal and roams. */
    @Synchronized
    fun onWifiConnected() {
        val now = snapshot()

        // Wi-Fi is back whether or not its details can be read (snapshot() is null when they
        // can't), so a recorded drop is always closed here and the watchdog stopped.
        if (downSince != 0L) {
            val secs = (System.currentTimeMillis() - downSince) / 1000
            downSince = 0L
            watchdog?.cancel()
            val prev = lastSnapshot
            write("RESTORED after ${secs}s - ${now ?: "no wifi details"}" +
                (prev?.let { " (was bssid=${it.bssid} rssi=${it.rssi})" } ?: ""))
        }
        if (now == null) return

        val prev = lastSnapshot
        lastSnapshot = now
        if (prev != null && prev.bssid != now.bssid) {
            write("ROAM ${prev.bssid} (${prev.freq}MHz, ${prev.rssi}dBm) -> ${now.bssid} (${now.freq}MHz, ${now.rssi}dBm)")
        }
    }

    /** The default network was lost while it was Wi-Fi. */
    @Synchronized
    fun onWifiLost() {
        if (downSince != 0L) return
        // The old network is reported lost after its replacement is up (e.g. a Wi-Fi to Wi-Fi
        // handover), so only a lost default network with nothing to take over is a drop.
        if (connectivityManager.activeNetwork != null) return
        downSince = System.currentTimeMillis()
        write("DROP - last known ${lastSnapshot ?: "n/a"}; wifi enabled=${wifiManager.isWifiEnabled}")
        if (!config.enableNetworkRecovery) return

        watchdog?.cancel()
        watchdog = Sensor.sensorWorkerScope.launch {
            var attempt = 1
            while (true) {
                delay(RECONNECT_AFTER_MS)
                if (!config.enableNetworkRecovery) return@launch
                // Don't act on a drop the system says is over, even if its end was never reported.
                if (isWifiDefault()) {
                    onWifiConnected()
                    return@launch
                }
                val secs = (System.currentTimeMillis() - downSince) / 1000
                write("STILL DOWN after ${secs}s - forcing reconnect (attempt $attempt)")
                forceReconnect(attempt++)
            }
        }
    }

    fun stop() {
        watchdog?.cancel()
    }

    /**
     * Escalating reconnect. WifiManager.reconnect()/setWifiEnabled() are no-ops for third party
     * apps targeting Android 10+, so they only work on privileged/older builds; the root `svc`
     * toggle works on rooted devices. Each step's outcome is logged so it's clear which one (if
     * any) is effective on a given device.
     */
    @Suppress("DEPRECATION")
    private fun forceReconnect(attempt: Int) {
        try {
            if (!wifiManager.isWifiEnabled) {
                write("  wifi disabled -> setWifiEnabled(true)=${wifiManager.setWifiEnabled(true)}")
                return
            }
            if (attempt % 2 == 1) {
                write("  disconnect()=${wifiManager.disconnect()} reconnect()=${wifiManager.reconnect()}")
            } else {
                write("  setWifiEnabled(false)=${wifiManager.setWifiEnabled(false)}")
                Thread.sleep(2000)
                write("  setWifiEnabled(true)=${wifiManager.setWifiEnabled(true)}")
            }
        } catch (e: Exception) {
            write("  reconnect failed: ${e.message}")
        }

        if (attempt >= 2 && !config.wifiRootFailed) rootToggle()
    }

    /**
     * Toggles Wi-Fi as root, as part of network recovery. If root is refused, missing or doesn't
     * answer in [ROOT_TIMEOUT_S] this is remembered so it is never asked for again.
     */
    private fun rootToggle() {
        var process: Process? = null
        val failure = try {
            process = Runtime.getRuntime()
                .exec(arrayOf("su", "-c", "svc wifi disable; sleep 2; svc wifi enable"))
            if (!process.waitFor(ROOT_TIMEOUT_S, TimeUnit.SECONDS)) "no answer in ${ROOT_TIMEOUT_S}s"
            else process.exitValue().let { if (it == 0) null else "exit=$it" }
        } catch (e: Exception) {
            "unavailable (${e.message})"
        } finally {
            process?.destroy()
        }

        if (failure == null) {
            write("  root wifi toggle ok")
        } else {
            config.wifiRootFailed = true
            write("  root wifi toggle failed: $failure - won't try root again")
        }
    }

    @Synchronized
    private fun write(message: String) {
        Timber.w("WifiDrop: $message")
        try {
            val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val file = File(dir, LOG_FILE)
            if (file.length() > MAX_LOG_BYTES) {
                File(dir, "$LOG_FILE.1").delete()
                file.renameTo(File(dir, "$LOG_FILE.1"))
            }
            file.appendText("${timeFormat.format(Date())} $message\n")
        } catch (e: Exception) {
            Timber.e(e, "Unable to write wifi drop log")
        }
    }
}
