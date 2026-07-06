package com.msp1974.vacompanion.data

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Class responsible for aggregating available alarms from assets and custom storage.
 */
class AvailableAlarms(
    private val context: Context,
    private val deviceManager: DeviceManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Returns a combined list of alarms from assets and custom storage.
     */
    suspend fun get(): List<AvailableAlarm> = withContext(dispatcher) {
        val downloader = CustomFileDownloader(context, deviceManager)
        val customAlarms = downloader.listAvailableCustomAlarms()
        
        val assetAlarms = try {
            context.assets.list("alarm")?.map { fileName ->
                val id = fileName.substringBeforeLast(".")
                AvailableAlarm(
                    id = id,
                    name = formatDisplayName(id),
                    custom = false,
                    filename = fileName
                )
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val customWithId = customAlarms.map { it.copy(id = it.id) }

        return@withContext (assetAlarms + customWithId).sortedBy { it.name }
    }

    private fun formatDisplayName(name: String): String {
        return name.map { char ->
            if (char.isLetterOrDigit()) char else ' '
        }.joinToString("").trim().replace(Regex("\\s+"), " ").capitalizeWords()
    }
}
