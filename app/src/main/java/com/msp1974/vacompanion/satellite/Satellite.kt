package com.msp1974.vacompanion.satellite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.core.net.toUri
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.AudioOutputRouter
import com.msp1974.vacompanion.audio.DetectionBuffer
import com.msp1974.vacompanion.audio.MicLevelMonitor
import com.msp1974.vacompanion.audio.MicrophoneAudioStream
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.broadcasts.VacaUdpBroadcast
import com.msp1974.vacompanion.data.AvailableAlarms
import com.msp1974.vacompanion.data.AvailableWakeSounds
import com.msp1974.vacompanion.device.camera.CameraAvailability
import com.msp1974.vacompanion.device.camera.MotionDetector
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.device.VolumeObserver
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.audio.AudioInRouter
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Helpers
import com.msp1974.vacompanion.utils.LogcatReader
import com.msp1974.vacompanion.wakeword.AvailableWakeWords
import com.msp1974.vacompanion.utils.EventListener
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.utils.SoundControl.Companion.isDoNotDisturbEnabled
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wyoming.EVENT_TYPE
import com.msp1974.vacompanion.wyoming.SatelliteState
import com.msp1974.vacompanion.wyoming.WyomingCapabilitiesBuilder
import com.msp1974.vacompanion.wyoming.WyomingCustomEventType
import com.msp1974.vacompanion.wyoming.WyomingEvent
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineStart
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
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.text.ifEmpty
import kotlin.time.Duration.Companion.milliseconds
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
    var motionTask: MotionDetector? = null

    private val customFilesHandler = SatelliteCustomFilesHandler(context, deviceManager)

    private val eventHandler = SatelliteCustomEventHandler(context, deviceManager, scope, this)

    private val firebase = FirebaseManager.getInstance(context)

    private var wakeWordHandler: SatelliteWakeWorkHandler? = null
    private var audioPipeline: SatelliteAudioPipeline? = null
    private var audioPipelineLastStateChange = System.currentTimeMillis()

    // Consecutive automatic restarts attempted after the wake word engine failed on its own
    // (see onEngineFailure below). Reset once the engine reports RUNNING again, so a transient
    // fault gets a fresh budget next time rather than exhausting it permanently.
    private var wakeWordEngineRestartAttempts = 0
    // Start/stop/restart of wake word detection must not overlap: a settings push can change several
    // wake word settings at once, each asking for a restart, and overlapping restarts leave an
    // orphaned engine running that reports every wake word a second time.
    private val wakeWordLock = Mutex()

    // The only owner of the microphone. The wake word engine and any listening audio pipeline
    // subscribe to it; it is open only while at least one of them is.
    private val microphone = MicrophoneAudioStream(context, deviceManager, scope)

    private var soundEffectFinishTime: Long = 0
    private var currentWakeWordSoundUri: android.net.Uri? = null

    private var _satelliteState = MutableStateFlow(SatelliteState.STOPPED)
    val satelliteState: StateFlow<SatelliteState> = _satelliteState.asStateFlow()

    // WyomingServerStatus.satelliteRunning (derived from this via WyomingTCPServer.updateStatus)
    // is the single source of truth for "is the satellite running" app-wide - see VAViewModel.State.satelliteRunning.
    var state: SatelliteState = SatelliteState.STOPPED
        set(value) {
            field = value
            _satelliteState.value = value
        }
    private var volumeObserver: VolumeObserver? = null

    private var lastAudioLevel = 0f
    private var lastDetectionLevel = 0f

    private val audioLogManager: SatelliteAudioLog = SatelliteAudioLog(config).apply {
        onPlaybackStatusChanged = { playing ->
            muteMicrophone(playing)
            sendDiagnostics(lastAudioLevel, lastDetectionLevel)
        }
    }

    suspend fun start() {
        // Add config change listeners
        Timber.d("Satellite starting...")
        val startTime = System.currentTimeMillis()

        state = SatelliteState.STARTING
        config.eventBroadcaster.addListener(this)

        val loadedSettings = waitForSettings()
        if (!loadedSettings) {
            // Try 1 more time in case of timing issue
            sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
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

        // Before the observer is registered so it cannot read half-applied volumes back into config
        eventHandler.applyVolumes()

        volumeObserver =VolumeObserver(context) { musicVolume, notificationVolume, alarmVolume ->
            if (config.musicVolume != musicVolume) {
                config.musicVolume = musicVolume
                sendSetting("music_volume", musicVolume)
            }

            if (config.notificationVolume != notificationVolume) {
                config.notificationVolume = notificationVolume
                sendSetting("notification_volume", notificationVolume)
            }

            if (config.alarmVolume != alarmVolume) {
                config.alarmVolume = alarmVolume
                sendSetting("alarm_volume", alarmVolume)
            }
        }
        volumeObserver?.register()

        scope.launch {
            warmUpAudioResources()
            startSensorMonitor()
            startWakeWordDetection()
            eventHandler.run()
        }

        sendDeviceStates()

        Timber.d("Satellite started in ${System.currentTimeMillis() - startTime}ms")

        state = SatelliteState.RUNNING
        sendPendingSettings()
        BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_STARTED)

        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        AudioInRouter.addActiveMicInputListener(activeMicInputListener)
    }

    // HA lists the audio inputs and outputs from the capabilities, so tell it when they change.
    // Device events arrive in bursts (and once on registration for everything already present),
    // so they are debounced and only a real change in the lists is sent.
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioDeviceHandler = Handler(Looper.getMainLooper())
    private val audioDevicesChanged = Runnable {
        scope.launch {
            if (state == SatelliteState.RUNNING) {
                if (deviceInfo.refreshAudioDevices()) {
                    Timber.d("Audio devices changed - sending capabilities")
                    sendCapabilities()
                }
                // Connecting or removing a device can move the active input/output.
                sendAudioSettingsIfChanged()
            }
        }
    }

    // The ids of the input and output in use (AudioDeviceInfo ids, as listed in the
    // capabilities), so HA can match them to that list. -1 means none could be determined.
    private fun buildAudioSettings() = buildJsonObject {
        put("audio_input", AudioInRouter.activeMicInputId)
        put("audio_output", AudioOutputRouter.activeOutputId(context, config))
    }

    private var lastAudioSettings: JsonObject? = null

    private fun sendAudioSettingsIfChanged(force: Boolean = false) {
        val audio = buildAudioSettings()
        if (!force && audio == lastAudioSettings) return
        lastAudioSettings = audio
        sendCustomEvent(WyomingCustomEventType.SETTINGS, buildJsonObject {
            put("timestamp", isoNow())
            put("settings", audio)
        })
    }

    private val activeMicInputListener: () -> Unit = {
        if (state == SatelliteState.RUNNING) sendAudioSettingsIfChanged()
    }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = scheduleAudioDeviceCheck()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = scheduleAudioDeviceCheck()
    }

    private fun scheduleAudioDeviceCheck() {
        audioDeviceHandler.removeCallbacks(audioDevicesChanged)
        audioDeviceHandler.postDelayed(audioDevicesChanged, AUDIO_DEVICE_DEBOUNCE_MS)
    }


    suspend fun waitForSettings(waitTime: Long = 10000): Boolean {
        try {
            withTimeout(waitTime.milliseconds) {
                // Wait for settings to be processed
                while (!config.initSettings) {
                    delay(10.milliseconds)
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
                sendEvent(WyomingEvent.INFO, infoBuilder.buildInfo())

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
        sendAudioSettingsIfChanged(force = true)
    }

    fun prepareSatelliteTakeover(clientId: String) {
        // This must run before WyomingTCPServer resumes reading the new connection.
        // Otherwise its settings event can arrive before initSettings is reset.
        this.clientId = clientId
        config.initSettings = false
    }

    suspend fun handleSatelliteTakeover(clientId: String) {
        if (this.clientId != clientId) return

        val loadedSettings = waitForSettings(5000)
        if (!loadedSettings) {
            if (this.clientId != clientId) return

            // Explicitly request settings from the reconnected client
            sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
            })
            if (!waitForSettings(2000)) {
                if (this.clientId != clientId) return

                // Connection is live; continue with cached settings rather than
                // wedging the satellite into ERROR just because the refresh was slow.
                Timber.w("Settings not received after reconnect; continuing with cached settings")
            }
        }

        if (this.clientId != clientId) return

        // Verify app version
        if (!validateAppVersion()) {
            stop()
            start()
        } else {
            BroadcastSender.sendBroadcast(context, BroadcastSender.SATELLITE_CLIENT_UPDATED)
        }
    }

    suspend fun stop() {
        state = SatelliteState.STOPPING
        config.eventBroadcaster.removeListener(this)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        AudioInRouter.removeActiveMicInputListener(activeMicInputListener)
        audioDeviceHandler.removeCallbacks(audioDevicesChanged)

        stopAudioPipeline()

        mediaManager.stopAll()

        volumeObserver?.unregister()

        scope.launch {
            eventHandler.stop()
            wakeWordHandler?.stop()
            stopMotionDetection()
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
            WyomingEvent.CUSTOM_EVENT -> customEventHandler(clientId, packet)
            else -> {
                try {
                    if (audioPipeline != null && audioPipeline?.pipelineStage != PipelineStage.ENDED) {
                        audioPipeline?.processAudioPipelineMessage(packet)
                    } else if (packet.type == "audio-start") {
                        handleAudioStart(packet)
                    } else if (packet.type == "transcribe") {
                        handleTranscribe(packet)
                    }
                } catch (e: Exception) {
                    Timber.e("Error processing message: ${e.message.toString()}")
                }
            }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    suspend fun startWakeWordDetection() = wakeWordLock.withLock { startWakeWordDetectionLocked() }

    private suspend fun startWakeWordDetectionLocked() {
        if (wakeWordHandler != null) {
            wakeWordHandler?.stop()
            wakeWordHandler = null
        }

        Timber.i(
            "Experimental settings: wake word arbitration=${config.wakeWordArbitrationEnabled}, " +
                "ducking scope=${config.duckScope}, " +
                "suppress wake sound=${config.suppressWakeSound}, minimum mic level=${config.micMinLevel} dB, level calibration=${"%+.1f".format(config.wakeLevelOffsetDb)} dB"
        )
        Timber.d("Starting Wake Word Detection")
        sendDiagnostics(0f, 0f)
        withContext(Dispatchers.Default) {
            wakeWordHandler = object : SatelliteWakeWorkHandler(context, deviceManager, scope, microphone) {
                override fun onStateChange(state: WakeWordHandlerState) {
                    Timber.d("Wake word handler state: $state")
                    if (state == WakeWordHandlerState.RUNNING) {
                        wakeWordEngineRestartAttempts = 0
                    }
                }

                override fun onEngineFailure(error: Throwable) {
                    scope.launch { handleWakeWordEngineFailure(error) }
                }

                override suspend fun onAudio(audio: WakeWordEngineProvider.AudioResult.Audio) {
                    audioLogManager.onAudio(audio.audio)
                }

                override suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                    Timber.d("Wake word detected: $detection")
                    if (isResponding()) {
                        interruptResponse(detection)
                    } else if (mediaManager.alarmPlayer.isSounding()) {
                        onStopWordDetected(detection)
                    } else {
                        handleWakeWordDetection(detection)
                    }
                }

                override suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection) {
                    Timber.d("Stop word detected: $detection")
                    handleStopWord()
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

    suspend fun stopWakeWordDetection() = wakeWordLock.withLock { stopWakeWordDetectionLocked() }

    private suspend fun stopWakeWordDetectionLocked() {
        wakeWordHandler?.stop()
        wakeWordHandler = null
    }


    suspend fun restartWakeWordDetection() = wakeWordLock.withLock {
        stopWakeWordDetectionLocked()
        warmUpAudioResources()
        startWakeWordDetectionLocked()
    }

    // Reacts to SatelliteWakeWorkHandler.onEngineFailure - the wake word engine's audio/
    // detection loop ending on its own rather than via a deliberate stop(). Previously nothing
    // restarted it in this case, leaving wake-word capture permanently dead until the app was
    // restarted (see issue #65). Bounded with a short backoff so a persistently broken engine
    // (e.g. a corrupt custom model) doesn't spin in a tight restart loop.
    private suspend fun handleWakeWordEngineFailure(error: Throwable) {
        if (wakeWordEngineRestartAttempts >= MAX_WAKE_WORD_ENGINE_RESTART_ATTEMPTS) {
            Timber.e("Wake word engine failed $wakeWordEngineRestartAttempts times in a row, giving up automatic restart: ${error.message}")
            firebase.logEvent(FirebaseManager.WAKE_WORD_ENGINE_RESTART_GIVEN_UP, mapOf("engine" to config.wakeWordEngine))
            return
        }
        wakeWordEngineRestartAttempts++
        Timber.w("Restarting wake word engine after unexpected failure (attempt $wakeWordEngineRestartAttempts/$MAX_WAKE_WORD_ENGINE_RESTART_ATTEMPTS): ${error.message}")
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_ENGINE_RESTART,
            mapOf("engine" to config.wakeWordEngine, "attempt" to wakeWordEngineRestartAttempts.toString())
        )
        delay(WAKE_WORD_ENGINE_RESTART_DELAY_MS.milliseconds)
        restartWakeWordDetection()
    }

    @Volatile private var arbitratingWakeWord = false

    // Audio kept from the moment a wake word was heard until this device knows whether it answers.
    @Volatile private var detectionBuffer: DetectionBuffer? = null

    private fun cancelDetectionBuffer() {
        detectionBuffer?.cancel()
        detectionBuffer = null
    }

    /** True while a pipeline is genuinely in progress: started, and neither ended nor stuck. */
    fun isResponding(): Boolean {
        val pipeline = audioPipeline ?: return false
        if (pipeline.pipelineStage == PipelineStage.ENDED) return false
        // One that has sat in a stage far too long is stuck, not responding, so a new wake word
        // may replace it.
        val stage = pipeline.pipelineStage.ordinal
        val ageSeconds = ((System.currentTimeMillis() - audioPipelineLastStateChange) / 1000).toInt()
        if (stage <= PipelineStage.AWAITING_RESPONSE.ordinal && ageSeconds > 15) return false
        if (stage <= PipelineStage.STREAMING_TTS.ordinal && ageSeconds > 30) return false
        return true
    }

    /**
     * A wake word heard while responding is an interruption: stop, and nothing more. The other
     * devices that heard it are told (they decide among themselves and this one always wins, so none
     * of them answers), but this device doesn't wait to be told.
     */
    private suspend fun interruptResponse(detection: WakeWordEngineProvider.WakeWordDetection) {
        Timber.i("Wake word heard while responding, interrupting")
        val level = MicLevelMonitor.calibrated(
            microphone.level.peakDbfs(MicLevelMonitor.WAKE_WORD_WINDOW_MS), config.wakeLevelOffsetDb
        )
        VacaUdpBroadcast.instance?.reportInterruption(detection.score, level)
        cancelDetectionBuffer()
        audioPipeline?.stop()
        audioPipeline = null
        if (mediaManager.alarmPlayer.isSounding()) {
            handleAlarmAction(false)
        }
    }

    suspend fun handleWakeWordDetection(detection: WakeWordEngineProvider.WakeWordDetection) {
        if (clientId.isEmpty()) {
            Timber.e("Unable to run audio pipeline. Satellite not connected to HA")
            val toastMessage = "No connection to Home Assistant.  Unable to respond to voice command."
            BroadcastSender.sendBroadcast(context, BroadcastSender.TOAST_MESSAGE, toastMessage)
            return
        }
        soundEffectFinishTime = 0L

        // A spoken wake word never gets here while responding (it interrupts instead, see
        // onWakeWordDetected); a manual wake is simply ignored.
        if (isResponding()) return

        if (arbitratingWakeWord) return
        arbitratingWakeWord = true

        var buffer: DetectionBuffer? = null
        try {
            // Measured before the sound plays, so the device's own chime can't count towards it.
            val level = MicLevelMonitor.calibrated(
                microphone.level.peakDbfs(MicLevelMonitor.WAKE_WORD_WINDOW_MS), config.wakeLevelOffsetDb
            )

            // Every device that heard the wake word reacts at once: the sound tells the user it was
            // heard, and the audio from now on is kept, so nothing said while the devices agree
            // who answers is lost. Only the winner uses the audio; the others drop it.
            buffer = DetectionBuffer(microphone.audio, scope).also { detectionBuffer = it }
            playWakeWordDetectionSound()

            val won = VacaUdpBroadcast.instance?.arbitrateWakeWord(
                detection.score,
                level,
                contest = detection.wakeWordId != "manual_wake",
            ) ?: true
            if (!won) {
                Timber.i("Another device is nearer to the speaker, not starting audio pipeline")
                return
            }

            if (audioPipeline != null) {
                audioPipeline?.stop()
                audioPipeline = null
            }

            // if wake up on ww, send event
            if (config.screenOnWakeWord) {
                config.screenOn = true
                config.screenSaver = false
            }
            audioLogManager.onWakeWordDetected(detection.timestamp, detection.wakeWord, detection.score)
            startAudioPipeline(PipelineStartMode.WAKE_WORD_DETECTED, detection.timestamp, buffer)
            buffer = null // the pipeline owns it now
        } finally {
            arbitratingWakeWord = false
            buffer?.let {
                it.cancel()
                if (detectionBuffer === it) detectionBuffer = null
            }
        }
    }

    suspend fun playWakeWordDetectionSound() {
        if (config.wakeWordSound != "none") {
            try {
                val soundUri = currentWakeWordSoundUri ?: resolveWakeSoundUri(config.wakeWordSound)

                if (soundUri != null) {
                    if (config.suppressWakeSound) {
                        // Undispatched, so the microphone is already turned down by the time the
                        // sound plays; it comes back up when the player reports the sound ended.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            microphone.suppressor.suppressWhilePlaying(
                                mediaManager.soundPlayer.state.map { it != Player.STATE_ENDED }
                            )
                        }
                    }
                    mediaManager.soundPlayer.play(soundUri)
                }

                Timber.i("Started wake word sound")
                scope.launch {
                    // The player may already be sitting at STATE_ENDED from a previous play of
                    // this same (cached) sound, so wait for it to actually leave ENDED first -
                    // confirming this playback has genuinely (re)started - before waiting for it
                    // to end again.
                    mediaManager.soundPlayer.state.first { it != Player.STATE_ENDED }
                    mediaManager.soundPlayer.state.first { it == Player.STATE_ENDED }
                    Timber.i("Ended wake word sound")
                }
            } catch (e: Exception) {
                Timber.e("Error playing wake word sound: ${e.message.toString()}")
            }
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
            wakeWordHandler?.engine?.setMuted(muted)
        }.onFailure { e ->
            Timber.e(e, "Error setting wake word engine muted=$muted")
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
    fun startAudioPipeline(
        startStage: PipelineStartMode,
        eventId: Long = System.currentTimeMillis(),
        // Audio kept since the wake word was heard; the pipeline streams it, then live audio.
        buffer: DetectionBuffer? = null,
    ) {
        if (audioPipeline != null) {
            audioPipeline?.stop()
            audioPipeline = null
        }
        audioPipeline = object: SatelliteAudioPipeline(context, scope, config, eventId, mediaManager, buffer?.frames ?: microphone.audio) {
            override fun sendMessage(packet: WyomingPacket) {
                sendSatelliteMessage(clientId, packet.type, packet.data, packet.payload)
            }

            override fun onStateChange(state: PipelineStage) {
                Timber.d("Audio pipeline state: $state")
                audioPipelineLastStateChange = System.currentTimeMillis()
                // Past listening the kept audio is of no use any more.
                if (state.ordinal > PipelineStage.VOICE_STARTED.ordinal) cancelDetectionBuffer()
                // Refresh the diagnostics mode (detect/stream). Without a wake word engine there
                // are no level updates to piggyback on, and the level drops to zero once the
                // pipeline stops listening.
                if (config.diagnosticsEnabled) {
                    val stoppedListening = state.ordinal > PipelineStage.VOICE_STARTED.ordinal
                    val noEngine = wakeWordHandler?.engine == null
                    sendDiagnostics(if (noEngine && stoppedListening) 0f else lastAudioLevel, lastDetectionLevel)
                }
            }

            override fun onAudioLevel(level: Float) {
                // With a wake word engine running its own level updates already cover this.
                if (wakeWordHandler?.engine == null) sendDiagnostics(level, lastDetectionLevel)
            }

            override fun onRequestTranscript(pipelineId: Long, text: String) {
                audioLogManager.onRequest(pipelineId, text)
            }

            override fun onResponseTranscript(pipelineId: Long, text: String) {
                audioLogManager.onResponse(pipelineId, text)
            }

            override fun onErrorMessage(pipelineId: Long, text: String) {
                audioLogManager.onErrorResponse(pipelineId, text)
            }

            override fun onFinish(reason: PipelineEndReason, continueConversation: Boolean) {
                Timber.i("Pipeline ended.  Restarting: $continueConversation")
                cancelDetectionBuffer()
                // A continuing conversation keeps other devices ducked until it really finishes.
                // This device ducked the others when it won arbitration, so it always unducks them.
                if (!(reason == PipelineEndReason.END_OF_PIPELINE && continueConversation)) {
                    config.eventBroadcaster.notifyEvent(Event(VacaUdpBroadcast.EVENT_PIPELINE_COMPLETED, "", ""))
                }
                if (reason == PipelineEndReason.END_OF_PIPELINE) {
                    if (continueConversation) {
                        scope.launch {
                            while (mediaManager.voicePlayer.isRunning()) {
                                delay(10.milliseconds)
                            }
                            startAudioPipeline(PipelineStartMode.CONTINUE_CONVERSATION)
                        }
                        return
                    } else {
                        audioPipeline = null
                    }
                } else {
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

    private suspend fun handleStopWord() {
        if (audioPipeline != null && audioPipeline?.pipelineStage == PipelineStage.STREAMING_TTS) {
            audioPipeline?.stop()
            audioPipeline = null
        }

        if (mediaManager.alarmPlayer.isSounding()) {
            handleAlarmAction(false)
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
            WyomingCustomEventType.ACTION -> handleAction(packet.getProp("action"), packet)
            WyomingCustomEventType.SETTINGS -> handleSettings(packet.getProp("settings"))
            WyomingCustomEventType.CAPABILITIES -> handleCapabilities(clientId)
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
                "interrupt" -> {
                    Timber.d("Interrupt received - treating as stop word")
                    handleStopWord()
                    BroadcastSender.sendBroadcast(context, BroadcastSender.STOP_WORD_DETECTED)
                }
                "refresh" -> config.eventBroadcaster.notifyEvent(Event("refresh", "", ""))
                "get-logs" -> scope.launch { handleGetLogs(payloadStr) }
                "wake" -> scope.launch {
                    handleWakeWordDetection(WakeWordEngineProvider.WakeWordDetection(
                        wakeWordId = "manual_wake",
                        wakeWord = "Manual Wake",
                        detected = true,
                        score = 1.0f,
                        timestamp = System.currentTimeMillis(),
                    ))
                }
                "alarm" -> if (payloadStr.isNotEmpty()) {
                    val payload = Json.parseToJsonElement(payloadStr).jsonObject
                    handleAlarmAction(payload["activate"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
                "navigate" -> if (payloadStr.isNotEmpty()) {
                    val url = Json.parseToJsonElement(payloadStr).jsonObject["url"]?.jsonPrimitive?.content ?: ""
                    if (url.isNotEmpty()) config.eventBroadcaster.notifyEvent(Event("navigate", "", url))
                }
            }
        }.onFailure { Timber.e("Failed to handle custom action $action: $it") }
    }

    // Action "get-logs", optional payload: {"lines": 500, "level": "V|D|I|W|E|F", "request_id": "..."}.
    // Replies with a custom event of type "logs": data {timestamp, request_id, lines, level, error?}
    // and the log text (UTF-8) as the binary payload.
    private suspend fun handleGetLogs(payloadStr: String) {
        val req = if (payloadStr.isNotEmpty()) Json.parseToJsonElement(payloadStr).jsonObject else null
        val requestId = req?.get("request_id")?.jsonPrimitive?.contentOrNull ?: ""
        val lines = req?.get("lines")?.jsonPrimitive?.intOrNull ?: LogcatReader.DEFAULT_LINES
        val level = req?.get("level")?.jsonPrimitive?.contentOrNull ?: "V"

        try {
            val result = LogcatReader.read(lines, level)
            sendCustomEvent(WyomingCustomEventType.LOGS, buildJsonObject {
                put("timestamp", isoNow())
                put("request_id", requestId)
                put("lines", result.lines)
                put("level", result.level)
            }, result.text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            sendCustomEvent(WyomingCustomEventType.LOGS, buildJsonObject {
                put("timestamp", isoNow())
                put("request_id", requestId)
                put("lines", 0)
                put("error", e.message ?: e.toString())
            })
        }
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
                    val volume = Json.parseToJsonElement(payloadStr).jsonObject["volume"]?.jsonPrimitive?.floatOrNull ?: 90f
                    if (config.sendspinPlaying) {
                        // Sendspin audio is what is playing, so it is the volume that is meant.
                        config.eventBroadcaster.notifyEvent(Event("sendspinSetVolume", "", volume))
                    } else {
                        mediaManager.musicPlayer.setVolume(volume)
                    }
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
        sendCustomEvent(WyomingCustomEventType.STATUS, data)
    }

    // Sends every setting changed on the device that HA has not been told about. Anything that
    // cannot be sent yet (satellite not running) stays queued for the next time this is called.
    private fun sendPendingSettings() {
        if (state != SatelliteState.RUNNING) {
            if (config.pendingHaSettings.isNotEmpty()) {
                Timber.w("Settings not sent to HA yet (satellite $state): ${config.pendingHaSettings.keys}")
            }
            return
        }
        for (key in config.pendingHaSettings.keys.toList()) {
            val value = config.pendingHaSettings[key] ?: continue
            try {
                sendSetting(key, value)
                Timber.i("Sent setting to HA: $key=$value")
                // Only drop it if a newer value has not been queued in the meantime.
                config.pendingHaSettings.remove(key, value)
            } catch (e: Exception) {
                Timber.e("Failed to send setting $key to HA: $e")
            }
        }
    }

    fun sendSetting(name: String, value: Any) {
        sendCustomEvent(WyomingCustomEventType.SETTINGS, buildJsonObject {
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

    fun sendCustomEvent(type: String, data: JsonObject, payload: ByteArray = ByteArray(0)) {
        sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
            put(EVENT_TYPE, type)
            put("data", data)
        }, payload)
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
        scope.launch {
            deviceManager.sensorsManager.requestSensorUpdates()
            startMotionDetection(delay=2)
        }


    }

    suspend fun startMotionDetection(delay: Int = 0) {
        // Start motion sensor
        if (deviceInfo.hardware.hasFrontCamera) {
            if (config.motionDetectionMode != "none") {
                if (deviceManager.camera.currentAvailability() == CameraAvailability.NO_PERMISSION) {
                    Timber.w("Motion detection enabled but camera permission not granted, requesting")
                    BroadcastSender.sendBroadcast(context, BroadcastSender.OPEN_PERMISSION_SCREEN, Manifest.permission.CAMERA)
                    return
                }
                delay(delay.seconds)  // Add delay to camera start to let app start up
                // Reuse the existing detector rather than leaking it - this can be called again
                // (e.g. on screenOn) while one is already running.
                if (motionTask == null) {
                    motionTask = MotionDetector(deviceManager.camera, config)
                }
                motionTask?.start()
            }
        }
    }

    suspend fun stopMotionDetection() {
        motionTask?.stop()
        motionTask?.release()
        motionTask = null
    }

    suspend fun stopSensors() {
        sensorJob?.cancel()
        stopMotionDetection()
    }

    fun sendDiagnostics(audioLevel: Float, detectionLevel: Float) {
        lastAudioLevel = audioLevel
        lastDetectionLevel = detectionLevel
        val wakeWordName = if (config.wakeWord == "") "None" else config.wakeWord.replace("_", " ").capitalizeWords()
        if (config.diagnosticsEnabled) {
            val data = DiagnosticInfo(
                show = config.diagnosticsEnabled,
                engine = config.wakeWordEngine,
                audioLevel = AudioDSP().linearToDbfs(audioLevel),
                detectionLevel = detectionLevel * 10,
                detectionThreshold = config.wakeWordThreshold * 10,
                wakeWord = wakeWordName,
                muted = config.isMuted,
                // Detecting only applies while a wake word engine is actually running - with no
                // wake word set the mic is only open to stream, so otherwise nothing is shown.
                mode = if (audioPipeline?.isListening == true) {
                    AudioRouteOption.STREAM
                } else if (wakeWordHandler?.state == WakeWordHandlerState.RUNNING && wakeWordHandler?.engine != null) {
                    AudioRouteOption.DETECT
                } else {
                    AudioRouteOption.NONE
                },
                motionDetectionMode = config.motionDetectionMode,
                activeMic = AudioInRouter.activeMicInput,
                recordingWakewordEnabled = config.recordingWakewordEnabled,
                audioLog = audioLogManager.getLog()
            )
            val event = Event("diagnosticStats", "", data)
            config.eventBroadcaster.notifyEvent(event)
        }
    }

    override fun onEventTriggered(event: Event) {
        when (event.eventName) {
            // A setting changed on the device (Configuration screen): tell HA, under its settings key.
            "sendSettingToHA" -> sendPendingSettings()
            "requestSettings" -> {
                scope.launch {
                    if (clientId != "") {
                        sendSatelliteMessage(clientId, WyomingEvent.CUSTOM_EVENT, buildJsonObject {
                            put(EVENT_TYPE, WyomingCustomEventType.SETTINGS)
                        })
                    }
                }
            }
            // The selected audio devices (-1 = automatic), whoever changed them - HA or the
            // on-device settings.
            "audioInputId", "audioOutputId" -> sendAudioSettingsIfChanged()
            // Granted after the satellite started (it is not requested outside onboarding), so
            // the wake word engine was skipped at startup - start it now.
            // Camera granted after startup - motion detection was skipped without it.
            "hasCameraPermission" -> {
                if (event.newValue == true) scope.launch { startMotionDetection() }
            }
            "recordAudioPermissionGranted" -> {
                if (wakeWordHandler?.state != WakeWordHandlerState.RUNNING) {
                    scope.launch { restartWakeWordDetection() }
                }
            }
            "playRecording" -> {
                (event.newValue as? Long)?.let { eventId ->
                    audioLogManager.playRecording(eventId)
                }
                sendDiagnostics(0f, 0f)
            }
            "toggleRecording" -> {
                config.recordingWakewordEnabled = !config.recordingWakewordEnabled
                sendDiagnostics(0f, 0f)
            }
        }
    }

    companion object {
        fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        private const val AUDIO_DEVICE_DEBOUNCE_MS = 1000L

        // Bounds on automatic recovery after the wake word engine fails on its own - see
        // handleWakeWordEngineFailure().
        private const val MAX_WAKE_WORD_ENGINE_RESTART_ATTEMPTS = 3
        private const val WAKE_WORD_ENGINE_RESTART_DELAY_MS = 2000L
    }
}
