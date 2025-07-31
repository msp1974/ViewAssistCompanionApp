package com.msp1974.vacompanion.service

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import com.msp1974.vacompanion.Zeroconf
import com.msp1974.vacompanion.audio.AudioInCallback
import com.msp1974.vacompanion.audio.AudioRecorderThread
import com.msp1974.vacompanion.audio.WakeWordSoundPlayer
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.openwakeword.Model
import com.msp1974.vacompanion.openwakeword.ONNXModelRunner
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.settings.InterfaceConfigChangeListener
import com.msp1974.vacompanion.utils.Logger
import com.msp1974.vacompanion.wyoming.WyomingTCPServer
import com.msp1974.vacompanion.wyoming.WyomingCallback
import com.msp1974.vacompanion.utils.Helpers
import kotlin.concurrent.thread
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.sensors.SensorUpdatesCallback
import com.msp1974.vacompanion.sensors.Sensors
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.SoundControl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Date

enum class AudioRouteOption { NONE, DETECT, STREAM}

internal class BackgroundTaskController (private val context: Context): Thread(), EventListener {

    private val log = Logger()
    private var config: APPConfig = APPConfig.getInstance(context)

    var modelRunner: ONNXModelRunner? = null
    var model: Model? = null
    var audioRoute: AudioRouteOption = AudioRouteOption.NONE
    var recorder: AudioRecorderThread? = null
    val audioDSP: AudioDSP = AudioDSP()
    private var sensorRunner: Sensors? = null
    lateinit var assetManager: AssetManager
    lateinit var server: WyomingTCPServer

    var wwDetect = true
    var calm: Int = 0


