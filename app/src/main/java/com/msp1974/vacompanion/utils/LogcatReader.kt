package com.msp1974.vacompanion.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/**
 * Reads this app's own logcat output. An app can always read its own process's log entries without
 * the READ_LOGS permission, so this only ever returns VACA's logs.
 */
object LogcatReader {
    const val DEFAULT_LINES = 1000
    const val MAX_LINES = 10000
    private const val TIMEOUT_MS = 10_000L
    private val LEVELS = setOf("V", "D", "I", "W", "E", "F")

    data class LogResult(val text: String, val lines: Int, val level: String)

    /**
     * @param lines most recent N lines to return (clamped to 1..MAX_LINES)
     * @param level minimum priority: V, D, I, W, E or F (anything else falls back to V)
     */
    suspend fun read(lines: Int = DEFAULT_LINES, level: String = "D"): LogResult = withContext(Dispatchers.IO) {
        val count = lines.coerceIn(1, MAX_LINES)
        val prio = level.trim().uppercase().take(1).takeIf { it in LEVELS } ?: "V"
        val pid = android.os.Process.myPid().toString()

        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "*:$prio")
            .redirectErrorStream(true)
            .start()
        try {
            // Keep only a rolling window of the last `count` lines so memory stays bounded.
            val window = ArrayDeque<String>(count)
            withTimeout(TIMEOUT_MS) {
                process.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        if (window.size == count) window.removeFirst()
                        window.addLast(line)
                    }
                }
            }
            LogResult(window.joinToString("\n"), window.size, prio)
        } catch (e: Exception) {
            Timber.e("Failed to read logcat: $e")
            throw e
        } finally {
            process.destroy()
        }
    }
}
