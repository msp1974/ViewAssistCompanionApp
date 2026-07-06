package com.msp1974.vacompanion.wakeword.openwakeword.providers

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.msp1974.vacompanion.utils.Helpers.Companion.capitalizeWords
import com.msp1974.vacompanion.wakeword.WakeWordProvider
import com.msp1974.vacompanion.wakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.name

class OpenWakeWordCustomProvider(
    val context: Context,
    val path: Path,
    private val extension: String = OpenWakeWordAssetProvider.ONNX_EXT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : WakeWordProvider {
    @SuppressLint("ThrowableNotAtBeginning")
    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun get(): List<WakeWordWithId> = withContext(dispatcher) {
        if (!path.exists()) return@withContext emptyList()
        val wakeWords = buildList {
            path.forEachDirectoryEntry("*$extension") { file ->
                val model = file.name
                runCatching {
                    val id = model.substring(0, model.lastIndexOf(extension))
                    val wakeWord = WakeWord(
                        type = "open",
                        wake_word = id.replace("_", " ").capitalizeWords(),
                        model = model,
                        micro = OpenWakeWordAssetProvider.empty_micro
                    )
                    add(WakeWordWithId(id, wakeWord) { loadModel(wakeWord.model) })
                }.onFailure {
                    Timber.e(it, "Error loading wake word: $model")
                }
            }
        }
        return@withContext wakeWords
    }

    private suspend fun loadModel(model: String): ByteBuffer = withContext(dispatcher) {
        val file = File(path.toAbsolutePath().toString() , model)
        val buffer = context.contentResolver.getModelBufferOrNull(Uri.fromFile(file))
            ?: error("Could not load model $model")
        return@withContext buffer
    }

    /**
     * Attempts to get a ByteBuffer for a model from the given uri.
     * Requires that the ByteBuffer is either a Direct or Mapped ByteBuffer, in native order.
     * If the uri points to a local file a MappedByteBuffer is returned else the stream is copied
     * to a Direct ByteBuffer.
     * If the uri is invalid, returns null.
     */
    private fun ContentResolver.getModelBufferOrNull(uri: Uri): ByteBuffer? {
        try {
            val file = File(uri.path ?: "")
            val bytes = file.readBytes()
            return ByteBuffer.wrap(bytes)
        } catch (e: Exception) {
            return null
        }
    }
}