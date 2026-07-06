package com.msp1974.vacompanion

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.system.Os
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import com.msp1974.vacompanion.utils.ActivityManager
import com.msp1974.vacompanion.utils.Helpers
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


@HiltAndroidApp
class VACAApplication: Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()

        Timber.plant(DebugTree())

        Timber.i("#################################################################################################")
        Timber.i("Starting View Assist Companion App")
        Timber.i("Version ${packageManager.getPackageInfo(packageName, 0).versionName}")
        Timber.i("Android version: ${Helpers.getAndroidVersion()}")
        Timber.i("CPU: ${System.getProperty("os.arch")}")
        Timber.i("Name: ${Helpers.getDeviceName()}")
        Timber.i("Serial: ${Build.SERIAL}")
        Timber.i("#################################################################################################")

        suppressMLKitSpam()

        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this.applicationContext))

        disableSSLCertificateChecking()

        activityManager = ActivityManager(this)

        // Create the notification channel (required for Android 8.0 and above)
        val channel = NotificationChannel(
            "VACAForegroundServiceChannelId",
            "VACA Foreground Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        // service provided by Android Operating system to show notification outside of our app
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

    }

    private fun suppressMLKitSpam() {
        try {
            // Suppress TFLITE/XNNPack spam logs before any ML Kit/TFLite components load
            Os.setenv("TFLITE_XNNPACK_DELEGATE_NO_LOGGING", "1", true)
            Os.setenv("XNNPACK_LOG_LEVEL", "0", true)
            Os.setenv("TFLITE_LOG_LEVEL", "0", true)
            Os.setenv("TF_CPP_MIN_LOG_LEVEL", "3", true) // 3 = FATAL

            // Native ML Kit tags often check these environment variables as a fallback for system properties
            Os.setenv("log.tag.FaceDetectorV2Jni", "ERROR", true)
            Os.setenv("log.tag.ThickFaceDetector", "ERROR", true)
            Os.setenv("log.tag.Vision", "ERROR", true)
        } catch (_: Exception) {}
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_FRONT_CAMERA)
            .setMinimumLoggingLevel(android.util.Log.ERROR)
            .build()
    }

    private fun disableSSLCertificateChecking() {
        val trustAllCerts: Array<TrustManager?> = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate?>? {
                return null
            }

            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            override fun checkClientTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
                // Not implemented
            }

            @SuppressLint("TrustAllX509TrustManager")
            @Throws(CertificateException::class)
            override fun checkServerTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
                // Not implemented
            }
        }) as Array<TrustManager?>

        try {
            val sc = SSLContext.getInstance("TLS")

            sc.init(null, trustAllCerts, SecureRandom())

            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (e: KeyManagementException) {
            e.printStackTrace()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var activityManager: ActivityManager
    }
}