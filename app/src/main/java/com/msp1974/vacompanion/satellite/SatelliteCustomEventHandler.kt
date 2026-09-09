package com.msp1974.vacompanion.satellite

import android.content.Context
import android.media.AudioManager
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.VolumeManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.SoundControl
import com.msp1974.vacompanion.utils.WebViewGestureDetector
import com.msp1974.vacompanion.wyoming.WyomingInfoBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds


class SatelliteCustomEventHandler(
    val context: Context,
    val deviceManager: DeviceManager,
    val scope: CoroutineScope,
    val satellite: Satellite
): EventListener {

    val volumeManager = VolumeManager(context)
    val config = deviceManager.config

    private val serviceStarted = CompletableDeferred<Int>()

    fun run() {
        scope.launch {
            start()
        }
        Timber.d("Satellite custom event handler started")
    }

    suspend fun start() {
        withContext(Dispatchers.Default) {
            val job = launch {
                try {
                    config.eventBroadcaster.addListener(this@SatelliteCustomEventHandler)
                    awaitCancellation()
                } finally {
                    config.eventBroadcaster.removeListener(this@SatelliteCustomEventHandler)
                }
            }


            serviceStarted.await()
            job.cancel()
            Timber.d("Satellite custom event handler stopped")
        }
    }

    fun stop() {
        serviceStarted.complete(0)
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "isMuted" -> {
                try {
                    satellite.muteMicrophone(event.newValue as Boolean)
                } catch (e: Exception) {
                    Timber.e("Error setting muted: ${e.message.toString()}")
                }
            }
            "notificationVolume" -> {
                if (!SoundControl.isDoNotDisturbEnabled(context)) {
                    volumeManager.setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Int)
                }
            }
            "musicVolume" -> {
                volumeManager.setVolume(AudioManager.STREAM_MUSIC, event.newValue as Int)
            }
            "alarmVolume" -> {
                volumeManager.setVolume(AudioManager.STREAM_ALARM, event.newValue as Int)
            }
            "wakeWord", "wakeWordSound", "wakeWordThreshold", "wakeWordEngine" -> {
                scope.launch {
                    satellite.restartWakeWordDetection()
                }
            }
            "speakerEnrollmentStart" -> {
                scope.launch {
                    satellite.startSpeakerEnrollment()
                }
            }
            "speakerEnrollmentClear" -> {
                scope.launch {
                    satellite.clearSpeakerEnrollment()
                }
            }
            "recognitionError" -> {
                val errorText = event.oldValue as? String ?: ""
                if (errorText.isNotEmpty()) {
                    config.eventBroadcaster.notifyEvent(Event("showToastError", "", errorText))
                }
                //audioRoute = AudioRouteOption.DETECT
                satellite.sendDiagnostics(0f, 0f)
            }
            "doNotDisturb" -> {
                volumeManager.setDoNotDisturb(event.newValue as Boolean)
                satellite.sendSetting("do_not_disturb", event.newValue)
            }
            "screenSaver" -> {
                satellite.sendSetting("screen_saver", event.newValue)
            }
            "screenSaverDisableOnTouch" -> {
                satellite.sendSetting("screen_saver_disable_on_touch", event.newValue)
            }
            "currentPath" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val isOn = event.newValue as Boolean
                satellite.sendSetting("screen_on", isOn)
                if (isOn && config.enableMotionDetection) {
                    // Force restart of camera when screen turns on to ensure recovery
                    scope.launch {
                        delay(500.milliseconds)
                        satellite.motionTask.startCamera()
                    }
                }
            }
            "motionDetectionMode" -> {
                val mode = event.newValue as String
                if (mode != "none") {
                    if (!config.cameraStreamActive) {
                        satellite.motionTask.startCamera()
                    }
                } else {
                    scope.launch { satellite.motionTask.stopCamera() }
                }
            }
            "motion" -> {
                val value = event.newValue as? Boolean ?: true
                config.lastMotion = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", value)
                            if (value) {
                                put("last_motion", config.lastMotion)
                            }
                        })
                    }
                )
            }
            "lastActivity" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "musicPlayerPlayingStatus" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("media_player", {
                            put("playing", event.newValue as Boolean)
                        })
                    }
                )
            }
            "cameraStreamActive" -> {
                val active = event.newValue as Boolean
                if (active) {
                    scope.launch { satellite.motionTask.stopCamera() }
                } else if (config.enableMotionDetection) {
                    satellite.motionTask.startCamera()
                }
            }
            "updateAvailableWakeWords" -> {
                val infoBuilder = WyomingInfoBuilder(deviceManager)
                satellite.sendEvent("info", infoBuilder.buildInfo())
            }
            "updateCustomFiles" -> {
                satellite.sendCapabilities()
            }
            "gesture" -> {
                val data = event.newValue as? WebViewGestureDetector.GestureEvent
                if (data != null) {
                    satellite.sendCustomEvent("gesture",
                        buildJsonObject {
                            put("gesture", data.direction.toString().lowercase()  )
                            put("touch_points", data.pointers)
                            put("x", data.startX)
                            put("y", data.startX)
                        }
                    )
                }
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("Event: ${event.eventName} - ${event.newValue}")
        }
    }
}
