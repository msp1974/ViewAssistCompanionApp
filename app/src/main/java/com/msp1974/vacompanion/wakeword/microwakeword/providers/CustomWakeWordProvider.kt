package com.msp1974.vacompanion.wakeword.microwakeword.providers

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.msp1974.vacompanion.utils.copyTo
import com.msp1974.vacompanion.wakeword.WakeWordProvider
import com.msp1974.vacompanion.wakeword.models.WakeWord
import com.msp1974.vacompanion.wakeword.models.WakeWordWithId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class MicroWakeWordCustomProvider (
    val context: Context,
    val path: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : WakeWordProvider {
        @SuppressLint("ThrowableNotAtBeginning")
        @OptIn(ExperimentalSerializationApi::class)
        override suspend fun get(): List<WakeWordWithId> = withContext(dispatcher) {
            if (!path.exists()) return@withContext emptyList()
            val wakeWords = buildList {
                path.listFiles { file -> file.name.endsWith(".json") }?.forEach { file ->
                    val name = file.name
                    runCatching {
                        val uri = Uri.fromFile(file)
                        val wakeWord = context.contentResolver.openInputStream(uri)?.use {
                            Json.decodeFromStream<WakeWord>(it)
                        }
                        val id = file.name.substring(0, file.name.lastIndexOf(".json"))
                        if (wakeWord != null) {
                            add(
                                WakeWordWithId(
                                    id,
                                    wakeWord
                                ) { loadModel(wakeWord.model) })
                        }
                    }.onFailure {
                        Timber.e(it, "Error loading wake word: $name")
                    }
                }
            }
            return@withContext wakeWords
        }

        private suspend fun loadModel(model: String): ByteBuffer = withContext(dispatcher) {
            val file = File(path, model)
            val buffer = context.contentResolver.getTFLiteModelBufferOrNull(Uri.fromFile(file))
                ?: error("Could not load model $model")
            return@withContext buffer
        }

        /**
         * Attempts to get a ByteBuffer for a model from the given uri.
         * TFLite requires that the ByteBuffer is either a Direct or Mapped ByteBuffer, in native order.
         * If the uri points to a local file a MappedByteBuffer is returned else the stream is copied
         * to a Direct ByteBuffer.
         * If the uri is invalid, returns null.
         */
        private fun ContentResolver.getTFLiteModelBufferOrNull(uri: Uri): ByteBuffer? =
            openFileDescriptor(uri, "r")?.use { descriptor ->
                // If statSize is >= 0 then the descriptor points to a local file
                // with the given size and can be mapped directly
                val size = descriptor.statSize
                if (size >= 0) {
                    return FileInputStream(descriptor.fileDescriptor).use { stream ->
                        stream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            0,
                            size
                        ).apply {
                            order(ByteOrder.nativeOrder())
                        }
                    }
                }

                // If not a local file, try and query the size and copy to a direct byte buffer
                return query(uri, null, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex: Int = it.getColumnIndex(OpenableColumns.SIZE)
                        val size = if (it.isNull(sizeIndex)) 0 else it.getLong(sizeIndex)
                        if (size >= 0) {
                            openInputStream(uri)?.use {
                                return ByteBuffer.allocateDirect(size.toInt()).apply {
                                    order(ByteOrder.nativeOrder())
                                    it.copyTo(this)
                                    flip()
                                }
                            }
                        }
                    }
                    return null
                }
            }
}