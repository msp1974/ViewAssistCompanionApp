package com.msp1974.vacompanion.satellite

import android.Manifest
import android.content.Context
import com.msp1974.vacompanion.broadcasts.BroadcastSender
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.FirebaseManager
import com.msp1974.vacompanion.utils.Permissions
import com.msp1974.vacompanion.wakeword.WakeWordEngine
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import kotlin.collections.set

enum class WakeWordHandlerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
}

interface IWakeWordHandler {
    fun onStateChange(state: WakeWordHandlerState)
    suspend fun onAudio(audio: WakeWordEngineProvider.AudioResult.Audio)
    suspend fun onWakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection)

    suspend fun onStopWordDetected(detection: WakeWordEngineProvider.WakeWordDetection)

    fun onDiagnostics(level: Float, lastDetectionLevel: Float)
}

abstract class SatelliteWakeWorkHandler(
    val context: Context,
    val config: APPConfig,
    val scope: CoroutineScope,
    private val engineOverride: WakeWordEngineModel? = null,
    private val activeWakeWordsOverride: List<String>? = null,
    private val activeStopWordsOverride: List<String> = listOf("stop")
): IWakeWordHandler {

    val firebase = FirebaseManager.getInstance(context)

    var state: WakeWordHandlerState = WakeWordHandlerState.STOPPED
        set(value) {
            field = value
            onStateChange(value)
        }

    var streamAudio: Boolean = false

    private var wakeWordJob: Job? = null
    var engine: WakeWordEngine? = null
    private var holdDetectionLevelJob: Job? = null
    private var lastWakeWordDetectionScore = 0f
    private val detectionCooldowns = mutableMapOf<String, Long>()
    private val detectionCooldownMs: Long = 2000L
    private var lastWakeDetectionTimestamp = 0L

    suspend fun run() {
        val startTime = System.currentTimeMillis()
        scope.launch (context = Dispatchers.Default) {
            start()
        }
        withTimeout(5000) {
            try {
                while (state != WakeWordHandlerState.RUNNING) {
                    delay(100)
                }
                Timber.d("Wake word detection started in ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Timber.e("Error waiting for wake word detection to start: ${e.message.toString()}")
            }
        }
    }

    suspend fun start() {
        try {
            val engineModel = engineOverride ?: when (config.wakeWordEngine) {
                "openwakeword" -> WakeWordEngineModel.OPENWAKEWORD
                "openwakeword-rt" -> WakeWordEngineModel.OPENWAKEWORD_RT
                "microwakeword" -> WakeWordEngineModel.MICROWAKEWORD
                else -> null
            }

            if (engineModel != null) {
                state = WakeWordHandlerState.STARTING
                engine = WakeWordEngine(context, config, engineModel)
                engine?.setActiveWakeWords(
                    activeWakeWordsOverride ?: listOf(config.wakeWord).filter { it != "none" }
                )
                engine?.setActiveStopWords(activeStopWordsOverride)
                runWakeWordDetection()
            }
            state = WakeWordHandlerState.RUNNING
            awaitCancellation()
        } finally {
            terminateWakeWordDetection()
        }
    }

    suspend fun stop() {
        if (wakeWordJob != null && wakeWordJob!!.isActive) {
            state = WakeWordHandlerState.STOPPING
            wakeWordJob?.cancel()
            wakeWordJob = null
        }

        try {
            withTimeout(200L) {
                withContext(Dispatchers.Default) {
                    while (state != WakeWordHandlerState.STOPPED) {
                        delay(10)
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            engine = null
            onDiagnostics(0f, 0f)
            Timber.d("Wake word detection stopped")
        }
    }


    fun runWakeWordDetection() {
        if (!Permissions(context, config).hasPermission(Manifest.permission.RECORD_AUDIO)) {
            return
        }

        //sendDiagnostics(0f, 0f)
        val flow = engine!!.start()
        wakeWordJob = scope.launch { flow.cancellable().collect {
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
                        Timber.d("Stop word detected: score: ${it.detection.score}")
                        if (it.detection.score > 0.5) {
                            onStopWordDetected(it.detection)
                            BroadcastSender.sendBroadcast(
                                context,
                                BroadcastSender.STOP_WORD_DETECTED
                            )
                        }
                    }
                }

                is WakeWordEngineProvider.AudioResult.Audio -> {
                    if (it.audio.size() > 0) {
                        if (engine!!.isStreaming()) {
                            onAudio(it)
                        }
                    }
                }

                is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                    if (config.diagnosticsEnabled) {
                        onDiagnostics(it.level, lastWakeWordDetectionScore)
                    }
                }
                is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                    Timber.i("Engine status: ${it.status}")
                    if (it.status == "Started") {
                        state = WakeWordHandlerState.RUNNING
                    } else if (it.status == "Stopped") {
                        state = WakeWordHandlerState.STOPPED
                    }
                }

            }
        }}
    }

    fun terminateWakeWordDetection() {

    }

    private suspend fun wakeWordDetected(detection: WakeWordEngineProvider.WakeWordDetection, isStreaming: Boolean) {
        Timber.i("${detection.wakeWord} wake word detected at ${detection.score}, threshold is ${config.wakeWordThreshold}")
        lastWakeDetectionTimestamp = detection.timestamp
        firebase.logEvent(
            FirebaseManager.WAKE_WORD_DETECTED, mapOf(
                "wake_word" to config.wakeWord,
                "threshold" to config.wakeWordThreshold.toString(),
                "prediction" to detection.score.toString()
            )
        )

        holdLastDetectionLevel(detection.score)
        onWakeWordDetected(detection)
    }

    private fun holdLastDetectionLevel(detectionLevel: Float, duration: Long = 2000) {
        if (detectionLevel > lastWakeWordDetectionScore) {
            lastWakeWordDetectionScore = detectionLevel
            if (holdDetectionLevelJob != null && holdDetectionLevelJob!!.isActive) {
                holdDetectionLevelJob?.cancel()
            }
            holdDetectionLevelJob = scope.launch {
                delay(duration)
                if (!streamAudio) {
                    lastWakeWordDetectionScore = 0f
                }
            }
        }
    }

}
