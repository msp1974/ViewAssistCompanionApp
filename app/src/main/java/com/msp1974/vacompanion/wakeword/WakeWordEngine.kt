package com.msp1974.vacompanion.wakeword

import android.content.Context
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.Helpers.Companion.round
import com.msp1974.vacompanion.wakeword.microwakeword.MicroWakeWordEngine
import com.msp1974.vacompanion.wakeword.microwakeword.providers.MicroWakeWordAssetProvider
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.OpenWakeWordEngine
import kotlinx.coroutines.flow.flow
import timber.log.Timber

open class WakeWordEngine(val context: Context, val config: APPConfig, val engine: WakeWordEngineModel, val isAndroidThings: Boolean) {

    private var activeWakeWords: List<String> = listOf()
    private var activeStopWords: List<String> = listOf()
    private var engineInstance: WakeWordEngineProvider? = null

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
                return MicroWakeWordEngine(context, config, activeWakeWords, activeStopWords, availableWakeWords, availableStopWords, isAndroidThings = isAndroidThings, muted = config.isMuted)
            }
            WakeWordEngineModel.OPENWAKEWORD -> {
                return OpenWakeWordEngine(
                    context = context,
                    config = config,
                    engine = WakeWordEngineModel.OPENWAKEWORD,
                    activeWakeWords = activeWakeWords,
                    availableWakeWords = availableWakeWords,
                    detectionCooldownMs = 1500L,
                    isAndroidThings = isAndroidThings,
                    muted = config.isMuted,

                )
            }
            WakeWordEngineModel.OPENWAKEWORD_RT -> {
                return OpenWakeWordEngine(
                    context = context,
                    config = config,
                    engine = WakeWordEngineModel.OPENWAKEWORD_RT,
                    activeWakeWords = activeWakeWords,
                    availableWakeWords = availableWakeWords,
                    detectionCooldownMs = 1500L,
                    isAndroidThings = isAndroidThings,
                    muted = config.isMuted
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
        if (engineInstance != null) {
            engineInstance!!.setMuted(value)
        }
    }

    fun isMuted(): Boolean {
        if (engineInstance != null) {
            return engineInstance!!.isMuted()
        }
        return false
    }

    fun start() = flow {
        engineInstance = get()
        if (engineInstance != null) {
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
                emit(WakeWordEngineProvider.AudioResult.EngineStatus("Stopped"))
            }
        }
    }
}
