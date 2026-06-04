package com.msp1974.vacompanion.satellite

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.device.Camera
import com.msp1974.vacompanion.device.SensorUpdatesCallback
import com.msp1974.vacompanion.device.Sensors
import com.msp1974.vacompanion.device.VolumeObserver
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.device.DeviceCapabilitiesData
import com.msp1974.vacompanion.device.DeviceCapabilitiesManager
import com.msp1974.vacompanion.device.ScreenUtils
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.wakeword.WakeWordDownloader
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingEvent
import com.msp1974.vacompanion.wyoming.WyomingPacket
import io.github.z4kn4fein.semver.toVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.intOrNull
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

interface ISatelliteEvent {
    fun onEvent(event: String, data: JsonObject)
    fun sendSatelliteMessage(clientId: String, type: String, data: JsonObject, payload: ByteArray = ByteArray(0))
}

enum class AudioRouteOption { NONE, DETECT, STREAM}


abstract class Satellite(var context: Context, val config: APPConfig, val scope: CoroutineScope, clientIdString: String, val deviceInfo: DeviceCapabilitiesData): ISatelliteEvent, EventListener {

    var clientId = clientIdString
    val mediaManager: SatelliteMediaManager = SatelliteMediaManager(context, config)

    private var hasInitSettings: Boolean = false

    private var sensorRunner: Sensors? = null
    var motionTask = Camera(context, config)

    private val eventHandler = SatelliteCustomEventHandler(context, config, scope, this)

    private var wakeWordHandler: SatelliteWakeWorkHandler? = null
    private var timerStopWordHandler: SatelliteWakeWorkHandler? = null
    private var wakeWordDownloader = WakeWordDownloader(context, config)
    private var audioPipeline: SatelliteAudioPipeline? = null
    private var audioPipelineId = AtomicInteger(0)
    private var audioPipelineLastStateChange = System.currentTimeMillis()
    private val activeTimers = mutableMapOf<String, VacaTimer>()
    private var soundingTimerId: String? = null

    private var soundEffectFinishTime: Long = 0

    var state: SatelliteState = SatelliteState.STOPPED
        set(value) {
            field = value
            config.isRunning = value == SatelliteState.RUNNING
        }
    private var volumeObserver: VolumeObserver? = null



