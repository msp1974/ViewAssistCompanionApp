package com.msp1974.vacompanion.sendspin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the album art a Sendspin server points at in its track metadata. The overlay shows one
 * image at a time, so a few recent ones are cached (a track change back and forth, or the overlay
 * reappearing, shouldn't refetch).
 */
object SendspinArtwork {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val TARGET_PX = 512
    private const val TIMEOUT_MS = 5_000

    private val cache = LruCache<String, Bitmap>(4)

    /** The artwork at [url], or null if it can't be fetched or decoded. */
    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) { fetch(url) }?.also { cache.put(url, it) }
    }

    private fun fetch(url: String): Bitmap? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            try {
                if (connection.responseCode !in 200..299) return null
                val bytes = readLimited(connection) ?: return null
                decode(bytes)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Timber.w("Sendspin: artwork fetch failed: $e")
            null
        }
    }

    private fun readLimited(connection: HttpURLConnection): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
        }
        return out.toByteArray()
    }

    // Full-size art can be several thousand pixels square; the overlay needs a small thumbnail.
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
