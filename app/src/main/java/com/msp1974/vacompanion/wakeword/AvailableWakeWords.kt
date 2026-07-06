package com.msp1974.vacompanion.wakeword

import android.content.Context
import com.msp1974.vacompanion.utils.CustomFileDownloader.Companion.CUSTOM_DIR
import com.msp1974.vacompanion.utils.CustomFileDownloader.Companion.WAKEWORDS_DIR
import com.msp1974.vacompanion.wakeword.microwakeword.providers.MicroWakeWordAssetProvider
import com.msp1974.vacompanion.wakeword.microwakeword.providers.MicroWakeWordCustomProvider
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import com.msp1974.vacompanion.wakeword.openwakeword.providers.OpenWakeWordAssetProvider
import com.msp1974.vacompanion.wakeword.openwakeword.providers.OpenWakeWordCustomProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.Path

typealias AvailableWakeWordsType = MutableMap<String, List<WakeWordWithId>>
interface AvailableWakeWordProvider{
    suspend fun get(): AvailableWakeWordsType
}

class AvailableWakeWords(
    val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
): AvailableWakeWordProvider {

    override suspend fun get(): AvailableWakeWordsType = withContext(dispatcher) {
        val availableWakeWords: AvailableWakeWordsType =
            mutableMapOf()

        for (wakeWordType in WakeWordEngineModel.entries) {
            val targetDir = Path(
                context.filesDir.absolutePath,
                CUSTOM_DIR,
                WAKEWORDS_DIR,
                wakeWordType.toString().lowercase()
            )

            when (wakeWordType) {
                WakeWordEngineModel.MICROWAKEWORD -> {
                    val assets = MicroWakeWordAssetProvider(context.assets).get()
                    val custom = MicroWakeWordCustomProvider(context, targetDir).get()
                    availableWakeWords[wakeWordType.toString()] = assets + custom
                }

                WakeWordEngineModel.OPENWAKEWORD -> {
                    val assets = OpenWakeWordAssetProvider(
                        context.assets,
                        extension = OpenWakeWordAssetProvider.ONNX_EXT
                    ).get()
                    val custom = OpenWakeWordCustomProvider(context, targetDir).get()
                    availableWakeWords[wakeWordType.toString()] = assets + custom
                }

                WakeWordEngineModel.OPENWAKEWORD_RT -> {
                    val assets = OpenWakeWordAssetProvider(
                        context.assets,
                        extension = OpenWakeWordAssetProvider.TFLITE_EXT
                    ).get()
                    val custom = OpenWakeWordCustomProvider(
                        context,
                        targetDir,
                        extension = OpenWakeWordAssetProvider.TFLITE_EXT
                    ).get()
                    availableWakeWords[wakeWordType.toString()] = assets + custom
                }
            }
        }
        return@withContext availableWakeWords
    }
}