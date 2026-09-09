package com.msp1974.vacompanion.wakeword.microwakeword

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWordDetector
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.audio.VACAAudioFormat
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.yield
import timber.log.Timber
import kotlin.collections.plus

open class MicroWakeWordEngine (
    val context: Context,
    val deviceManager: DeviceManager,
    activeWakeWords: List<String>,
    activeStopWords: List<String>,
    val availableWakeWords: List<WakeWordWithId>,
    val availableStopWords: List<WakeWordWithId>,
    muted: Boolean = false
): WakeWordEngineProvider() {
    private val config: APPConfig = deviceManager.config
    private val _availableWakeWords = availableWakeWords.associateBy { it.id }
    private val _availableStopWords = availableStopWords.associateBy { it.id }

    private val lastScores = mutableMapOf<String, Float>()

    private val detector: MicroWakeWordDetector? = null

    private val _activeWakeWords = MutableStateFlow(activeWakeWords)
    val activeWakeWords = _activeWakeWords.asStateFlow()
    override fun setActiveWakeWords(value: List<String>) {
        _activeWakeWords.value = value
    }

    private val _activeStopWords = MutableStateFlow(activeStopWords)
    val activeStopWords = _activeStopWords.asStateFlow()
    override fun setActiveStopWords(value: List<String>) {
        _activeStopWords.value = value
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
        // Stop microphone when muted
        if (it) emptyFlow()
        else flow {
            val microphoneInput = MicrophoneInput(context, deviceManager)
            var wakeWords = activeWakeWords.value
            var stopWords = activeStopWords.value

            try {
                var detector = createDetector(wakeWords, stopWords)
                microphoneInput.start()
                emit(AudioResult.EngineStatus("Started"))
                while (true) {
                    if (wakeWords != activeWakeWords.value || stopWords != activeStopWords.value) {
                        wakeWords = activeWakeWords.value
                        stopWords = activeStopWords.value
                        detector.close()
                        detector = createDetector(wakeWords, stopWords)
                    }

                    val audio = microphoneInput.readBytes()
                    val frameTimestamp = System.currentTimeMillis()
                        val detections = detector.detect(audio)
                        val frameScores = mutableMapOf<String, Float>()
                        detections.forEach { detection ->
                            val currentWakeWordScore = frameScores[detection.wakeWord]
                            if (currentWakeWordScore == null || detection.score > currentWakeWordScore) {
                                frameScores[detection.wakeWord] = detection.score
                            }

                            val currentWakeWordIdScore = frameScores[detection.wakeWordId]
                            if (currentWakeWordIdScore == null || detection.score > currentWakeWordIdScore) {
                                frameScores[detection.wakeWordId] = detection.score
                            }
                        }
                        audio.rewind()

                    if (config.diagnosticsEnabled) {
                        val audioBytes = ByteArray(audio.remaining())
                        audio.get(audioBytes)
                        audio.rewind()
                        emit(AudioResult.AudioLevel(AudioDSP().audioLevel(audioBytes)))
                    }

                    // Emit audio result even if not streaming so that the controller can maintain a rolling history buffer
                    if (isStreaming || config.recordingWakewordEnabled) {
                        val audioBytes = ByteArray(audio.remaining())
                        audio.get(audioBytes)
                        emit(
                            AudioResult.Audio(
                                audioBytes,
                                timestamp = frameTimestamp,
                                scores = frameScores
                            )
                        )
                        audio.rewind()
                    }

                    // Always run audio through the models, even if not currently streaming, to keep
                    // their internal state up to date.
                    // Detection for this frame has already been computed above.
                    for (detection in detections) {
                        val lastScore = lastScores[detection.wakeWordId] ?: 0f
                        if (detection.detected && (detection.score > 0.1f || lastScore > 0.1f)) {
                            if (detection.wakeWordId in wakeWords) {
                                emit(AudioResult.WakeDetected(detection.copy(timestamp = frameTimestamp)))
                            } else if (detection.wakeWordId in stopWords) {
                                emit(AudioResult.StopDetected(detection.copy(timestamp = frameTimestamp)))
                            }
                        }
                        lastScores[detection.wakeWordId] = detection.score
                    }

                    // yield to ensure upstream emissions and
                    // cancellation have a chance to occur
                    yield()
                }
            } finally {
                Timber.i("Stopping MicroWakeWordEngine")
                microphoneInput.close()
                detector?.close()
                Timber.i("MicroWakeWordEngine stopped")
            }
        }.onCompletion { cause ->
            if (cause != null && cause !is kotlinx.coroutines.CancellationException) {
                Timber.w(cause, "MicroWakeWordEngine completed with failure")
            }
        }
    }

    private suspend fun createDetector(
        wakeWords: List<String>,
        stopWords: List<String>
    ) = MicroWakeWordDetector(
            loadWakeWords(wakeWords, _availableWakeWords) +
            loadWakeWords(stopWords, _availableStopWords)
        )


    private suspend fun loadWakeWords(
        ids: List<String>,
        wakeWords: Map<String, WakeWordWithId>
    ): List<MicroWakeWord> = buildList {
        for (id in ids) {
            wakeWords[id]?.let { wakeWord ->
                runCatching {
                    add(MicroWakeWord.fromWakeWord(wakeWord))
                }.onFailure {
                    Timber.e(it, "Error loading wake word: $id")
                }
            }
        }
    }
}
