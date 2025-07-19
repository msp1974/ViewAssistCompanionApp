package com.msp1974.vacompanion.utils

import android.content.ContextWrapper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.msp1974.vacompanion.settings.APPConfig

class ScreenUtils(val activity: AppCompatActivity) : ContextWrapper(activity) {
    var log = Logger()
    var config = APPConfig.getInstance(activity.applicationContext)
    private var wakeLock: PowerManager.WakeLock? = null

    fun hideStatusAndActionBars() {
        val window = activity.window
        val visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        window.decorView.systemUiVisibility = visibility
    }

    fun setScreenBrightness(
        screenBrightnessValue: Int
    ) {   // Change the screen brightness change mode to manual.
        try {
            if (canWriteScreenSetting()) {
                // Apply the screen brightness value to the system, this will change
                // the value in Settings ---> Display ---> Brightness level.
                // It will also change the screen brightness for the device.
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    screenBrightnessValue
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setDeviceBrightnessMode(automatic: Boolean = false) {
        if (!canWriteScreenSetting()) {
            return
        }
        var mode = -1
        try {
            mode = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            ) //this will return integer (0 or 1)
        } catch (e: Settings.SettingNotFoundException) {
            log.e("No screen brightness mode setting available")
        }
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
        }
    }

    fun wakeScreen() {
        log.d("Acquiring screen on wake lock")
        if (wakeLock != null && wakeLock!!.isHeld) {
            wakeLock!!.release()
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "vacompanion.ScreenUtils:wakeLock"
        )
        wakeLock?.acquire(1000)
    }

    fun canWriteScreenSetting(): Boolean {
        var hasPermission = true
        //if (!config.requiresScreenWritePermission) {
        //    return hasPermission
        //}
        hasPermission = Settings.System.canWrite(applicationContext)
        return hasPermission
    }
}