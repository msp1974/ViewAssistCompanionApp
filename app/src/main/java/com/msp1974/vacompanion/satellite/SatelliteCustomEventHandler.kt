package com.msp1974.vacompanion.satellite

import android.content.Context
import android.media.AudioManager
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.device.VolumeManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber


class SatelliteCustomEventHandler(
    val context: Context,
    val config: APPConfig,
    val scope: CoroutineScope,
    val satellite: Satellite
): EventListener {

    val volumeManager = VolumeManager(context)

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
                if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
                    volumeManager.setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Int)
                }
            }
            "musicVolume" -> {
                volumeManager.setVolume(AudioManager.STREAM_MUSIC, event.newValue as Int)
            }
            "wakeWord", "wakeWordSound", "wakeWordThreshold", "wakeWordEngine", "useVoiceEnhancer", "useAdvancedGain" -> {
                scope.launch {
                    satellite.restartWakeWordDetection()
                }
            }
            "wakeWordTrigger" -> {
                scope.launch {
                    satellite.handleWakeWordDetection()
                }
            }
            "recognitionError" -> {
                val errorText = event.oldValue as? String ?: ""
                if (errorText.isNotEmpty()) {
                    config.eventBroadcaster.notifyEvent(Event("showToastError", "", errorText))
                }

                if (config.wakeWordSound != "none") {
                    try {
                        scope.launch {
                            satellite.mediaManager.soundPlayer.play(R.raw.error)
                        }
                    } catch (e: Exception) {
                        Timber.e("Error playing wake word sound: ${e.message.toString()}")
                    }
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
                satellite.sendSetting("screen_on", event.newValue as Boolean)
            }
            "screenState" -> {
                satellite.sendScreenStatus(event.newValue as Boolean)
            }
            "enableMotionDetection" -> {
                val state = event.newValue as Boolean
                if (state) {
                    satellite.motionTask.startCamera()
                } else {
                    satellite.motionTask.stopCamera()
                }
            }
            "lastMotion" -> {
                satellite.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", true)
                            put("last_motion", config.lastMotion)
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
            "motionDetectionSensitivity" -> {
                satellite.motionTask.setSensitivity(event.newValue as Int)
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
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("Event: ${event.eventName} - ${event.newValue}")
        }
    }
}
