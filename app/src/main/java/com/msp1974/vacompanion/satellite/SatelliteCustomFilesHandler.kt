package com.msp1974.vacompanion.satellite

import android.content.Context
import com.msp1974.vacompanion.device.DeviceManager
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.utils.DownloadStatus
import com.msp1974.vacompanion.utils.CustomFileDownloader
import com.msp1974.vacompanion.utils.Event
import com.msp1974.vacompanion.utils.WakeWordType
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class SatelliteCustomFilesHandler(
    val context: Context,
    val deviceManager: DeviceManager,
    val viewModel: VAViewModel? = null
) {
    private var config = deviceManager.config
    private var customFileDownloader = CustomFileDownloader(context, deviceManager)

    suspend fun downloadAllCustomFiles(force: Boolean = false): Boolean {
        var hasDownloaded = false
        val customFiles = config.customFiles as? JsonObject ?: return false

        // Download Wake Words
        for (wakeWordTypeEntry in customFiles) {
            val typeKey = wakeWordTypeEntry.key
            
            val wakeWordModelType = if (typeKey == WakeWordType.MICROWAKEWORD.toString().lowercase()) {
                WakeWordType.MICROWAKEWORD 
            } else if (typeKey == WakeWordType.OPENWAKEWORD.toString().lowercase()) {
                WakeWordType.OPENWAKEWORD
            } else if (typeKey == WakeWordType.OPENWAKEWORD_RT.toString().lowercase()) {
                WakeWordType.OPENWAKEWORD_RT
            } else {
                continue
            }

            // Handle both JsonObject (map) and JsonArray (list)
            val wakeWordEntries = when (val value = wakeWordTypeEntry.value) {
                is JsonObject -> value.entries.map { it.key to (it.value as? JsonObject) }
                is JsonArray -> value.map { it.jsonPrimitive.content to null as JsonObject? }
                else -> continue
            }

            for ((name, entryConfig) in wakeWordEntries) {
                val configExtensions = entryConfig?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content }
                
                val extensions = configExtensions ?: when (wakeWordModelType) {
                    WakeWordType.MICROWAKEWORD -> listOf("json", "tflite")
                    WakeWordType.OPENWAKEWORD -> listOf("onnx")
                    WakeWordType.OPENWAKEWORD_RT -> listOf("tflite")
                }

                if (wakeWordModelType == WakeWordType.OPENWAKEWORD || wakeWordModelType == WakeWordType.OPENWAKEWORD_RT) {
                    val missingExtensions = mutableListOf<String>()
                    for (ext in extensions) {
                        if (force || !customFileDownloader.wakeWordFileExists(wakeWordModelType, "$name.$ext")) {
                            missingExtensions.add(ext)
                        }
                    }
                    
                    if (missingExtensions.isNotEmpty()) {
                        Timber.d("Download of $name ($wakeWordModelType) files needed: $missingExtensions")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        customFileDownloader.downloadWakeWordModel(wakeWordModelType, name, missingExtensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                } else {
                    if (!extensions.contains("json") || !extensions.contains("tflite")) {
                        Timber.w("Skipping microWakeWord $name: required extensions (json, tflite) not fully specified in config")
                        continue
                    }

                    var downloadNeeded = force
                    if (!downloadNeeded) {
                        for (ext in extensions) {
                            if (!customFileDownloader.wakeWordFileExists(wakeWordModelType, "$name.$ext")) {
                                downloadNeeded = true
                                break
                            }
                        }
                    }

                    if (downloadNeeded) {
                        Timber.d("Download of $name ($wakeWordModelType) needed")
                        val displayName = name.replace("_", " ").capitalizeWords()
                        customFileDownloader.downloadWakeWordModel(wakeWordModelType, name, extensions).collect { status ->
                            handleDownloadStatus(displayName, status)
                        }
                        hasDownloaded = true
                    }
                }
            }
        }

        // Download Sounds and Alarms
        if (downloadAllGenericFiles(customFiles, CustomFileDownloader.SOUNDS_DIR, force)) hasDownloaded = true
        if (downloadAllGenericFiles(customFiles, CustomFileDownloader.ALARMS_DIR, force)) hasDownloaded = true

        return hasDownloaded
    }

    private suspend fun downloadAllGenericFiles(customFiles: JsonObject, subDir: String, force: Boolean = false): Boolean {
        var hasDownloaded = false
        val files = customFiles[subDir] as? JsonObject ?: return false
        
        for (fileEntry in files) {
            val name = fileEntry.key
            val entryConfig = fileEntry.value as? JsonObject
            val extensions = entryConfig?.get("extensions")?.jsonArray?.map { it.jsonPrimitive.content } ?: listOf("xxx")

            for (ext in extensions) {
                val fileName = "$name.$ext"
                val fileExists = java.io.File("${context.filesDir}/${CustomFileDownloader.CUSTOM_DIR}/$subDir", fileName).exists()

                if (force || !fileExists) {
                    Timber.d("Download of $subDir/$fileName needed")
                    val displayName = name.replace("_", " ").capitalizeWords()
                    customFileDownloader.downloadCustomFile(subDir, fileName).collect { status ->
                        handleDownloadStatus(displayName, status)
                    }
                    hasDownloaded = true
                }
            }
        }
        return hasDownloaded
    }

    suspend fun syncAllCustomFiles() {
        // Request settings and wait for update
        config.initSettings = false
        config.eventBroadcaster.notifyEvent(Event("requestSettings", "", ""))
        
        try {
            withTimeout(10000.milliseconds) {
                while (!config.initSettings) {
                    delay(100.milliseconds)
                }
            }
        } catch (e: Exception) {
            Timber.e("Timeout waiting for settings update during sync")
        }

        // Clear all custom files before redownloading
        customFileDownloader.clearAllCustomFiles()
        
        // Download/Update everything
        downloadAllCustomFiles(force = true)
    }

    private fun handleDownloadStatus(displayName: String, status: DownloadStatus) {
        when (status) {
            is DownloadStatus.Progress -> viewModel?.setDownloadProgress(displayName, status.progress)
            is DownloadStatus.Success, is DownloadStatus.Error -> viewModel?.clearDownloadProgress()
        }
    }
}
