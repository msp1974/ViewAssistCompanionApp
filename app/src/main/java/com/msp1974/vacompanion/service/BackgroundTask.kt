package com.msp1974.vacompanion.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.res.AssetManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.wyoming.Zeroconf
import com.msp1974.vacompanion.audio.SoundClipPlayer
import com.msp1974.vacompanion.audio.AudioManager as AudManager
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.DeviceCapabilitiesManager
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.VolumeObserver
import com.msp1974.vacompanion.wakeword.WakeWordEngine
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.WyomingCallback
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.util.Date
import kotlin.collections.set
import kotlin.concurrent.thread

enum class AudioRouteOption { NONE, DETECT, PROCESS_NO_DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): EventListener {

    private val firebase = FirebaseManager.getInstance(context)
    private var config: APPConfig = APPConfig.getInstance(context)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wakeWordJob: Job? = null
    private var holdDetectionLevelJob: Job? = null
    private var lastWakeWordDetectionScore = 0f

    private var wifiLock: WifiManager.WifiLock? = null

    private val detectionCooldowns = mutableMapOf<String, Long>()
    private val detectionCooldownMs: Long = 2000L

    val zeroConf: Zeroconf = Zeroconf(context)

    var engine: WakeWordEngine? = null
    var engineStarted: Boolean = false
    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: WyomingTCPServer
    private lateinit var volumeObserver: VolumeObserver

    private var motionTask = CameraBackgroundTask(context)

    fun start() {
        assetManager = context.assets

        // wifi lock
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "wallPanel:wifiLock")

        volumeObserver = VolumeObserver(context) { musicVolume, notificationVolume ->
            if (config.musicVolume != musicVolume) {
                config.musicVolume = musicVolume
                server.sendSetting("music_volume", musicVolume)
            }

            if (config.notificationVolume != notificationVolume) {
                config.notificationVolume = notificationVolume
                server.sendSetting("notification_volume", notificationVolume)
            }
        }

