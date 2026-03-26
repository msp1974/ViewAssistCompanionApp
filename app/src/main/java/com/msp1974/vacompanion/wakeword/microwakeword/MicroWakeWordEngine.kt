package com.msp1974.vacompanion.wakeword.microwakeword

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWord
import com.msp1974.vacompanion.wakeword.microwakeword.microwakeword.MicroWakeWordDetector
import com.msp1974.vacompanion.wakeword.microwakeword.models.WakeWordWithId
import com.google.protobuf.ByteString
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.audio.MicrophoneInput
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.wakeword.WakeWordEngineProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield
import timber.log.Timber
import kotlin.collections.plus

open class MicroWakeWordEngine (
    val context: Context,
    activeWakeWords: List<String>,
    activeStopWords: List<String>,
    val availableWakeWords: List<WakeWordWithId>,
    val availableStopWords: List<WakeWordWithId>,
    muted: Boolean = false
): WakeWordEngineProvider() {
    private val config = APPConfig.getInstance(context)
    private val _availableWakeWords = availableWakeWords.associateBy { it.id }
    private val _availableStopWords = availableStopWords.associateBy { it.id }

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
            val microphoneInput = MicrophoneInput()
            var wakeWords = activeWakeWords.value
            var stopWords = activeStopWords.value
            var detector = createDetector(wakeWords, stopWords)
            try {
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

                    if (config.diagnosticsEnabled) {
                        val audioByteString = ByteString.copyFrom(audio)
                        audio.rewind()
                        emit(AudioResult.AudioLevel(AudioDSP().audioLevel(audioByteString.toByteArray())))
                    }



                    if (isStreaming) {
                        emit(AudioResult.Audio(ByteString.copyFrom(audio)))
                        audio.rewind()
                    }

                    // Always run audio through the models, even if not currently streaming, to keep
                    // their internal state up to date
                    val detections = detector.detect(audio)
                    for (detection in detections) {
                        if (detection.score > 0.1f) {
                            if (detection.wakeWordId in wakeWords) {
                                emit(AudioResult.WakeDetected(detection))
                            } else if (detection.wakeWordId in stopWords) {
                                emit(AudioResult.StopDetected(detection))
                            }
                        }
                    }

                    // yield to ensure upstream emissions and
                    // cancellation have a chance to occur
                    yield()
                }
            } finally {
                Timber.i("Stopping MicroWakeWordEngine")
                microphoneInput.close()
                detector.close()
                emit(AudioResult.EngineStatus("Stopped"))
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