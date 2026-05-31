package com.msp1974.vacompanion.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Logger

class ScreenUtils (val context: Context, val config: APPConfig) : ContextWrapper(context) {
    var log = Logger()
    private val firebase = FirebaseManager.Companion.getInstance(context)

    private var wakeLock: PowerManager.WakeLock? = null
    var initBrightness: Float = 0f

    init {
        initBrightness = getScreenBrightness()
    }

    fun getScreenBrightness(): Float {
        return Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS
        ) / 255f
    }

    fun setScreenBrightness(window: Window, brightness: Float) {
        try {
            if (!getScreenAutoBrightnessMode()) {
                if (canWriteScreenSetting()) {
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        (brightness * 255).toInt()
                    )
                } else {
                    val layout: WindowManager.LayoutParams? = window.attributes
                    layout?.screenBrightness = brightness
                    window.attributes = layout
                }
            }
        } catch (e: Exception) {
            log.e("Error setting screen brightness: $e")
            firebase.logException(e)
        }
    }

    fun getScreenAutoBrightnessMode(): Boolean {
        return getDeviceBrightnessMode() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }

    fun setScreenAutoBrightness(window: Window, state: Boolean) {
        if (!state) {
            setDeviceBrightnessMode(false)
            setScreenBrightness(window, config.screenBrightness)
        } else {
            setDeviceBrightnessMode(true)
        }
    }

    fun setScreenAlwaysOn(window: Window, state: Boolean, reason: String = "screen_always_on") {
        log.i("SET_KEEP_SCREEN_ON enabled=$state reason=$reason")
        window.decorView.keepScreenOn = state
        if (state) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun getDeviceBrightnessMode(): Int {
        try {
            return Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            ) //this will return integer (0 or 1)
        } catch (e: Settings.SettingNotFoundException) {
            log.e("No screen brightness mode setting available")
            return -1
        }
    }

    fun setDeviceBrightnessMode(automatic: Boolean = false) {
        if (!canWriteScreenSetting()) {
            return
        }
        val mode = getDeviceBrightnessMode()
        try {
            if (automatic) {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    //reset back to automatic mode
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    )
                }
            } else {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    //Automatic mode, need to be in manual to change brightness
                    Settings.System.putInt(
                        contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                }
            }
        } catch (e: SecurityException) {
            log.e("Error setting screen brightness mode: $e")
            firebase.logException(e)
        }
    }

    fun enableWakeScreenFlags(activity: Activity, window: Window, reason: String) {
        log.i("ENABLE_WAKE_SCREEN_FLAGS reason=$reason")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }

    fun clearWakeScreenFlags(activity: Activity, window: Window, reason: String) {
        log.i("CLEAR_WAKE_FLAGS reason=$reason")
        window.clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        @Suppress("DEPRECATION")
        window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(false)
            activity.setTurnScreenOn(false)
        }
    }

    fun allowPhysicalSleep(activity: Activity, window: Window, reason: String) {
        log.i("ALLOW_PHYSICAL_SLEEP reason=$reason")
        releaseWakeLock(reason)
        setScreenAlwaysOn(window, false, reason)
        clearWakeScreenFlags(activity, window, reason)
    }

    fun wakeScreen(lockDuration: Long = 5000, reason: String = "explicit_wake") {
        log.i("WAKE_SCREEN_EXPLICIT reason=$reason durationMs=$lockDuration")
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "vacompanion.ScreenUtils:wakeLock"
        )
        wakeLock?.acquire(lockDuration)
    }

    fun setPartialWakeLock(lockDuration: Long = 15000, reason: String = "screen_sleep") {
        log.i("Acquiring partial wake lock reason=$reason durationMs=$lockDuration")
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "vacompanion.ScreenUtils:partialWakeLock"
        )
        wakeLock?.acquire(lockDuration)
    }

    fun releaseWakeLock(reason: String) {
        if (wakeLock != null && wakeLock!!.isHeld) {
            log.i("Releasing wake lock reason=$reason")
            wakeLock!!.release()
        }
    }

    fun lockScreen() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.lockNow()
    }

    fun canWriteScreenSetting(): Boolean {
        return Settings.System.canWrite(applicationContext)
    }

    fun isScreenOn(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    fun isScreenOn2(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return display.state == Display.STATE_ON
        } else {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            return wm.defaultDisplay.state == Display.STATE_ON
        }
    }

    fun isScreenOff(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return display.state == Display.STATE_OFF
        } else {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            return wm.defaultDisplay.state == Display.STATE_OFF
        }
    }

    fun getScreenTimeout(): Int {
        return Settings.System.getString(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toInt()
    }

    fun setScreenTimeout(timeout: Int): Boolean {
        if (canWriteScreenSetting()) {
            try {
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, timeout)
                return true
            } catch (e: Exception) {
                log.e("Error setting screen timeout: $e")
                return false
            }
        }
        return false
    }

    fun hideSystemUI(window: Window) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            val decorView: View = window.decorView
            decorView.setSystemUiVisibility(
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            )
        }
    }
}
