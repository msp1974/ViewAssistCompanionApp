package com.msp1974.vacompanion.broadcasts

import android.os.SystemClock

/**
 * Which other devices are around, and which wake word each listens for, so wake word arbitration
 * only waits for devices that could matter.
 *
 * Every device announces itself now and then (and when it comes up), and every message from a
 * device counts as hearing from it. A device is a *competitor* for a wake word if it listens for
 * that wake word, wherever it is (arbitration is by wake word, not by area), or hasn't said which.
 * If there are no competitors a device can simply answer, and if there are, the master can decide
 * the moment every one of them has reported instead of waiting out the window.
 *
 * Mistakes are made to cost time, not correctness: a device that has gone is counted until
 * [timeoutMs] passes (arbitration just waits as it used to), and one whose wake word is unknown is
 * assumed to compete. The one way to go wrong the other way is a competitor that hasn't been heard
 * for [timeoutMs] while still listening, such as one running a version that doesn't announce itself.
 */
class PeerPresence(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val timeoutMs: Long = PEER_TIMEOUT_MS,
) {
    private class Peer(val wakeWord: String?, val at: Long)

    private val peers = HashMap<String, Peer>()

    /**
     * Notes that [sender] has just been heard from. [wakeWord] is the wake word it listens for if
     * the message said; null leaves whatever was last announced.
     */
    @Synchronized
    fun seen(sender: String, wakeWord: String? = null) {
        peers[sender] = Peer(wakeWord ?: peers[sender]?.wakeWord, clock())
    }

    /** The devices other than [self] that could hear [wakeWord], heard from lately. */
    @Synchronized
    fun competitors(wakeWord: String?, self: String): Set<String> {
        val now = clock()
        peers.entries.removeIf { now - it.value.at > timeoutMs }
        return peers.filter { (sender, peer) -> sender != self && competes(peer.wakeWord, wakeWord) }.keys
    }

    /**
     * Everyone who could have heard [wakeWord]: its [competitors] and, if [selfCompetes], [self]
     * too. When all of them have reported there is nobody left to wait for.
     */
    fun expectedReporters(wakeWord: String?, self: String, selfCompetes: Boolean): Set<String> =
        competitors(wakeWord, self) + if (selfCompetes) setOf(self) else emptySet()

    companion object {
        /** About three missed announcements. */
        const val PEER_TIMEOUT_MS = 35_000L

        /**
         * Whether a device listening for [peerWakeWord] could hear [wakeWord]. Unknown on either
         * side counts as yes. A device with no wake word ("none") hears nothing.
         */
        fun competes(peerWakeWord: String?, wakeWord: String?): Boolean {
            if (peerWakeWord == NO_WAKE_WORD) return false
            if (peerWakeWord == null || wakeWord == null) return true
            return peerWakeWord.equals(wakeWord, ignoreCase = true)
        }

        private const val NO_WAKE_WORD = "none"
    }
}
