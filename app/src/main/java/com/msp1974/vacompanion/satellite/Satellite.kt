package com.msp1974.vacompanion.satellite

import android.annotation.SuppressLint
import android.content.Context
import androidx.media3.common.Player
import androidx.core.net.toUri
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.device.Camera
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.VolumeObserver
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.SoundControl.Companion.isDoNotDisturbEnabled
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingCapabilitiesBuilder
import com.msp1974.vacompanion.wyoming.WyomingInfoBuilder
import com.msp1974.vacompanion.wyoming.WyomingPacket
import io.github.z4kn4fein.semver.toVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.seconds

interface ISatelliteEvent {
    fun onEvent(event: String, data: JsonObject)
    fun sendSatelliteMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0))
}

enum class AudioRouteOption { NONE, DETECT, STREAM}


abstract class Satellite(var context: Context, val deviceManager: DeviceManager, val scope: CoroutineScope, clientIdString: String): ISatelliteEvent, EventListener {

    val config = deviceManager.config
    val deviceInfo = deviceManager.deviceInfo

    var clientId = clientIdString
    val mediaManager: SatelliteMediaManager = SatelliteMediaManager(context, config)

    var sensorJob: Job? = null
    var motionTask = Camera(context, config)

    private val customFilesHandler = SatelliteCustomFilesHandler(context, deviceManager)

    private val eventHandler = SatelliteCustomEventHandler(context, deviceManager, scope, this)

    private var wakeWordHandler: SatelliteWakeWorkHandler? = null
    private var audioPipeline: SatelliteAudioPipeline? = null
    private var audioPipelineId = AtomicInteger(0)
    private var audioPipelineLastStateChange = System.currentTimeMillis()

    private var soundEffectFinishTime: Long = 0
    private var currentWakeWordSoundUri: android.net.Uri? = null

    private var _satelliteState = MutableStateFlow(SatelliteState.STOPPED)
    val satelliteState: StateFlow<SatelliteState> = _satelliteState.asStateFlow()

    var state: SatelliteState = SatelliteState.STOPPED
        set(value) {
            field = value
            _satelliteState.value = value
            config.isRunning = value == SatelliteState.RUNNING
        }
    private var volumeObserver: VolumeObserver? = null

