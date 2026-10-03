package com.msp1974.vacompanion.wakeword

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers.Companion.round
import com.msp1974.vacompanion.wakeword.microwakeword.MicroWakeWordEngine
import com.msp1974.vacompanion.wakeword.microwakeword.providers.MicroWakeWordAssetProvider
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.OpenWakeWordEngine
import kotlinx.coroutines.flow.flow
import timber.log.Timber

open class WakeWordEngine(val context: Context, val deviceManager: DeviceManager, val engine: WakeWordEngineModel) {

    private var activeWakeWords: List<String> = listOf()
    private var activeStopWords: List<String> = listOf()
    private var engineInstance: WakeWordEngineProvider? = null

    private val config: APPConfig = deviceManager.config

    // Latest mute state requested via setMuted(), tracked independently of engineInstance.
    // engineInstance stays null for the ~1s the engine takes to start (ONNX/TFLite model
    // loading) - without this, a setMuted() call landing in that window was a silent no-op,
    // and since config.isMuted only re-broadcasts on an actual value *change*, an identical
    // mute arriving later would never be redelivered, losing it until the app restarted
    // (see issue #57). Seeded from config.isMuted so a fresh engine still starts correct.
    @Volatile
    private var requestedMuted: Boolean = config.isMuted

    private suspend fun get(): WakeWordEngineProvider? {
        Timber.i("Starting $engine wake word engine")


        if (config.availableWakeWords == null) {
            Timber.e("No available wake words")
            return null
        }

        val availableWakeWords = config.availableWakeWords?.get(engine.toString()) ?: emptyList()

        when(engine) {
            WakeWordEngineModel.MICROWAKEWORD -> {
                // TODO: Replace this with values from config availableWakeWords
                // ATM this does not load stop words
                val availableStopWords = MicroWakeWordAssetProvider(
                    context.assets,
                    "microwakeword/stopWords"
                ).get()
                return MicroWakeWordEngine(
                    context=context,
                    deviceManager=deviceManager,
                    activeWakeWords=activeWakeWords,
                    activeStopWords=activeStopWords,
                    availableWakeWords=availableWakeWords,
                    availableStopWords=availableStopWords,
                    muted = requestedMuted)
            }
            WakeWordEngineModel.OPENWAKEWORD -> {
                return OpenWakeWordEngine(
                    context = context,
                    deviceManager = deviceManager,
                    engine = WakeWordEngineModel.OPENWAKEWORD,
                    activeWakeWords = activeWakeWords,
                    availableWakeWords = availableWakeWords,
                    detectionCooldownMs = 1500L,
                    muted = requestedMuted
                )
            }
            WakeWordEngineModel.OPENWAKEWORD_RT -> {
                return OpenWakeWordEngine(
                    context = context,
                    deviceManager = deviceManager,
                    engine = WakeWordEngineModel.OPENWAKEWORD_RT,
                    activeWakeWords = activeWakeWords,
                    availableWakeWords = availableWakeWords,
                    detectionCooldownMs = 1500L,
                    muted = requestedMuted
                )
            }
        }
    }

    fun getAvailableWakeWords(): List<WakeWordWithId> {
        return config.availableWakeWords?.get(engine.toString()) ?: emptyList()
    }


    fun setActiveWakeWords(value: List<String>) {
        activeWakeWords = value
    }

    fun setActiveStopWords(value: List<String>) {
        activeStopWords = value
    }

    fun setStreaming(stream: Boolean) {
        if (engineInstance != null) {
            engineInstance!!.isStreaming = stream
        }
    }

    fun isStreaming(): Boolean {
        if (engineInstance != null) {
            return engineInstance!!.isStreaming
        }
        return false
    }

    fun setMuted(value: Boolean) {
        requestedMuted = value
        if (engineInstance != null) {
            engineInstance!!.setMuted(value)
        } else {
            Timber.w("Wake word engine not started yet - deferring mute=$value until it starts")
        }
    }

    fun isMuted(): Boolean {
        return engineInstance?.isMuted() ?: requestedMuted
    }

    @Synchronized
    fun release() {
        val instance = engineInstance
        engineInstance = null
        instance?.release()
    }

    fun start() = flow {
        engineInstance = get()
        if (engineInstance != null) {
            // Re-apply here rather than trusting the muted= constructor argument above: a
            // setMuted() call can land after get() captured requestedMuted into that argument
            // but before engineInstance was assigned on the line above, and would otherwise be
            // lost the same way (see issue #57).
            if (engineInstance!!.isMuted() != requestedMuted) {
                Timber.w("Applying mute=$requestedMuted deferred while wake word engine was starting")
                engineInstance!!.setMuted(requestedMuted)
            }
            try {
                engineInstance!!.start()!!.collect {
                    when (it) {
                        is WakeWordEngineProvider.AudioResult.WakeDetected -> {
                            val detectInfo = WakeWordEngineProvider.WakeWordDetection(
                                it.detection.wakeWordId,
                                it.detection.wakeWord,
                                it.detection.score >= config.wakeWordThreshold,
                                it.detection.score.round(2)
                            )
                            emit(WakeWordEngineProvider.AudioResult.WakeDetected(detectInfo))
                        }

                        is WakeWordEngineProvider.AudioResult.StopDetected -> {
                            val detectInfo = WakeWordEngineProvider.WakeWordDetection(
                                it.detection.wakeWordId,
                                it.detection.wakeWord,
                                it.detection.score >= config.wakeWordThreshold,
                                it.detection.score.round(2)
                            )
                            emit(WakeWordEngineProvider.AudioResult.StopDetected(detectInfo))
                        }

                        is WakeWordEngineProvider.AudioResult.Audio -> {
                            emit(it)
                        }

                        is WakeWordEngineProvider.AudioResult.AudioLevel -> {
                            emit(it)
                        }

                        is WakeWordEngineProvider.AudioResult.EngineStatus -> {
                            emit(it)
                        }
                    }
                }
            } finally {
                try {
                    emit(WakeWordEngineProvider.AudioResult.EngineStatus("Stopped"))
                } catch (_: Exception) {
                    // Cancellation can prevent the final status emission.
                } finally {
                    release()
                }
            }
        }
    }
}