    override fun run() {
        assetManager = context.assets

        // Start wyoming server
        server = WyomingTCPServer(context, config.serverPort, object : WyomingCallback {
            override fun onSatelliteStarted() {
                log.i("Background Task - Connection detected")
                startSensors(context)
                startOpenWakeWordDetection()
                startInputAudio(context)
                audioRoute = AudioRouteOption.DETECT
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
            }

            override fun onSatelliteStopped() {
                log.i("Background Task - Disconnection detected")
                BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
                if (sensorRunner != null) {
                    sensorRunner!!.stop()
                    sensorRunner = null
                }
                audioRoute = AudioRouteOption.NONE
                stopInputAudio()
                stopOpenWakeWordDetection()
                stopSensors()
            }

            override fun onRequestInputAudioStream() {
                log.i("Streaming audio to server")
                if (audioRoute == AudioRouteOption.DETECT) {
                    audioRoute = AudioRouteOption.STREAM
                    stopOpenWakeWordDetection()
                }
            }

            override fun onReleaseInputAudioStream() {
                log.i("Stopped streaming audio to server")
                if (audioRoute == AudioRouteOption.STREAM) {
                    thread{startOpenWakeWordDetection()}
                    audioRoute = AudioRouteOption.DETECT
                }
            }
        })
        thread { server.start() }

        // Add config change listeners
        config.eventBroadcaster.addListener(this)

        // Start mdns server
        log.i("Starting mdns server")
        Zeroconf(context).registerService(config.serverPort)
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            "notificationVolume" -> {
                log.i("BackgroundTask - notificationVolume changed to ${event.newValue}")
                setVolume(AudioManager.STREAM_NOTIFICATION, event.newValue as Float)
            }
            "musicVolume" -> {
                log.i("BackgroundTask - musicVolume changed to ${event.newValue}")
                setVolume(AudioManager.STREAM_MUSIC, event.newValue as Float)
            }
            "wakeWord" -> {
                log.i("BackgroundTask - wakeWord changed to ${event.newValue}")
                if (audioRoute != AudioRouteOption.NONE) {
                    restartWakeWordDetection()
                }
            }
            "doNotDisturb" -> {
                log.i("BackgroundTask - doNotDisturb changed to ${event.newValue}")
                setDoNotDisturb(event.newValue as Boolean)
            }
        }
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
    }

    fun stopSensors() {
        sensorRunner?.stop()
    }

    fun startInputAudio(context: Context) {
        try {
            log.i("Starting input audio")
            recorder = AudioRecorderThread(context, object : AudioInCallback {
                override fun onAudio(audioBuffer: ShortArray) {
                    if (audioRoute == AudioRouteOption.DETECT) {
                        var floatBuffer = audioDSP.normaliseAudioBuffer(audioBuffer)
                        processAudioToWakeWordEngine(context, floatBuffer)
                    } else if (audioRoute == AudioRouteOption.STREAM) {
                        var bAudioBuffer = audioDSP.shortArrayToByteBuffer(audioDSP.autoGain(audioBuffer, config.micGain))
                        server.sendAudio(bAudioBuffer)
                    }
                }
                override fun onError(err: String) {
                }
            })
            recorder?.start()
        } catch (e: Exception) {
            log.d("Error starting mic audio: ${e.message.toString()}")
        }
    }

    fun stopInputAudio() {
        try {
            log.i("Stopping input audio")
            recorder?.stopRecording()
            recorder = null
        } catch (e: Exception) {
            log.d("Error stopping input audio: ${e.message.toString()}")
        }
    }

    fun processAudioToWakeWordEngine(context: Context, audioBuffer: FloatArray) {
        try {
            if (model != null) {
                val res = model!!.predict_WakeWord(audioBuffer).toFloat()
                if (res >= 0.1) {
                    log.d("Wakeword probability value: $res")
                }
                if (res >= config.wakeWordThreshold && calm == 0) {
                    log.i("Wake word detected at $res, theshold is ${config.wakeWordThreshold}")

                    if (config.wakeWordSound != "none") {
                        WakeWordSoundPlayer(
                            context,
                            context.resources.getIdentifier(
                                config.wakeWordSound,
                                "raw",
                                context.packageName
                            )
                        ).play()
                    }
                    BroadcastSender.sendBroadcast(context, BroadcastSender.WAKE_WORD_DETECTED)
                    //model!!.reset()

                    // Process 20 audio buffers before sending detection event again
                    calm = 20
                }
                if (calm > 0) {
                    --calm
                }
            }
        } catch (e: Exception) {
            log.d("Error processing to wake word engine: ${e.message.toString()}")
        }
    }

    fun shutdown() {
        Zeroconf(context).unregisterService()
        stopInputAudio()
        stopOpenWakeWordDetection()
        stopSensors()
        server.stop()

    }

    fun startOpenWakeWordDetection() {
        // Init wake word detection
        log.i("Starting wake word detection")
        try {
            modelRunner = ONNXModelRunner(assetManager, config.wakeWord)
            model = Model(context, modelRunner)
        } catch (e: Exception) {
            log.d("Error starting wake word detection: ${e.message.toString()}")
        }
    }

    fun stopOpenWakeWordDetection() {
        log.i("Stopping wake word detection")
        model = null
    }

    fun restartWakeWordDetection() {
        if (config.initSettings) {
            log.i("Restarting wake word detection")
            wwDetect = false
            stopOpenWakeWordDetection()
            startOpenWakeWordDetection()
            wwDetect = true
        }
    }

    fun setVolume(stream: Int, volume: Float) {
        val audioManager = com.msp1974.vacompanion.audio.AudioManager(context)
        audioManager.setVolume(stream, volume)
    }

    fun setDoNotDisturb(enable: Boolean) {

        val sound = SoundControl(context)

        if (enable) {
            // Mute mic
            //stopInputAudio()
            // Enable silent mode
            sound.setSoundMode(AudioManager.RINGER_MODE_SILENT)

        } else {
            // Un-mute mic
            //startInputAudio(context)
            // Disable silent mode
            sound.setSoundMode(AudioManager.RINGER_MODE_NORMAL)
        }
    }
}