    suspend fun start() {
        // Add config change listeners
        Timber.d("Satellite starting...")
        config.eventBroadcaster.addListener(this)
        state = SatelliteState.STARTING

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

        // Not ready for release
        //wakeWordDownloader.downloadsNeeded(config.customFiles)

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
            startSensors()
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
                while (!hasInitSettings) {
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

    fun sendDeviceStates() {
        sendStatus(
            buildJsonObject {
                putJsonObject("sensors", {
                    put("do_not_disturb", DeviceCapabilitiesManager.isDoNotDisturbEnabled(context))
                    put("screen_on", ScreenUtils(context, config).isScreenOn())
                })
                putJsonObject("media_player", {
                    put("playing", false)
                })
            }
        )
    }

    suspend fun handleSatelliteTakeover(clientId: String) {
        hasInitSettings = false
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

        stopAudioPipeline()

        mediaManager.stopAll()

        volumeObserver?.unregister()

        scope.launch {
            eventHandler.stop()
            wakeWordHandler?.stop()
        }.join()

        motionTask.stopCamera()
        stopSensors()
        state = SatelliteState.STOPPED
        config.eventBroadcaster.removeListener(this)
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STOPPED)
    }

    fun validateAppVersion(): Boolean {
        // Verify app version
        if (config.version.toVersion() < config.minRequiredApkVersion.toVersion() ) {
            Timber.w("Does not meet min app version requirement. Version: ${config.version}, Min: ${config.minRequiredApkVersion} ")
            return false
        }
        return true
    }

    suspend fun processMessage(packet: WyomingPacket) {
        when (packet.type) {
            WyomingEvent.CUSTOM_EVENT -> customEventHandler(clientId, packet)
            WyomingEvent.TIMER_STARTED,
            WyomingEvent.TIMER_UPDATED,
            WyomingEvent.TIMER_CANCELLED,
            WyomingEvent.TIMER_FINISHED -> handleTimerEvent(packet)
            else -> {
                if (audioPipeline != null && audioPipeline?.isAcceptingMessages == true) {
                    audioPipeline?.processAudioPipelineMessage(packet)
                } else if (packet.type == WyomingEvent.AUDIO_START) {
                    handleAudioStart(packet)
                } else if (packet.type == WyomingEvent.TRANSCRIBE) {
                    handleTranscribe(packet)
                } else {
                    Timber.d("Ignoring unhandled Wyoming packet: ${packet.type} ${packet.data}")
                }
            }
        }
    }

    override fun onEventTriggered(event: Event) {
        if (event.eventName == "timerDismissRequested") {
            val timerId = event.newValue as? String
            scope.launch {
                dismissTimerAlert(timerId)
            }
        }
    }

    private suspend fun handleTimerEvent(packet: WyomingPacket) {
        Timber.i("VACA timer event handled: ${packet.type} ${packet.data}")
        when (packet.type) {
            WyomingEvent.TIMER_STARTED -> handleTimerStarted(packet)
            WyomingEvent.TIMER_UPDATED -> handleTimerUpdated(packet)
            WyomingEvent.TIMER_CANCELLED -> handleTimerCancelled(packet)
            WyomingEvent.TIMER_FINISHED -> handleTimerFinished(packet)
        }
    }

    private suspend fun handleTimerStarted(packet: WyomingPacket) {
        val timer = parseTimer(packet) ?: return
        activeTimers[timer.id] = timer
        config.eventBroadcaster.notifyEvent(Event("timerStarted", "", timer.toUiState()))
    }

    private suspend fun handleTimerUpdated(packet: WyomingPacket) {
        val existing = packet.timerId()?.let { activeTimers[it] }
        val timer = parseTimer(packet, existing) ?: return
        activeTimers[timer.id] = timer
        config.eventBroadcaster.notifyEvent(Event("timerUpdated", "", timer.toUiState()))
    }

    private fun handleTimerCancelled(packet: WyomingPacket) {
        val timerId = packet.timerId()
        if (timerId == null) {
            Timber.w("Timer cancelled packet missing id: ${packet.data}")
            return
        }
        activeTimers.remove(timerId)
        config.eventBroadcaster.notifyEvent(Event("timerCancelled", "", timerId))
    }

    private suspend fun handleTimerFinished(packet: WyomingPacket) {
        val timerId = packet.timerId()
        if (timerId == null) {
            Timber.w("Timer finished packet missing id: ${packet.data}")
            return
        }

        val timer = activeTimers.remove(timerId)
        val uiState = timer?.toUiState(isFinished = true) ?: VacaTimerUiState(
            id = timerId,
            isFinished = true
        )

        soundingTimerId = timerId
        config.eventBroadcaster.notifyEvent(Event("timerFinished", "", uiState))
        startTimerStopWordDetection()
        handleAlarmAction(true)
    }

    private suspend fun dismissTimerAlert(timerId: String?) {
        val currentTimerId = soundingTimerId
        if (timerId.isNullOrBlank() || currentTimerId == null || timerId == currentTimerId) {
            soundingTimerId = null
            stopTimerStopWordDetection()
            handleAlarmAction(false)
            config.eventBroadcaster.notifyEvent(Event("timerDismissed", "", timerId ?: currentTimerId.orEmpty()))
        }
    }

    private suspend fun startTimerStopWordDetection() {
        if (timerStopWordHandler != null) {
            return
        }

        Timber.i("Starting timer stop-word detection")
        stopWakeWordDetection()
        withContext(Dispatchers.Default) {
            timerStopWordHandler = object : SatelliteWakeWorkHandler(
                context = context,
                config = config,
                scope = scope,
                engineOverride = WakeWordEngineModel.MICROWAKEWORD,
                activeWakeWordsOverride = emptyList(),
                activeStopWordsOverride = listOf("stop")
            ) {
                override fun onStateChange(state: WakeWordHandlerState) {
                    Timber.d("Timer stop-word handler state: $state")
                }

                override suspend fun onAudio(audio: WakeWordEngineProvider.AudioResult.Audio) {}

                override suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {}

                override suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                    Timber.i("Timer stop word detected: $detection")
                    scope.launch {
                        dismissTimerAlert(soundingTimerId)
                    }
                }

                override fun onDiagnostics(level: Float, lastDetectionLevel: Float) {
                    sendDiagnostics(level, lastDetectionLevel)
                }
            }.also {
                it.run()
            }
        }
    }

    private suspend fun stopTimerStopWordDetection() {
        val handler = timerStopWordHandler ?: return
        Timber.i("Stopping timer stop-word detection")
        timerStopWordHandler = null
        handler.stop()
        restartWakeWordDetection()
    }

    private fun parseTimer(packet: WyomingPacket, existing: VacaTimer? = null): VacaTimer? {
        val timerId = packet.timerId()
        if (timerId == null) {
            Timber.w("Timer packet missing id: ${packet.type} ${packet.data}")
            return null
        }

        val remainingSeconds = packet.intProp("total_seconds")
            ?: packet.intProp("seconds")
            ?: existing?.remainingSeconds
            ?: packet.intProp("start_seconds")
            ?: 0
        val durationSeconds = packet.intProp("start_seconds")
            ?: existing?.durationSeconds
            ?: remainingSeconds
        val name = packet.data["name"]?.jsonPrimitive?.contentOrNull ?: existing?.name

        return VacaTimer(
            id = timerId,
            name = name,
            durationSeconds = durationSeconds.coerceAtLeast(0),
            remainingSeconds = remainingSeconds.coerceAtLeast(0),
            updatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
            isActive = packet.type != WyomingEvent.TIMER_CANCELLED && packet.type != WyomingEvent.TIMER_FINISHED
        )
    }

