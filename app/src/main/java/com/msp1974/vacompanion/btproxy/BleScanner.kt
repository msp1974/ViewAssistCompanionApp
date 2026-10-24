package com.msp1974.vacompanion.btproxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.HandlerThread
import timber.log.Timber

/**
 * Wraps the Android BLE scanner and turns its results into batches of raw advertisements.
 *
 * Android has no passive scan, so "passive" maps to a balanced scan and "active" to low latency.
 * Results are batched for [FLUSH_MS] and de-duplicated: an unchanged payload from the same device
 * is only re-sent every [REPEAT_MS] so RSSI still refreshes without flooding the network.
 *
 * Android silently delivers no results to an unfiltered scan while system Location is off (the
 * Bluetooth stack logs "Cannot start unfiltered scan in location-off"), so the scan is not started
 * until Location is on: that is reported through [onLocationStateChanged] and re-checked every
 * [LOCATION_RECHECK_MS], rather than restarting a scan that can never see anything.
 */
@SuppressLint("MissingPermission")
abstract class BleScanner(
    private val adapter: BluetoothAdapter?,
    private val isLocationEnabled: () -> Boolean,
) {

    private val thread = HandlerThread("btproxy-scan").apply { start() }
    private val handler = Handler(thread.looper)

    private val pending = LinkedHashMap<Long, BleAdvertisement>()
    private val lastSent = HashMap<Long, Pair<Int, Long>>() // address -> (payload hash, time)

    @Volatile var state = ScannerState.STOPPED
        private set
    @Volatile var activeMode = false
        private set
    @Volatile var receivedCount = 0L
        private set
    @Volatile var forwardedCount = 0L
        private set

    private var callback: ScanCallback? = null
    private var flushScheduled = false

    /** Called on the scanner thread with each batch to forward. */
    abstract fun onAdvertisements(ads: List<BleAdvertisement>)

    /** The scanner state or mode changed. */
    abstract fun onStateChanged()

    /** The scan cannot run because system Location is off ([off] = true), or that was fixed. */
    open fun onLocationStateChanged(off: Boolean) {}

    // Whether a scan is wanted, so it can start by itself once Location is turned on.
    private var wanted = false
    private var locationOff = false

    val isScanning: Boolean get() = callback != null

    fun start(active: Boolean) = handler.post {
        wanted = true
        startInternal(active)
    }

    fun stop() = handler.post {
        wanted = false
        stopInternal()
    }

    /** Releases the scanner thread; the scanner cannot be used afterwards. */
    fun shutdown() {
        handler.post {
            wanted = false
            stopInternal()
            thread.quitSafely()
        }
    }

    private fun setState(newState: Int) {
        if (state != newState) {
            state = newState
            onStateChanged()
        }
    }

    private fun startInternal(active: Boolean, force: Boolean = false) {
        if (callback != null) {
            if (active == activeMode && !force) return
            stopInternal()
        }
        activeMode = active
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("Bluetooth proxy: BLE scanner unavailable (Bluetooth off?)")
            setState(ScannerState.FAILED)
            return
        }
        if (!isLocationEnabled()) {
            Timber.w("Bluetooth proxy: system Location is off, so Android will not deliver BLE scan results - waiting")
            setLocationOff(true)
            setState(ScannerState.FAILED)
            handler.removeCallbacks(locationRecheck)
            handler.postDelayed(locationRecheck, LOCATION_RECHECK_MS)
            return
        }
        setLocationOff(false)
        setState(ScannerState.STARTING)

        val settings = ScanSettings.Builder()
            .setScanMode(if (active) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) =
                results.forEach { handleResult(it) }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("Bluetooth proxy: scan failed ($errorCode)")
                handler.post {
                    callback = null
                    setState(ScannerState.FAILED)
                }
            }
        }
        try {
            scanner.startScan(null, settings, cb)
            callback = cb
            lastWatchdogCount = receivedCount
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, WATCHDOG_MS)
            setState(ScannerState.RUNNING)
            Timber.d("Bluetooth proxy: scanning started (active=$active)")
        } catch (e: Exception) {
            Timber.w("Bluetooth proxy: could not start scan: $e")
            setState(ScannerState.FAILED)
        }
    }

    private fun setLocationOff(off: Boolean) {
        if (locationOff != off) {
            locationOff = off
            onLocationStateChanged(off)
        }
    }

    private val locationRecheck = Runnable {
        if (wanted && callback == null) startInternal(activeMode)
    }

    private fun stopInternal() {
        handler.removeCallbacks(locationRecheck)
        val cb = callback ?: run {
            if (state != ScannerState.STOPPED) setState(ScannerState.STOPPED)
            return
        }
        setState(ScannerState.STOPPING)
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            Timber.w("Bluetooth proxy: error stopping scan: $e")
        }
        callback = null
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(flushRunnable)
        flushScheduled = false
        pending.clear()
        lastSent.clear()
        setState(ScannerState.STOPPED)
    }

    private fun handleResult(result: ScanResult) {
        val raw = result.scanRecord?.bytes ?: return
        val data = trimPadding(raw)
        val address = try {
            BleAddress.toLong(result.device.address)
        } catch (_: Exception) {
            return
        }
        handler.post {
            if (callback == null) return@post
            receivedCount++
            val hash = data.contentHashCode()
            val now = System.currentTimeMillis()
            val last = lastSent[address]
            if (last != null && last.first == hash && now - last.second < REPEAT_MS) return@post

            lastSent[address] = hash to now
            pending[address] = BleAdvertisement(address, result.rssi, ADDRESS_TYPE_PUBLIC, data)
            if (pending.size >= MAX_BATCH) {
                flush()
            } else if (!flushScheduled) {
                flushScheduled = true
                handler.postDelayed(flushRunnable, FLUSH_MS)
            }
        }
    }

    private val flushRunnable = Runnable { flush() }

    private var lastWatchdogCount = 0L

    // A scan can go silently dead (Bluetooth stack restarted, OS throttling) without any callback.
    // If nothing at all was heard for a whole interval, restart it; a restart is cheap and well
    // inside Android's limit of five scan starts per 30 seconds.
    private val watchdog = object : Runnable {
        override fun run() {
            if (callback == null) return
            if (receivedCount == lastWatchdogCount) {
                Timber.w("Bluetooth proxy: no advertisements for ${WATCHDOG_MS / 1000}s, restarting scan")
                startInternal(activeMode, force = true)
            } else {
                lastWatchdogCount = receivedCount
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    private fun flush() {
        handler.removeCallbacks(flushRunnable)
        flushScheduled = false
        if (pending.isEmpty()) return
        val batch = pending.values.toList()
        pending.clear()
        forwardedCount += batch.size
        batch.chunked(MAX_BATCH).forEach { onAdvertisements(it) }
        if (lastSent.size > MAX_TRACKED) {
            val cutoff = System.currentTimeMillis() - REPEAT_MS
            lastSent.entries.removeAll { it.value.second < cutoff }
        }
    }

    companion object {
        private const val WATCHDOG_MS = 30_000L
        private const val LOCATION_RECHECK_MS = 5_000L
        private const val FLUSH_MS = 100L
        private const val REPEAT_MS = 2_000L
        private const val MAX_BATCH = 16
        private const val MAX_TRACKED = 1_000

        // Android does not expose the address type before API 36; public is what Home Assistant
        // assumes when none is given, and Android resolves the real type from its scan cache.
        private const val ADDRESS_TYPE_PUBLIC = 0

        /**
         * Android returns the advertisement and scan response concatenated and zero padded.
         * Walk the AD structures (length, type, data) and cut at the first zero-length one.
         */
        fun trimPadding(raw: ByteArray): ByteArray {
            var i = 0
            while (i < raw.size) {
                val len = raw[i].toInt() and 0xFF
                if (len == 0) break
                i += len + 1
            }
            return raw.copyOf(minOf(i, raw.size))
        }
    }
}
