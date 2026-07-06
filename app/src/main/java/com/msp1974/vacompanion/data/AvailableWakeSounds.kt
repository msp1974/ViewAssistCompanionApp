package com.msp1974.vacompanion.data

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Class responsible for aggregating available wake sounds from assets and custom storage.
 */
class AvailableWakeSounds(
    private val context: Context,
    private val deviceManager: DeviceManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Returns a combined list of wake sounds from assets and custom storage.
     */
    suspend fun get(): List<AvailableWakeSound> = withContext(dispatcher) {
        val downloader = CustomFileDownloader(context, deviceManager)
        val customSounds = downloader.listAvailableCustomWakeSounds()
        
        val assetSounds = try {
            context.assets.list("wakeSounds")?.map { fileName ->
                val id = fileName.substringBeforeLast(".")
                AvailableWakeSound(
                    id = id,
                    name = formatDisplayName(id),
                    custom = false,
                    filename = fileName
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val customWithId = customSounds.map { it.copy(id = it.id) }

        return@withContext (assetSounds + customWithId).sortedBy { it.name }
    }

    private fun formatDisplayName(name: String): String {
        return name.map { char ->
            if (char.isLetterOrDigit()) char else ' '
        }.joinToString("").trim().replace(Regex("\\s+"), " ").capitalizeWords()
    }
}
