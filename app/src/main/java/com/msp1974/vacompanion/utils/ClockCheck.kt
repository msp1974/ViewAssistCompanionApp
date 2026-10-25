package com.msp1974.vacompanion.utils

import com.msp1974.vacompanion.BuildConfig
import java.util.Calendar

/**
 * Detects a system clock that has not been set (e.g. no network to reach a time server on boot).
 * A bad clock breaks token authentication. The build date of the APK is used as the reference:
 * a clock whose year is earlier than the build year cannot be correct.
 */
object ClockCheck {
    fun isClockSynchronised(): Boolean {
        val buildYear = Calendar.getInstance().apply { timeInMillis = BuildConfig.BUILD_TIME }.get(Calendar.YEAR)
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return currentYear >= buildYear
    }
}