    private fun WyomingPacket.timerId(): String? {
        return data["id"]?.jsonPrimitive?.contentOrNull
    }

    private fun WyomingPacket.intProp(name: String): Int? {
        return data[name]?.jsonPrimitive?.intOrNull
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun startWakeWordDetection() {
        if (wakeWordHandler != null) {
            wakeWordHandler?.stop()
            wakeWordHandler = null
        }

        if (config.wakeWord == "none") {
            return
        }

        Timber.d("Starting Wake Word Detection")
        sendDiagnostics(0f, 0f)
        withContext(Dispatchers.Default) {
            wakeWordHandler = object : SatelliteWakeWorkHandler(context, config, scope) {
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
                        if (soundingTimerId != null) {
                            dismissTimerAlert(soundingTimerId)
                        } else {
                            handleAlarmAction(false, "")
                        }
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
        if (clientId == "") {
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
                mediaManager.soundPlayer.play(
                    context.resources.getIdentifier(
                        config.wakeWordSound,
                        "raw",
                        context.packageName
                    )
                )
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

    fun playErrorSound() {
        try {
            scope.launch {
                mediaManager.soundPlayer.play(
                    context.resources.getIdentifier(
                        "error",
                        "raw",
                        context.packageName
                    )
                )
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
                val isCurrentPipeline = audioPipeline === this
                Timber.d(
                    "Closed audio pipeline. pipelineId=${this.pipelineId} " +
                        "startMode=${this.pipelineStartMode} reason=$reason"
                )

                if (reason == PipelineEndReason.END_OF_PIPELINE) {
                    Timber.i("Pipeline ended.  Restarting: $continueConversation")
                    if (continueConversation) {
                        if (isCurrentPipeline) {
                            audioPipeline = null
                        }
                        scope.launch {
                            while (mediaManager.voicePlayer.isRunning()) {
                                delay(10)
                            }
                            startAudioPipeline(PipelineStartMode.CONTINUE_CONVERSATION)
                        }
                    } else if (isCurrentPipeline) {
                        audioPipeline = null
                    }
                } else if (isCurrentPipeline) {
                    audioPipeline = null
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
                "screen-wake" -> config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
                "screen-sleep" -> config.eventBroadcaster.notifyEvent(Event("screenSleep", "", ""))
                "wake" -> scope.launch {handleWakeWordDetection()}
                "alarm" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    if (payload["activate"]?.jsonPrimitive?.booleanOrNull == true) {
                        soundingTimerId = null
                    }
                    handleAlarmAction(payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false, payload["url"]?.jsonPrimitive?.contentOrNull ?: "")
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

    private suspend fun handleAlarmAction(enable: Boolean, url: String = "") {
        withContext(Dispatchers.Main) {
            if (enable) {
                mediaManager.alarmPlayer.start(url)
                config.eventBroadcaster.notifyEvent(Event("screenWake", "", ""))
            } else {
                mediaManager.alarmPlayer.stop()
            }
            sendSetting("alarm", enable)
        }
    }

    private fun handleSettings(settings: String) {
        config.processSettings(settings)
        hasInitSettings = true
    }

    private fun handleCapabilities(clientId: String) {
        sendSatelliteMessage(clientId, "capabilities", DeviceCapabilitiesManager.toJson(deviceInfo))
    }

    // *************************************************************************
    // Send messages
    // *************************************************************************
    fun sendStatus(data: JsonObject) {
        sendCustomEvent("status", data)
    }

    fun sendScreenStatus(active: Boolean) {
        sendStatus(
            buildJsonObject {
                put("timestamp", isoNow())
                putJsonObject("sensors") {
                    put("screen_on", active)
                }
            }
        )
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
            mediaManager.soundPlayer.preload(R.raw.error)
            if (config.wakeWordSound != "none") {
                val resId = context.resources.getIdentifier(
                    config.wakeWordSound,
                    "raw",
                    context.packageName
                )
                if (resId != 0) {
                    mediaManager.soundPlayer.preload(resId)
                }
            }
        }
    }

    fun startSensors() {
        sensorRunner = Sensors(context, config, object : SensorUpdatesCallback {
            override fun onUpdate(data: MutableMap<String, Any>) {
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
                }
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    companion object {
        fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    }

}
