package com.msp1974.vacompanion.webrtc

import org.webrtc.Loggable
import org.webrtc.Logging
import timber.log.Timber

/**
 * libwebrtc's own log, routed through Timber at warning level and above, with repeats thinned out.
 *
 * Left to itself it writes every info line it has, and when the hardware encoder fails it repeats
 * the same error and stack trace thousands of times a second.
 */
class WebRtcLogger : Loggable {

    private val lastSeen = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()

    @Synchronized
    override fun onLogMessage(message: String, severity: Logging.Severity, tag: String) {
        // Stack trace lines and blank lines are part of the one message above them.
        val trimmed = message.trim()
        if (trimmed.isEmpty() || message.startsWith("\tat ")) return

        val key = "$tag:${trimmed.take(KEY_LENGTH)}"
        val now = System.currentTimeMillis()
        val last = lastSeen[key]
        if (last != null && now - last < REPEAT_WINDOW_MS) {
            suppressed[key] = (suppressed[key] ?: 0) + 1
            return
        }
        lastSeen[key] = now
        if (lastSeen.size > MAX_KEYS) { lastSeen.clear(); suppressed.clear() }
        val skipped = suppressed.remove(key)
        val text = "WebRTC/$tag: $trimmed" + if (skipped != null) " (repeated $skipped more times)" else ""
        when (severity) {
            Logging.Severity.LS_ERROR -> Timber.e(text)
            else -> Timber.w(text)
        }
    }

    companion object {
        private const val REPEAT_WINDOW_MS = 5_000L
        private const val KEY_LENGTH = 80
        private const val MAX_KEYS = 200
    }
}
