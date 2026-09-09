package com.msp1974.vacompanion.wakeword.openwakeword

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.audio.VACAAudioFormat
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineModel
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.audio.AudioProcessor
import com.msp1974.vacompanion.wakeword.openwakeword.ml.ModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.OnnxModelRunner
import com.msp1974.vacompanion.wakeword.openwakeword.ml.TfliteModelRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import timber.log.Timber
import kotlinx.coroutines.yield


/**
 * Main entry point for wake word detection using ONNX Runtime.
 *
 * This class manages multiple wake word models and emits detection events through a Kotlin Flow.
 * It provides real-time audio processing with configurable detection modes and cooldown periods.
 */
class OpenWakeWordEngine(
    val context: Context,
    val deviceManager: DeviceManager,
    val engine: WakeWordEngineModel,
    val activeWakeWords: List<String>,
    //val activeStopWords: List<String>,
    val availableWakeWords: List<WakeWordWithId>,
    //val availableStopWords: List<WakeWordWithId>,
    muted: Boolean = false,
    private val detectionCooldownMs: Long = 2000L,
): WakeWordEngineProvider() {

    private val assetManager: AssetManager = context.assets
    private val modelProcessors = mutableMapOf<WakeWordWithId, ModelProcessor>()
    private val scoreWindows = mutableMapOf<String, ArrayDeque<Float>>()
    private val detectionStates = mutableMapOf<String, OwwDetectionState>()

    private val config: APPConfig = deviceManager.config
    var isEnabled = true

    private var _audioProcessor: AudioProcessor = AudioProcessor(assetManager)
    @Volatile private var latestFrameScores: Map<String, Float> = emptyMap()

    private data class OwwDetectionState(
        var consecutiveHits: Int = 0,
        var lastTriggeredAtMs: Long = 0L
    )

    private val lastScores = mutableMapOf<String, Float>()
    private val audioDSP = AudioDSP()

    /**
     * Flow of wake word detection events.
     *
     * This Flow emits [WakeWordDetection] objects whenever a wake word is detected.
     * The Flow is hot and shared, meaning multiple collectors will receive the same events.
     *
     * ## Example: Basic Collection
     * ```kotlin
     * engine.detections.collect { detection ->
     *     showToast("${detection.model.name} detected!")
     * }
     * ```
     *
     * ## Example: Filtering High-Confidence Detections
     * ```kotlin
     * engine.detections
     *     .filter { it.score > 0.8f }
     *     .collect { detection ->
     *         // Only process high-confidence detections
     *     }
     * ```
     *
     * ## Example: Debouncing Rapid Detections
     * ```kotlin
     * engine.detections
     *     .debounce(500) // Additional debounce on top of cooldown
     *     .collect { detection ->
     *         // Process debounced detections
     *     }
     * ```
     */

    /**
     * Flow of real-time wake word scores.
     *
     * This Flow emits [WakeWordScore] objects continuously for all models,
     * regardless of whether they exceed the detection threshold.
     * Useful for real-time monitoring and visualization.
     */

    init {
        require(activeWakeWords.isNotEmpty()) { "At least one wake word model must be provided" }
        initializeModels()
    }

    private fun getWakeWord(wakeWord: String): WakeWordWithId? {
        for (w in availableWakeWords) {
            if (w.id == wakeWord) return w
        }
        return null
    }

    private fun initializeModels() {
        activeWakeWords.forEach { wakeWordWithId ->
            getWakeWord(wakeWordWithId)?.let { wakeWord ->
                val processor = ModelProcessor(engine, wakeWord)
                modelProcessors[wakeWord] = processor
            }
        }
    }

    fun addModel(wakeWordId: String) {
        /**
        Add model to detections
         */
        Timber.w("Adding model $wakeWordId to engine")
        getWakeWord(wakeWordId)?.let { wakeWord ->
            val processor = ModelProcessor(engine, wakeWord)
            modelProcessors[wakeWord] = processor
            scoreWindows.remove(wakeWord.id)
            detectionStates.remove(wakeWord.id)
        }
    }

    fun removeModel(modelName: String) {
        /**
        Remove model from detections
         */
        Timber.w("Removing model $modelName from engine")
        modelProcessors.forEach {(wakeWordWithId, processor) ->
            if (wakeWordWithId.id == modelName) {
                processor.close()
                modelProcessors.remove(wakeWordWithId)
                scoreWindows.remove(modelName)
                detectionStates.remove(modelName)
                return
            }
        }
        throw IllegalArgumentException("Model with name $modelName not found")
    }

    private val _muted = MutableStateFlow(muted)
    val muted = _muted.asStateFlow()
    override fun setMuted(value: Boolean) {
        _muted.value = value
    }

    override fun isMuted(): Boolean {
        return _muted.value
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() = muted.flatMapLatest {
        if (it) emptyFlow()
        else flow {
            val wakeWords = activeWakeWords
            val microphoneInput = MicrophoneInput(context, deviceManager)
            try {
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    val audio = microphoneInput.readShort()
                    val frameTimestamp = System.currentTimeMillis()

                    if (audio.isNotEmpty()) {
                        if (config.diagnosticsEnabled) {
                            val normalisedAudio = audioDSP.normaliseAudioBuffer(audio)
                            emit(AudioResult.AudioLevel(AudioDSP().audioLevel(normalisedAudio)))
                        }

                        // Always emit audio frames so shared speaker verification/enrollment has
                        // a live buffer source, even when not streaming to HA.
                        val audioBytes = AudioDSP().shortArrayToByteBuffer(audio)
                        emit(
                            AudioResult.Audio(
                                audioBytes,
                                timestamp = frameTimestamp,
                                scores = latestFrameScores
                            )
                        )

                        val detections = processAudio(audioDSP.shortArrayTo16BitPCMFloat(audio), frameTimestamp)
                        for (detection in detections) {
                            val lastScore = lastScores[detection.wakeWordId] ?: 0f
                            if (detection.score > 0.1f || lastScore > 0.1f) {
                                if (detection.wakeWordId in wakeWords) {
                                    Timber.i(
                                    "OWW trigger detected wake='%s' score=%.4f ts=%d",
                                    detection.wakeWord,
                                    detection.score,
                                    detection.timestamp
                                )
                                emit(AudioResult.WakeDetected(detection.copy(timestamp = frameTimestamp)))
                                }
                            }
                            lastScores[detection.wakeWordId] = detection.score
                        }
                    }
                    yield()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Timber.e("Runtime exception thrown by wake word engine: $e")
            } finally {
                microphoneInput.close()
                Timber.i("OpenWakeWordEngine stopped")
            }
        }.onCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                Timber.w(cause, "OpenWakeWordEngine completed with failure")
            }
        }
    }

    @SuppressLint("DefaultLocale")
    fun processAudio(audioBuffer: FloatArray, timestamp: Long = System.currentTimeMillis()): List<WakeWordDetection> {
        val detections = mutableListOf<WakeWordDetection>()
        val frameScores = mutableMapOf<String, Float>()

        if (isEnabled) {
            val audioFeatures = _audioProcessor.getAudioFeatures(audioBuffer)
            modelProcessors.map { (wakeWordWithId, processor) ->
                try {
                    val score = processor.process(audioFeatures)
                    frameScores[wakeWordWithId.id] = score
                    val smoothedScore = updateSmoothedScore(
                        modelName = wakeWordWithId.id,
                        score = score,
                        windowSize = config.experimentalMwwSmoothingWindow
                    )
                    val shouldTrigger = evaluateTrigger(
                        modelName = wakeWordWithId.id,
                        smoothedScore = smoothedScore,
                        threshold = config.wakeWordThreshold,
                        requiredHits = config.experimentalMwwConsecutiveHits,
                        cooldownMs = resolveOwwCooldownMs(),
                        nowMs = timestamp
                    )
                    if (shouldTrigger) {
                        detections.add(
                            WakeWordDetection(
                                wakeWordWithId.id,
                                wakeWordWithId.wakeWord.wake_word,
                                detected = true,
                                score = smoothedScore,
                                timestamp = timestamp
                            )
                        )
                    }
                } catch (e: RuntimeException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("Error processing model ${wakeWordWithId.id} ->$e")
                    e.printStackTrace()
                }
            }
        }
        latestFrameScores = HashMap(frameScores)
        return detections
    }

    private fun updateSmoothedScore(modelName: String, score: Float, windowSize: Int): Float {
        val size = windowSize.coerceAtLeast(1)
        val window = scoreWindows.getOrPut(modelName) { ArrayDeque(size) }
        if (window.size == size) {
            window.removeFirst()
        }
        window.addLast(score)
        return window.average().toFloat()
    }

    private fun evaluateTrigger(
        modelName: String,
        smoothedScore: Float,
        threshold: Float,
        requiredHits: Int,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        return shouldTrigger(
            modelName = modelName,
            smoothedScore = smoothedScore,
            threshold = threshold,
            requiredHits = requiredHits.coerceAtLeast(1),
            cooldownMs = cooldownMs.coerceAtLeast(0L),
            nowMs = nowMs
        )
    }

    private fun resolveOwwCooldownMs(): Long {
        return maxOf(detectionCooldownMs, config.experimentalMwwCooldownMs.toLong())
    }

    private fun shouldTrigger(
        modelName: String,
        smoothedScore: Float,
        threshold: Float,
        requiredHits: Int,
        cooldownMs: Long,
        nowMs: Long
    ): Boolean {
        val state = detectionStates.getOrPut(modelName) { OwwDetectionState() }
        state.consecutiveHits = if (smoothedScore >= threshold) state.consecutiveHits + 1 else 0
        val onCooldown = nowMs - state.lastTriggeredAtMs < cooldownMs
        if (state.consecutiveHits >= requiredHits && !onCooldown) {
            state.lastTriggeredAtMs = nowMs
            state.consecutiveHits = 0
            return true
        }
        return false
    }

    fun enable() {
        isEnabled = true
    }

    fun disable() {
        isEnabled = false
    }

    fun reset() {
        _audioProcessor.reset()
        scoreWindows.clear()
        detectionStates.clear()
    }

    /**
     * Stops wake word detection.
     *
     * This method stops audio recording and cancels all ongoing detection processing.
     * The engine can be restarted by calling [start] again.
     *
     * ## Example
     * ```kotlin
     * override fun onPause() {
     *     super.onPause()
     *     engine.stop() // Stop detection when app goes to background
     * }
     * ```
     *
     * @see start
     */
    fun stop() {

    }

    /**
     * Releases all resources used by the engine.
     *
     * This method should be called when the engine is no longer needed to free up memory
     * and system resources. After calling this method, the engine cannot be reused.
     *
     * ## Important
     * Always call this method in your Activity/Fragment's onDestroy() to prevent memory leaks.
     *
     * ## Example
     * ```kotlin
     * override fun onDestroy() {
     *     super.onDestroy()
     *     wakeWordEngine?.release()
     * }
     * ```
     *
     * This method will:
     * - Stop any ongoing detection
     * - Release ONNX Runtime sessions
     * - Free audio processing resources
     * - Clear internal caches
     */
    override fun release() {
        stop()
        modelProcessors.values.forEach { it.close() }
        modelProcessors.clear()
    }

    /**
     * Internal class to process audio for a specific model.
     */
    private class ModelProcessor(
        engine: WakeWordEngineModel,
        wakeWord: WakeWordWithId
    ) : AutoCloseable {

        private val modelRunner = getModelRunner(engine, wakeWord)

        fun getModelRunner(engine: WakeWordEngineModel, wakeWord: WakeWordWithId): ModelRunner {
            return if (engine == WakeWordEngineModel.OPENWAKEWORD) OnnxModelRunner(wakeWord)
            else TfliteModelRunner(wakeWord)
        }

        fun process(audioFeatures: Array<Array<FloatArray>>): Float {
            val score = modelRunner.predictWakeWord(audioFeatures)
            return score
        }

        override fun close() {
            modelRunner.close()
        }

    }

}