        // Start wyoming server
        server = WyomingTCPServer(context, config.serverPort, object : WyomingCallback {
            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
            override fun onSatelliteStarted() {
                Timber.i("Background Task - Connection detected")
                setInitialValues()
                volumeObserver.register()
                startSensors(context)
                runWakeWordDetection()
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
                zeroConf.unregisterService()
            }

            override fun onSatelliteStopped() {
                Timber.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                terminateWakeWordDetection()
                stopSensors()
                volumeObserver.unregister()
                zeroConf.registerService(config.serverPort)
            }

            override fun onRequestInputAudioStream() {
                Timber.i("Streaming audio to server")
                audioRoute = AudioRouteOption.STREAM
                engine?.setStreaming(true)
            }

            override fun onReleaseInputAudioStream() {
                Timber.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    audioRoute = AudioRouteOption.PROCESS_NO_DETECT
                    lastWakeWordDetectionScore = 0f

                    scope.launch {
                        delay(2000)
                        audioRoute = AudioRouteOption.DETECT
                    }
                }
                engine?.setStreaming(false)
            }
        })
        thread(name="WyomingServer") { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        zeroConf.registerService(config.serverPort)

        Timber.d("Background task initialisation completed")
    }

    override fun onEventTriggered(event: Event) {
        var consumed = true
        when (event.eventName) {
            "isMuted" -> {
                try {
                    engine?.setMuted(event.newValue as Boolean)
                    sendDiagnostics(0f,0f)
                } catch (e: Exception) {
                    Timber.e("Error setting muted: ${e.message.toString()}")
                }
            }
            "notificationVolume" -> {
                if (!DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)) {
                    setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Int)
                }
            }
            "musicVolume" -> {
                setVolume(AudioManager.STREAM_MUSIC, event.newValue as Int)
            }
            "wakeWord", "wakeWordThreshold", "wakeWordEngine", "useVoiceEnhancer", "useAdvancedGain" -> {
                scope.launch {
                    try {
                        if (wakeWordJob != null && wakeWordJob!!.isActive) {
                            restartWakeWordDetection()
                        } else if (server.pipelineClient != null) {
                            runWakeWordDetection()
                        }
                    } catch (e: SecurityException) {
                        Timber.e("Error restarting wake word detection: ${e.message.toString()}")
                    }
                }
            }
            "wakeWordTrigger" -> {
                wakeWordDetected(WakeWordEngineProvider.WakeWordDetection(
                    wakeWordId =  config.wakeWord,
                    wakeWord = config.wakeWord,
                    detected =  true,
                    score =  config.wakeWordThreshold
                ),
                false
                )
            }
            "recognitionError" -> {
                when (event.newValue) {
                    "duplicate_wake_up_detected" -> {}
                    else -> {
                        if (config.wakeWordSound != "none") {
                            try {
                                SoundClipPlayer(
                                    context,
                                    R.raw.error
                                ).play()
                            } catch (e: Exception) {
                                Timber.e("Error playing wake word sound: ${e.message.toString()}")
                            }
                        }
                    }
                }
                audioRoute = AudioRouteOption.DETECT
                sendDiagnostics(0f, 0f)
            }
            "doNotDisturb" -> {
                setDoNotDisturb(event.newValue as Boolean)
                server.sendSetting("do_not_disturb", event.newValue)
            }
            "screenSaver" -> {
                server.sendSetting("screen_saver", event.newValue)
            }
            "restartZeroconf" -> {
                zeroConf.unregisterService()
                scope.launch {
                    delay(2000)
                    zeroConf.registerService(config.serverPort)
                }
            }
            "pairedDeviceID" -> {
                if (config.pairedDeviceID != "") {
                    Timber.d("Device paired, stopping Zeroconf")
                    zeroConf.unregisterService()
                } else {
                    Timber.d("Device unpaired, starting Zeroconf")
                    zeroConf.registerService(config.serverPort)
                }
            }
            "currentPath" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("current_path", event.newValue.toString())
                        })
                    }
                )
            }
            "screenOn" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("screen_on", state)
                        })
                    }
                )
            }
            "enableMotionDetection" -> {
                val state = event.newValue as Boolean
                if (state) {
                    motionTask.startCamera()
                } else {
                    motionTask.stopCamera()
                }
            }
            "lastMotion" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("motion_detected", true)
                            put("last_motion", config.lastMotion)
                        })
                    }
                )
            }
            "lastActivity" -> {
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("last_activity", config.lastActivity)
                        })
                    }
                )
            }
            "uiIdle" -> {
                val state = event.newValue as Boolean
                server.sendStatus(
                    buildJsonObject {
                        putJsonObject("sensors", {
                            put("ui_idle", state)
                        })
                    }
                )
            }
            "motionDetectionSensitivity" -> {
                motionTask.setSensitivity(event.newValue as Int)
            }
            else -> consumed = false
        }
        if (consumed) {
            Timber.d("BackgroundTask - Event: ${event.eventName} - ${event.newValue}")
        }
    }

    fun setInitialValues() {
        config.doNotDisturb = DeviceCapabilitiesManager.isDoNotDisturbEnabled(context)
    }

    fun startSensors(context: Context) {
        sensorRunner = Sensors(context, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        data.map { (key, value) ->
                            if (Helpers.isNumber(value.toString())) {
                                put(key, value.toString().toFloat())
                            } else {
                                put(key, value.toString())
                            }
                        }
                    }
                }
                server.sendStatus(data)
            }
        })
        // Start motion sensor
        if (config.enableMotionDetection) {
            motionTask.startCamera()
        }
    }

    fun stopSensors() {
        sensorRunner?.stop()
        motionTask.stopCamera()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun runWakeWordDetection() {
        wakeWordJob = scope.launch {
            delay(1000L)
            engine = WakeWordEngine(context,  if (config.wakeWordEngine == "openwakeword") WakeWordEngineModel.OPENWAKEWORD else WakeWordEngineModel.MICROWAKEWORD)
            engine?.setActiveWakeWords(listOf(config.wakeWord))
            engine?.setActiveStopWords(listOf("stop"))

            sendDiagnostics(0f, 0f)

            engine!!.start().collect {
                when (it) {
                    is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                        holdLastDetectionLevel(it.detection.score)
                        if (it.detection.score >= config.wakeWordThreshold) {
                            val now = System.currentTimeMillis()
                            val lastDetection = detectionCooldowns[it.detection.wakeWordId]

                            if (lastDetection == null || detectionCooldownMs == 0L || now - lastDetection >= detectionCooldownMs) {
                                Timber.i("Wake word detected: ${it.detection.wakeWord}")
                                wakeWordDetected(it.detection, engine!!.isStreaming())
                                detectionCooldowns[it.detection.wakeWordId] = now
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.StopDetected -> {
                        if (it.detection.detected) {
                            Timber.d("Stop word detected: ${it.detection.wakeWord}")
                            if (it.detection.score > 0.5) {
                                BroadcastSender.sendBroadcast(
                                    context,
                                    BroadcastSender.STOP_WORD_DETECTED
                                )
                            }
                        }
                    }

                    is WakeWordEngineProvider.AudioResult.Audio -> {
                        server.sendAudio(it.audio.toByteArray())
                    }

                    is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                        if (config.diagnosticsEnabled) {
                            sendDiagnostics(it.level, lastWakeWordDetectionScore)
                        }
                    }
                    is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                        Timber.i("Engine status: ${it.status}")
                        engineStarted = it.status == "Started"
                    }

                }
            }
        }
    }

    fun terminateWakeWordDetection() {
        if (wakeWordJob != null && wakeWordJob!!.isActive) {
            wakeWordJob?.cancel()
            wakeWordJob = null
        }
        engine = null
        engineStarted = false
        sendDiagnostics(0f, 0f)
        Timber.d("Wake word detection terminated")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun restartWakeWordDetection() {
        Timber.d("Restarting wake word detection")
        terminateWakeWordDetection()
        runWakeWordDetection()
    }

    private fun wakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection, isStreaming: Boolean) {
        Timber.i("${detection.wakeWord} wake word detected at ${detection.score}, threshold is ${config.wakeWordThreshold}")
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                "wake_word" to config.wakeWord,
                "threshold" to config.wakeWordThreshold.toString(),
                "prediction" to detection.score.toString()
            )
        )
        // if wake up on ww, send event
        if (config.screenOnWakeWord) {
            config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
        }

        if (!isStreaming && config.wakeWordSound != "none") {
            try {
                SoundClipPlayer(
                    context,
                    context.resources.getIdentifier(
                        config.wakeWordSound,
                        "raw",
                        context.packageName
                    )
                ).play()
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
        }
        holdLastDetectionLevel(detection.score)
        BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
    }

    private fun holdLastDetectionLevel(detectionLevel: Float, duration: Long = 2000) {
        if (detectionLevel > lastWakeWordDetectionScore) {
            lastWakeWordDetectionScore = detectionLevel
            if (holdDetectionLevelJob != null && holdDetectionLevelJob!!.isActive) {
                holdDetectionLevelJob?.cancel()
            }
            holdDetectionLevelJob = scope.launch {
                delay(duration)
                if (audioRoute == AudioRouteOption.DETECT) {
                    lastWakeWordDetectionScore = 0f
                }
            }
        }
    }

    fun setVolume(stream: Int, volume: Int) {
        try {
            val audioManager = AudManager(context)
            audioManager.setVolume(stream, volume)
        } catch (e: Exception) {
            Timber.d("Error setting volume: ${e.message.toString()}")
            firebase.logException(e)
        }
    }

    fun setDoNotDisturb(enable: Boolean) {
        val notificationManager =  context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val isInDND = notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        if (isInDND != enable) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                Timber.d("Setting do not disturb to $enable")
                if (enable) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            } else {
                Timber.w("Unable to set do not disturb, notification policy access not granted")
                config.eventBroadcaster.notifyEvent(
                    Event(
                        "showToastMessage",
                        "",
                        "Unable to set do not disturb.  Permission not granted."
                    )
                )
            }
        }
    }

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = audioLevel * 150,
                detectionLevel = detectionLevel * 10,
                detectionThreshold = config.wakeWordThreshold * 10,
                wakeWord = config.wakeWord,
                mode = if (engine == null || !engineStarted || engine!!.isMuted()) AudioRouteOption.NONE else if (engine!!.isStreaming()) AudioRouteOption.STREAM else AudioRouteOption.DETECT
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }


    fun shutdown() {
        Timber.i("Shutting down")
        if (wifiLock != null && wifiLock!!.isHeld) {
            wifiLock!!.release()
        }
        config.eventBroadcaster.removeListener(this)
        zeroConf.unregisterService()
        motionTask.stopCamera()
        terminateWakeWordDetection()
        stopSensors()
        server.stop()

    }
}
