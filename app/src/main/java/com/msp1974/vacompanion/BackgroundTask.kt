package com.msp1974.vacompanion

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioManager
import kotlin.concurrent.thread
import kotlin.reflect.KProperty
/*
internal interface taskCallback {
    fun onPrediction(res: String)
    fun onWakeWordDetected()
    fun onListening()
    fun onActive()
}
*/

internal class BackgroundTask (private val context: Context): Thread() {

    var config: Config = Global.config
    var status: Status = Global.status
    lateinit var assetManager: AssetManager

    var modelRunner: ONNXModelRunner? = null
    var model: Model? = null
    private var zeroconf: Zeroconf = Zeroconf(context)
    var calm: Int = 0
    lateinit var server: WyomingTCPServer
    var streamAudio: Boolean = false
    var recorder: AudioRecorderThread? = null
    var wwDetect = true


    override fun run() {
        assetManager = context.assets

        // Set volumes
        setVolume(AudioManager.STREAM_NOTIFICATION, config.notificationVolume)
        setVolume(AudioManager.STREAM_MUSIC, config.musicVolume)

        // Add volume change listener
        config.configChangeListeners.add(object : InterfaceConfigChangeListener {
            override fun onConfigChange(property: KProperty<*>, oldValue: Any, newValue: Any) {
                if (property.name == "notificationVolume") {
                    Global.log.i("Setting notification volume to $newValue")
                    setVolume(AudioManager.STREAM_NOTIFICATION, newValue as Float)
                }
                if (property.name == "musicVolume") {
                    Global.log.i("Setting music volume to $newValue")
                    setVolume(AudioManager.STREAM_MUSIC, newValue as Float)
                }
                if (property.name == "systemVolume") {
                    Global.log.i("Setting system volume to $newValue")
                    setVolume(AudioManager.STREAM_SYSTEM, newValue as Float)
                }
                if (property.name == "wakeword") {
                    restartWakeWordDetection()
                }
            }
        })

        // Start wyoming server
        server = WyomingTCPServer(context, config.port, object : wyomingCallback {
            override fun onConnected() {
                // We could pass the clientHandler here and use it for something??
                // Maybe get pipeline status fro it and use to direct audio
                // slash pass wake word detected event
                startWakeWordDetection()
                startAudio(context)

            }

            override fun onDisconnected() {
                stopAudio()
                stopWakeWordDetection()

            }

            override fun onStartStreaming() {
                Global.log.i("Streaming audio to server")
                streamAudio = true
            }

            override fun onStopStreaming() {
                Global.log.i("Stopped streaming audio to server")
                streamAudio = false
            }
        })
        thread { server.start() }

        // Start mdns server
        zeroconf.registerService(config.port)
    }

    fun startAudio(context: Context) {
        recorder = AudioRecorderThread(object : AudioInCallback {
            override fun onAudio(audioBuffer: ShortArray) {
                if (streamAudio) {
                    server.sendAudio(convertAudioToByteBuffer(audioBuffer))
                } else {
                    processAudioToWakeWordEngine(context, normaliseAudioBuffer(audioBuffer))
                }
            }

            override fun onError(err: String) {
            }
        })
        recorder?.start()
    }

    fun stopAudio() {
        recorder?.stopRecording()
    }

    fun processAudioToWakeWordEngine(context: Context, floatBuffer: FloatArray) {
        try {
            val res = model!!.predict_WakeWord(floatBuffer)
            if (res.toFloat() > config.wakeword_threshold && calm == 0) {
                Global.log.i("Wake word detected")
                if (config.wakeword_sound != "none") {
                    WakeWordSoundPlayer(
                        context,
                        context.resources.getIdentifier(
                            config.wakeword_sound,
                            "raw",
                            context.packageName
                        )
                    ).play()
                }

                // Start pipeline
                server.pipelineClient?.sendDetection()
                server.pipelineClient?.startPipeline()

                // Process 20 audio buffers before sending detection event again
                calm = 20
            }
            if (calm > 0) {
                --calm
            }
        } catch (e: Exception) {
            Global.log.d("Error processing to wakeword engine: ${e.message.toString()}")
        }
    }

    private fun convertAudioToByteBuffer(audioBuffer: ShortArray): ByteArray {
        val byteBuffer = ByteArray(audioBuffer.size * 2)
        for (i in audioBuffer.indices) {
            val value: Int = (audioBuffer[i] * config.micGain)
            byteBuffer[i * 2] = (value and 0xFF).toByte()
            byteBuffer[i * 2 + 1] = (value shr 8).toByte()
        }
        return byteBuffer
    }

    private fun normaliseAudioBuffer(audioBuffer: ShortArray): FloatArray {
        val floatBuffer = FloatArray(audioBuffer.size)
        // Convert each short to float
        for (i in audioBuffer.indices) {
            // Convert by dividing by the maximum value of short to normalize
            floatBuffer[i] =
                (audioBuffer[i] / 32768.0f) * config.micGain // Normalize to range -1.0 to 1.0 if needed
        }
        return floatBuffer
    }

    fun shutdown() {
        zeroconf.unregisterService()
        recorder?.stopRecording()
        server.stop()

    }

    fun startWakeWordDetection() {
        // Init wake word detection
        Global.log.i("Starting wake word detection")
        try {
            modelRunner = ONNXModelRunner(assetManager, config.wakeword)
            model = Model(modelRunner)
        } catch (e: Exception) {
            Global.log.d(e.message.toString())
        }
    }

    fun stopWakeWordDetection() {
        Global.log.i("Stopping wake word detection")
    }

    fun restartWakeWordDetection() {
        if (config.initSettings) {
            Global.log.i("Restarting wake word detection")
            wwDetect = false
            stopWakeWordDetection()
            startWakeWordDetection()
            wwDetect = true
        }
    }

    fun setVolume(stream: Int, volume: Float) {
        val audioManager = AudioManager(context)
        audioManager.setVolume(stream, volume)
    }
}