    suspend fun start() {
        // Add config change listeners
        Timber.d("Satellite starting...")
        state = SatelliteState.STARTING
        config.eventBroadcaster.addListener(this)

        val loadedSettings = waitForSettings()
        if (!loadedSettings) {
            // Try 1 more time in case of timing issue
            sendSatelliteMessage(clientId,"custom-event", buildJsonObject {
                put("event_type", "settings")
            })
            if (!waitForSettings(2000)) {
                state = SatelliteState.ERROR
                return
            }
        }

        // Verify app version
        if (!validateAppVersion()) {
            BroadcastSender.sendBroadcast(context, BroadcastSender.VERSION_MISMATCH)
            state = SatelliteState.STOPPED
            return
        }

        customFilesLoader()

        volumeObserver = VolumeObserver(context) { musicVolume, notificationVolume ->
            if (config.musicVolume != musicVolume) {
                config.musicVolume = musicVolume
                sendSetting("music_volume", musicVolume)
            }

            if (config.notificationVolume != notificationVolume) {
                config.notificationVolume = notificationVolume
                sendSetting("notification_volume", notificationVolume)
            }
        }
        volumeObserver?.register()

        val startTime = System.currentTimeMillis()
        scope.launch {
            warmUpAudioResources()
            startSensorMonitor()
            startWakeWordDetection()
            eventHandler.run()
        }

        sendDeviceStates()

        Timber.d("Satellite started in ${System.currentTimeMillis() - startTime}ms")

        state = SatelliteState.RUNNING
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)
    }


    suspend fun waitForSettings(waitTime: Long = 10000): Boolean {
        try {
            withTimeout(waitTime) {
                // Wait for settings to be processed
                while (!config.initSettings) {
                    delay(10)
                }
                Timber.d("Initial settings downloaded")
            }
            return true
        } catch (e: Exception) {
            Timber.e("Error waiting for settings: ${e.message.toString()}")
            return false
        }
    }

    suspend fun customFilesLoader() {
        // Refresh available sounds and alarms first so they are available for preloading
        config.availableWakeSounds = AvailableWakeSounds(context, deviceManager).get()
        config.availableAlarms = AvailableAlarms(context, deviceManager).get()

        // Look for custom files
        if (config.customFiles.toString() != "") {
            val result = customFilesHandler.downloadAllCustomFiles()
            if (result) {
                config.availableWakeWords = AvailableWakeWords(context).get()
                // Refresh again after download to include new files
                config.availableWakeSounds = AvailableWakeSounds(context, deviceManager).get()
                config.availableAlarms = AvailableAlarms(context, deviceManager).get()

                val infoBuilder = WyomingInfoBuilder(deviceManager)
                sendEvent("info", infoBuilder.buildInfo())

                sendCapabilities()
            }
        }
    }

    fun sendDeviceStates() {
        // TODO: Do not put screen state in here as conflicts with update in MainActivity on satellite start
        sendStatus(
            buildJsonObject {
                putJsonObject("sensors") {
                    put("do_not_disturb", isDoNotDisturbEnabled(context))
                    put("motion_detected", false)
                }
                putJsonObject("media_player") {
                    put("playing", false)
                }
            }
        )
    }

    suspend fun handleSatelliteTakeover(clientId: String) {
        config.initSettings = false
        val loadedSettings = waitForSettings(5000)
        if (!loadedSettings) {
            // Try 1 more time in case of timing issue
            sendSatelliteMessage(clientId,"custom-event", buildJsonObject {
                put("event_type", "settings")
            })
            if (!waitForSettings(2000)) {
                state = SatelliteState.ERROR
                return
            }
        }

        // Verify app version
        if (!validateAppVersion()) {
            stop()
            start()
        } else {
            this.clientId = clientId
            BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_CLIENT_UPDATED)
        }
    }

    suspend fun stop() {
        state = SatelliteState.STOPPING
        config.eventBroadcaster.removeListener(this)

        stopAudioPipeline()

        mediaManager.stopAll()

        volumeObserver?.unregister()

        scope.launch {
            eventHandler.stop()
            wakeWordHandler?.stop()
            motionTask.stopCamera()
            motionTask.release()
        }.join()

        stopSensors()
        state = SatelliteState.STOPPED
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
    }

    fun validateAppVersion(): Boolean {
        // Verify app version
        if (deviceInfo.software.appVersion.toVersion() < config.minRequiredApkVersion.toVersion() ) {
            Timber.w("Does not meet min app version requirement. Version: ${deviceInfo.software.appVersion}, Min: ${config.minRequiredApkVersion} ")
            return false
        }
        return true
    }

    suspend fun processMessage(packet: WyomingPacket) {
        when (packet.type) {
            "custom-event" -> customEventHandler(clientId, packet)
            else -> {
                if (audioPipeline != null && audioPipeline?.pipelineStage != PipelineStage.ENDED) {
                    audioPipeline?.processAudioPipelineMessage(packet)
                } else if (packet.type == "audio-start") {
                    handleAudioStart(packet)
                } else if (packet.type == "transcribe") {
                    handleTranscribe(packet)
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun startWakeWordDetection() {
        if (wakeWordHandler != null) {
            wakeWordHandler?.stop()
            wakeWordHandler = null
        }

        Timber.d("Starting Wake Word Detection")
        sendDiagnostics(0f, 0f)
        withContext(Dispatchers.Default) {
            wakeWordHandler = object : SatelliteWakeWorkHandler(context, deviceManager, scope) {
                override fun onStateChange(state: WakeWordHandlerState) {
                    Timber.d("Wake word handler state: $state")
                }

                override suspend fun onAudio(audio: WakeWordEngineProvider.AudioResult.Audio) {
                    sendAudio(audio)
                }

                override suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                    Timber.d("Wake word detected: $detection")
                    if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.STREAMING_TTS) {
                        onStopWordDetected(detection)
                    } else if (mediaManager.alarmPlayer.isSounding()) {
                        onStopWordDetected(detection)
                    } else {
                        handleWakeWordDetection()
                    }
                }

                override suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                    if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.STREAMING_TTS) {
                        audioPipeline?.stop()
                        audioPipeline = null
                    }

                    if (mediaManager.alarmPlayer.isSounding()) {
                        handleAlarmAction(false)
                    }

                    Timber.d("Stop word detected: $detection")
                }

                override fun onDiagnostics(level: Float, lastDetectionLevel: Float) {
                    sendDiagnostics(level, lastDetectionLevel)
                }
            }.also {
                Timber.d("Starting Wake Word Detection")
                it.run()
            }
        }
    }

    suspend fun stopWakeWordDetection() {
        wakeWordHandler?.stop()
        wakeWordHandler = null
    }


    suspend fun restartWakeWordDetection() {
        stopWakeWordDetection()
        warmUpAudioResources()
        startWakeWordDetection()
    }

    suspend fun handleWakeWordDetection() {
        if (clientId.isEmpty()) {
            Timber.e("Unable to run audio pipeline. Satellite not connected to HA")
            return
        }
        soundEffectFinishTime = 0L
        var startNewPipeline = audioPipeline == null || audioPipeline?.pipelineStage == PipelineStage.ENDED

        if (!startNewPipeline) {
            // Check if running pipeline is still valid.  Cancel if not.
            val currentAudioPipelineStageOrdinal = audioPipeline?.pipelineStage!!.ordinal
            val audioPipelineStateAge = ((System.currentTimeMillis() - audioPipelineLastStateChange) / 1000).toInt()

            if (currentAudioPipelineStageOrdinal <= PipelineStage.AWAITING_RESPONSE.ordinal && audioPipelineStateAge > 15) {
                startNewPipeline = true
                Timber.d("Pipeline timed out waiting response, starting new pipeline. State age: $audioPipelineStateAge")
            } else if (currentAudioPipelineStageOrdinal <= PipelineStage.STREAMING_TTS.ordinal && audioPipelineStateAge > 30) {
                startNewPipeline = true
                Timber.d("Pipeline timed out waiting TTS, starting new pipeline. State age: $audioPipelineStateAge")
            }
        }

        if (startNewPipeline) {
            if (audioPipeline != null) {
                audioPipeline?.stop()
                audioPipeline = null
            }

            // if wake up on ww, send event
            if (config.screenOnWakeWord) {
                config.screenOn = true
                config.screenSaver = false
            }
            startAudioPipeline(PipelineStartMode.WAKE_WORD_DETECTED)
            playWakeWordDetectionSound()
        }
    }

    suspend fun playWakeWordDetectionSound() {
        if (config.wakeWordSound != "none") {
            try {
                val soundUri = currentWakeWordSoundUri ?: resolveWakeSoundUri(config.wakeWordSound)
                
                if (soundUri != null) {
                    mediaManager.soundPlayer.play(soundUri)
                }

                Timber.i("Started wake word sound")
                scope.launch {
                    while(mediaManager.soundPlayer.state.value != Player.STATE_ENDED) {
                        delay(50)
                    }
                    audioPipeline?.silenceAudioBefore = System.currentTimeMillis()
                    Timber.i("Ended wake word sound")
                }
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
        } else {
            audioPipeline?.silenceAudioBefore = 1L
        }
    }

    private fun resolveWakeSoundUri(id: String): android.net.Uri? {
        if (id == "none") return null
        
        val sound = config.availableWakeSounds.find { it.id == id }
        if (sound != null) {
            return if (sound.custom) {
                java.io.File("${context.filesDir}/${CustomFileDownloader.CUSTOM_DIR}/${CustomFileDownloader.SOUNDS_DIR}", sound.filename).toUri()
            } else {
                "asset:///wakeSounds/${sound.filename}".toUri()
            }
        }
        return null
    }

    fun playErrorSound() {
        try {
            scope.launch {
                mediaManager.soundPlayer.play("asset:///other/error.mp3".toUri())
            }
        } catch (e: Exception) {
            Timber.e("Error playing error sound: ${e.message.toString()}")
        }
    }


    fun muteMicrophone(muted: Boolean) {
        runCatching {
            wakeWordHandler?.engine!!.setMuted(muted)
        }
    }


    suspend fun handleAudioStart(packet: WyomingPacket) {
        startAudioPipeline(PipelineStartMode.START_STREAM_TTS)
        audioPipeline?.processAudioPipelineMessage(packet)
    }

    suspend fun handleTranscribe(packet: WyomingPacket) {
        startAudioPipeline(PipelineStartMode.REQUESTED_BY_SERVER)
        audioPipeline?.processAudioPipelineMessage(packet)
    }

    @OptIn(ExperimentalAtomicApi::class)
    fun startAudioPipeline(startStage: PipelineStartMode) {
        if (audioPipeline != null) {
            audioPipeline?.stop()
            audioPipeline = null
        }
        val pipelineId = audioPipelineId.getAndAdd(1)
        audioPipeline = object: SatelliteAudioPipeline(context, scope, config, pipelineId, mediaManager) {
            override fun sendMessage(packet: WyomingPacket) {
                sendSatelliteMessage(clientId, packet.type, packet.data, packet.payload)
            }

            override fun onStateChange(state: PipelineStage) {
                Timber.d("Audio pipeline state: $state")
                audioPipelineLastStateChange = System.currentTimeMillis()
                when (state) {
                    PipelineStage.LISTENING -> {
                        wakeWordHandler?.engine!!.setStreaming(true)
                    }
                    PipelineStage.VOICE_STOPPED -> { wakeWordHandler?.engine!!.setStreaming(false) }
                    PipelineStage.ENDED -> {
                        if (wakeWordHandler?.engine!!.isStreaming()) {
                            wakeWordHandler?.engine!!.setStreaming(false)
                        }
                    }
                    else -> {}
                }
            }

            override fun onFinish(reason: PipelineEndReason, continueConversation: Boolean) {
                if (reason == PipelineEndReason.END_OF_PIPELINE) {
                    Timber.i("Pipeline ended.  Restarting: $continueConversation")
                    if (continueConversation) {
                        scope.launch {
                            while (mediaManager.voicePlayer.isRunning()) {
                                delay(10)
                            }
                            startAudioPipeline(PipelineStartMode.CONTINUE_CONVERSATION)
                        }
                    } else {
                        audioPipeline = null
                    }
                }
                if (reason == PipelineEndReason.ERRORED && config.wakeWordSound != "none") {
                    playErrorSound()
                }
            }

        }.also {
            it.run(startStage)
        }
    }

    suspend fun sendAudio(audio: WakeWordEngineProvider.AudioResult.Audio) {
        if (audioPipeline != null) {
            audioPipeline?.sendMicAudio(audio)
        }
    }

    fun stopAudioPipeline() {
        audioPipeline?.stop()
        audioPipeline = null
    }

    // *************************************************************************
    // Custom Events
    // *************************************************************************
    private suspend fun customEventHandler(clientId: String, packet: WyomingPacket) {
        val eventType = packet.getProp("event_type")

        when (eventType) {
            "action" -> handleAction(packet.getProp("action"), packet)
            "settings" -> handleSettings(packet.getProp("settings"))
            "capabilities" -> handleCapabilities(clientId)
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun handleAction(action: String, packet: WyomingPacket) {
        Timber.d("Action received: $action")
        runCatching {
            val payloadStr = packet.getProp("payload")
            when (action) {
                "play-media", "play", "pause", "stop", "set-volume" -> { handleMediaPlayerAction(action, payloadStr) }
                "toast-message" -> if (payloadStr.isNotEmpty()) {
                    val msg = Json.parseToJsonElement(payloadStr).jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, msg)
                }
                "refresh" -> config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
                "wake" -> scope.launch {handleWakeWordDetection()}
                "alarm" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    handleAlarmAction(payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
            }
        }.onFailure { Timber.e("Failed to handle custom action $action: $it") }
    }

    private suspend fun handleMediaPlayerAction(action: String, payloadStr: String) {
        withContext(Dispatchers.Main) {
            when (action) {
                "play-media" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    val url = payload["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val volume = payload["volume"]?.jsonPrimitive?.floatOrNull ?: 90f
                    mediaManager.musicPlayer.play(url, volume)
                }
                "play" -> mediaManager.musicPlayer.resume()
                "pause" -> mediaManager.musicPlayer.pause()
                "stop" ->  mediaManager.musicPlayer.stop()
                "set-volume" -> if (payloadStr.isNotEmpty()) {
                   mediaManager.musicPlayer.setVolume(Json.parseToJsonElement(payloadStr).jsonObject["volume"]?.jsonPrimitive?.floatOrNull ?: 90f)
                }
            }
        }
    }

    private suspend fun handleAlarmAction(enable: Boolean) {
        withContext(Dispatchers.Main) {
            if (enable) {
                val alarm = config.availableAlarms.find { it.id == config.alarmSound }
                val mediaUri = if (alarm != null) {
                    if (alarm.custom) {
                        java.io.File("${context.filesDir}/${CustomFileDownloader.CUSTOM_DIR}/${CustomFileDownloader.ALARMS_DIR}", alarm.filename).toUri().toString()
                    } else {
                        "asset:///alarm/${alarm.filename}"
                    }
                } else {
                    "asset:///alarm/default.mp3"
                }

                mediaManager.alarmPlayer.start(mediaUri)
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            } else {
                mediaManager.alarmPlayer.stop()
            }
            sendSetting("alarm", enable)
        }
    }

    private fun handleSettings(settings: String) {
        config.processSettings(settings)
        config.initSettings = true
    }

    fun sendCapabilities() {
        handleCapabilities(clientId)
    }

    private fun handleCapabilities(clientId: String) {
        val capabilitiesBuilder = WyomingCapabilitiesBuilder(deviceManager)
        sendSatelliteMessage(clientId,"capabilities", capabilitiesBuilder.buildInfo())
    }

    // *************************************************************************
    // Send messages
    // *************************************************************************
    fun sendStatus(data: JsonObject) {
        sendCustomEvent("status", data)
    }

    fun sendSetting(name: String, value: Any) {
        sendCustomEvent("settings", buildJsonObject {
            put("timestamp", isoNow())
            putJsonObject("settings") {
                when (value) {
                    is String -> put(name, value)
                    is Boolean -> put(name, value)
                    is Number -> put(name, value)
                    is JsonElement -> put(name, value)
                }
            }
        })
    }

    fun sendEvent(type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        sendSatelliteMessage(clientId, type, data, payload)
    }

    fun sendCustomEvent(type: String, data: JsonObject) {
        sendSatelliteMessage(clientId, "custom-event", buildJsonObject {
            put("event_type", type)
            put("data", data)
        })
    }



    // *************************************************************************
    // ****
    // *************************************************************************
    @SuppressLint("DiscouragedApi")
    private suspend fun warmUpAudioResources() {
        withContext(Dispatchers.Main) {
            mediaManager.soundPlayer.preload("asset:///other/error.mp3".toUri())

            if (config.wakeWordSound != "none") {
                val newUri = resolveWakeSoundUri(config.wakeWordSound)

                if (newUri != null && newUri != currentWakeWordSoundUri) {
                    currentWakeWordSoundUri?.let { oldUri ->
                        mediaManager.soundPlayer.unload(oldUri)
                    }
                    mediaManager.soundPlayer.preload(newUri)
                    currentWakeWordSoundUri = newUri
                }
            } else {
                currentWakeWordSoundUri?.let { oldUri ->
                    mediaManager.soundPlayer.unload(oldUri)
                    currentWakeWordSoundUri = null
                }
            }
        }
    }

    suspend fun startSensorMonitor() {
        sensorJob = scope.launch {
            deviceManager.sensors.collect { data ->
                Timber.d("Sensors: $data")
                val data = buildJsonObject {
                    put("timestamp", Date().toString())
                    putJsonObject("sensors") {
                        // TODO: Use a formalized sensor mapping schema instead of manual type checking and casting.
                        data.forEach { (key, value) ->
                            when (value) {
                                is Boolean -> put(key, value)
                                is Number -> put(key, value.toFloat())
                                else -> {
                                    if (Helpers.isNumber(value.toString())) {
                                        put(key, value.toString().toFloat())
                                    } else {
                                        put(key, value.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                sendStatus(data)
            }
        }

        // Start motion sensor
        if (config.motionDetectionMode != "none") {
            delay(2.seconds)  // Add delay to camera start to let app start up
            motionTask.startCamera()
        }
    }

    suspend fun stopSensors() {
        sensorJob?.cancel()
        motionTask.stopCamera()
    }

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = audioLevel * 100,
                detectionLevel = detectionLevel * 10,
                detectionThreshold = config.wakeWordThreshold * 10,
                wakeWord = config.wakeWord,
                mode = if (wakeWordHandler?.state != WakeWordHandlerState.RUNNING) {
                    AudioRouteOption.NONE
                } else if (wakeWordHandler?.engine?.isStreaming() ?: false) {
                    AudioRouteOption.STREAM
                } else {
                    AudioRouteOption.DETECT
                },
                motionDetectionMode = config.motionDetectionMode
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "requestSettings") {
            scope.launch {
                if (clientId != "") {
                    sendSatelliteMessage(clientId, "custom-event", buildJsonObject {
                        put("event_type", "settings")
                    })
                }
            }
        }
    }

    companion object {
        fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }
}
