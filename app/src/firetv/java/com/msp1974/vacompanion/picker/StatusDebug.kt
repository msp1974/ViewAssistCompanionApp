package com.msp1974.vacompanion.picker

import android.content.Intent
import com.msp1974.vacompanion.ble.BleRemoteService
import kotlin.math.roundToInt

/**
 * Holds the latest BLE status so the picker tile title can be updated live.
 * We currently get battery PERCENT from adverts. If you later add volts (mV),
 * set it via updateBatteryMv(...) and the UI will prefer "B: x.xxV".
 */
object StatusDebug {
    @Volatile private var lastBatteryPct: Int? = null         // 0..100 (preferred if no voltage)
    @Volatile private var lastBatteryMv: Int? = null          // e.g. 2000 mV → shows 2.00V
    @Volatile private var lastRssi: Int? = null               // in dBm
    @Volatile private var lastConnected: Boolean? = null

    fun updateFromButton(intent: Intent) {
        if (intent.action != BleRemoteService.ACTION_BUTTON) return
        // Battery % (EXTRA_BATTERY_PCT present in your service)
        val pct = intent.getIntExtra(BleRemoteService.EXTRA_BATTERY_PCT, -1)
        if (pct in 0..100) lastBatteryPct = pct
        // If you start sending mV as an extra, you can capture here too.
    }

    fun updateFromConnState(intent: Intent) {
        if (intent.action != BleRemoteService.ACTION_CONNECTION_STATE) return
        val rssiVal = intent.getIntExtra(BleRemoteService.EXTRA_RSSI, Int.MIN_VALUE)
        if (rssiVal != Int.MIN_VALUE) lastRssi = rssiVal
        lastConnected = intent.getBooleanExtra(BleRemoteService.EXTRA_CONNECTED, false)
    }

    /** If you later add voltage to adverts, call this with millivolts. */
    fun updateBatteryMv(mv: Int?) {
        lastBatteryMv = mv?.takeIf { it > 0 }
    }

    fun formatTitle(): String {
        val battStr = when {
            lastBatteryMv != null -> {
                val v = (lastBatteryMv!! / 10) / 100.0  // safe double math
                // More precise formatting without trailing zeros fuss:
                val volts = (lastBatteryMv!! / 10.0) / 100.0
                // round to 2 decimals
                val show = ((volts * 100.0).roundToInt() / 100.0)
                "B: ${show}V"
            }
            lastBatteryPct != null -> "B: ${lastBatteryPct}%"
            else -> "B: —"
        }

        val sigStr = lastRssi?.let { "S:${it}dB" } ?: "S:—"
        return "$battStr  $sigStr"
    }
}
