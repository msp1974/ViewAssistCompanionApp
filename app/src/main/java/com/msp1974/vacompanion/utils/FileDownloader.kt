package com.msp1974.vacompanion.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.msp1974.vacompanion.device.authentication.HttpClientProvider
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

object FileDownloader {

    private val client = HttpClientProvider().get()

    /**
     * Downloads a file from the given [url] and saves it to the public Downloads folder.
     * Supports Android 8 (API 26) through Android 16.
     *
     * @param context Application context
     * @param url The URL to download from
     * @param fileName The name to save the file as (e.g., "update.apk")
     * @return The Uri of the downloaded file, or null if the download failed.
     */
    suspend fun downloadFileToDownloads(
        context: Context,
        url: String,
        fileName: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) {
                    Timber.e("Failed to download file: ${response.status.value} ${response.status.description}")
                    return@execute null
                }

                val channel = response.bodyAsChannel()
                val mimeType = response.contentType()?.toString() ?: "application/octet-stream"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ (API 29+) using MediaStore (Scoped Storage)
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: return@execute null

                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val buffer = ByteArray(8192)
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                    uri
                } else {
                    // Android 8-9 (API 26-28) using traditional File API
                    // REQUIRES: <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
                    @Suppress("DEPRECATION")
                    val targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!targetDir.exists() && !targetDir.mkdirs()) {
                        Timber.e("Failed to create Downloads directory")
                        return@execute null
                    }

                    val file = File(targetDir, fileName)
                    FileOutputStream(file).use { outputStream ->
                        val buffer = ByteArray(8192)
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break
                            outputStream.write(buffer, 0, bytesRead)
                        }
                    }
                    
                    // Scan the file so it shows up in the Downloads app immediately
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                    
                    Uri.fromFile(file)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error downloading file from $url")
            null
        }
    }
}
