package com.msp1974.vacompanion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings.Secure
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.reflect.KProperty


class Global: Application() {
    companion object {
        @JvmField
        var config = Config()
        @JvmField
        var status: Status = Status
        @JvmField
        var log: Logger = Logger()
    }
}
class MainActivity : AppCompatActivity() {
    private var recordPermissions: Boolean = false
    private var config: Config = Global.config
    private var status: Status = Global.status
    private lateinit var backgroundTask: BackgroundTask
    private lateinit var wakeLock: PowerManager.WakeLock

    @SuppressLint("HardwareIds", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.enableEdgeToEdge()

        setContentView(R.layout.activity_waiting)
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "com.msp1974.vacompanion:WakeLock")
        wakeLock.acquire()


        // Get UUID
        config.uuid = Secure.getString(contentResolver, Secure.ANDROID_ID)

        val ip = findViewById<TextView>(R.id.ip)
        val version = findViewById<TextView>(R.id.version)
        val uuid = findViewById<TextView>(R.id.uuid)
        ip.text = "IP: " + getIpv4HostAddress()
        version.text = "Version: " + Global.config.version
        uuid.text = "ID: " + Global.config.uuid

        status.connectionStateChangeListeners.add(object : InterfaceStatusChangeListener {
            @SuppressLint("SetJavaScriptEnabled")
            override fun onStatusChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
                if (property.name == "connectionState" && newValue == "Connected" && config.haURL.isNotEmpty()) {
                    val webIntent = Intent(this@MainActivity, WebViewActivity::class.java)
                    startActivity(webIntent)
                }

            }
        })

        // Check and get user permission to record
        recordPermissions = checkAndRequestPermissions()
        Global.log.i("Permissions granted: $recordPermissions")

        // Start tasks
        if (recordPermissions) {
            runBackgroundTasks()
        }
    }

    @SuppressLint("Wakelock")
    override fun onDestroy() {
        super.onDestroy()
        wakeLock.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val visibility: Int
        if (hasFocus) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            visibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            window.decorView.systemUiVisibility = visibility
        }
    }

    private fun runBackgroundTasks() {
        backgroundTask = BackgroundTask(this)
        backgroundTask.start()
    }

    private fun checkAndRequestPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_RECORD_AUDIO
            )
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission was granted, you can continue your operation
                    runBackgroundTasks()
                } else {
                    // Permission denied, you can disable the functionality that depends on this permission.
                }
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 200
    }

    private fun getIpv4HostAddress(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.map { networkInterface ->
            networkInterface.inetAddresses?.toList()?.find {
                !it.isLoopbackAddress && it is Inet4Address
            }?.let { return it.hostAddress }
        }
        return ""
    }

}

