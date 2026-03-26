package com.msp1974.vacompanion.wakeword

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.utils.WakeWords
import com.msp1974.vacompanion.wakeword.microwakeword.MicroWakeWordEngine
import com.msp1974.vacompanion.wakeword.microwakeword.providers.AssetWakeWordProvider
import com.msp1974.vacompanion.wakeword.openwakeword.OpenWakeWordEngine
import com.msp1974.vacompanion.wakeword.openwakeword.model.DetectionMode
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordDetection
import com.msp1974.vacompanion.wakeword.openwakeword.model.WakeWordModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import timber.log.Timber

data class WakeWord(val name: String, val fileName: String, val builtIn: Boolean = true)
enum class WakeWordEngineModel {MICROWAKEWORD, OPENWAKEWORD}

open class WakeWordEngine(val context: Context, val engine: WakeWordEngineModel) {
    private var activeWakeWords: List<String> = listOf()
    private var activeStopWords: List<String> = listOf()
    private var config = APPConfig.getInstance(context)
    private var engineInstance: WakeWordEngineProvider? = null


    private suspend fun get(): WakeWordEngineProvider? {
        if (engine == WakeWordEngineModel.MICROWAKEWORD) {
            val availableWakeWords = AssetWakeWordProvider(context.assets, "wakeWords").get()
            val availableStopWords = AssetWakeWordProvider(context.assets, "stopWords").get()
            return MicroWakeWordEngine(context, activeWakeWords, activeStopWords, availableWakeWords, availableStopWords, muted = config.isMuted)
        } else if (engine == WakeWordEngineModel.OPENWAKEWORD){
            val wakeWords = WakeWords(context).getWakeWords()
            if (config.wakeWord in wakeWords.keys) {
                val wakeWordInfo = wakeWords[config.wakeWord]!!
                val models = listOf(
                    WakeWordModel(
                        name = wakeWordInfo.name,
                        modelPath = wakeWordInfo.fileName,
                        builtIn = wakeWordInfo.builtIn,
                        threshold = config.wakeWordThreshold
                    )
                )
                return OpenWakeWordEngine(
                    context = context,
                    models = models,
                    detectionCooldownMs = 1500L,
                    muted = config.isMuted
                )
            }
        }
        return null
    }

    fun getAvailableWakeWords(): List<WakeWordDetection> {
        return listOf()
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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
                                it.detection.score
                            )
                            emit(WakeWordEngineProvider.AudioResult.WakeDetected(detectInfo))
                        }

                        is WakeWordEngineProvider.AudioResult.StopDetected -> {
                            emit(it)
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
