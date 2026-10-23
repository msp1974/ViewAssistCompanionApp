package com.msp1974.vacompanion.wakeword.microwakeword

import android.content.Context
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWordDetector
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.collections.plus

open class MicroWakeWordEngine (
    val context: Context,
    val deviceManager: DeviceManager,
    activeWakeWords: List<String>,
    activeStopWords: List<String>,
    val availableWakeWords: List<WakeWordWithId>,
    val availableStopWords: List<WakeWordWithId>,
    private val audio: Flow<ShortArray>,
    muted: Boolean = false
): WakeWordEngineProvider() {
    private val config: APPConfig = deviceManager.config
    private val _availableWakeWords = availableWakeWords.associateBy { it.id }
    private val _availableStopWords = availableStopWords.associateBy { it.id }

    private val audioDSP = AudioDSP()

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
    override fun start() = muted.flatMapLatest {
        // Unsubscribe from the microphone when muted
        if (it) emptyFlow()
        else flow {
            var wakeWords = activeWakeWords.value
            var stopWords = activeStopWords.value
            var detector: MicroWakeWordDetector? = null

            try {
                detector = createDetector(wakeWords, stopWords)
                emit(AudioResult.EngineStatus("Started"))
                audio.collect { frame ->
                    if (wakeWords != activeWakeWords.value || stopWords != activeStopWords.value) {
                        wakeWords = activeWakeWords.value
                        stopWords = activeStopWords.value
                        detector?.close()
                        detector = createDetector(wakeWords, stopWords)
                    }

                    val frameTimestamp = System.currentTimeMillis()

                    if (config.diagnosticsEnabled) {
                        emit(AudioResult.AudioLevel(audioDSP.audioLevel(audioDSP.normaliseAudioBuffer(frame))))
                    }

                    // Only for the wake word recording log, which needs a rolling history
                    if (config.recordingWakewordEnabled) {
                        emit(AudioResult.Audio(frame, timestamp = frameTimestamp))
                    }

                    // Always run audio through the models to keep their internal state up to date
                    val detections = detector!!.detect(toByteBuffer(frame))
                    for (detection in detections) {
                        if (detection.score > 0.1f) {
                            if (detection.wakeWordId in wakeWords) {
                                emit(AudioResult.WakeDetected(detection.copy(timestamp = frameTimestamp)))
                            } else if (detection.wakeWordId in stopWords) {
                                emit(AudioResult.StopDetected(detection.copy(timestamp = frameTimestamp)))
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // Rethrow after logging rather than letting it propagate unannounced -
                // SatelliteWakeWorkHandler now catches this to log it and trigger recovery
                // (see issue #65).
                Timber.e("Runtime exception thrown by wake word engine: $e")
                throw e
            } finally {
                Timber.i("Stopping MicroWakeWordEngine")
                detector?.close()
            }
        }
    }

    // The detector's native frontend reads from a direct little-endian buffer.
    private fun toByteBuffer(frame: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frame.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(frame)
        buffer.rewind()
        return buffer
    }

    private suspend fun createDetector(
        wakeWords: List<String>,
        stopWords: List<String>
    ) = MicroWakeWordDetector(
            loadWakeWords(wakeWords, _availableWakeWords, config.wakeWordThreshold) +
            loadWakeWords(stopWords, _availableStopWords)
        )


    private suspend fun loadWakeWords(
        ids: List<String>,
        wakeWords: Map<String, WakeWordWithId>,
        threshold: Float = -1f
    ): List<MicroWakeWord> = buildList {
        for (id in ids) {
            wakeWords[id]?.let { wakeWord ->
                runCatching {
                    add(MicroWakeWord.fromWakeWord(wakeWord, threshold))
                }.onFailure {
                    Timber.e(it, "Error loading wake word: $id")
                }
            }
        }
    }
}