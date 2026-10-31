package com.msp1974.vacompanion.broadcasts

import android.os.SystemClock

/**
 * Picks which device answers a wake word that several heard. The master runs this; it holds no
 * network code so the rules can be tested on their own.
 *
 * Decisions are kept per "group", which the master makes the wake word: devices compete for a wake
 * word wherever they are, and only those that reported hearing it are among the contenders.
 *
 * The rules, in order, among the devices that reported:
 *  0. A device that is responding to someone wins. The wake word interrupts its response, so it is
 *     for that device; the others do nothing. (If several are, the rules below choose among them.)
 *  1. The loudest, by calibrated mic level - the assumption being that the speaker is nearest to
 *     it - but only if it beats the next by [LEVEL_MARGIN_DB]. Devices within that margin of the
 *     loudest are too close to call and go on to the next rules.
 *  2. The device that last handled this wake word, if that was within [LAST_HANDLER_MEMORY_MS].
 *  3. The highest wake word score.
 *  4. The lowest device id, so every master reaches the same answer.
 *
 * If any contender didn't report a level (an older version, say), levels are not used at all and
 * the decision starts at rule 2, since a level can't be compared with a missing one.
 */
class WakeWordArbiter(private val clock: () -> Long = SystemClock::elapsedRealtime) {

    /**
     * One device's report of hearing the wake word. [levelDb] is null if it didn't give one.
     * [responding] means it was in the middle of a response when it heard it.
     */
    class Contender(
        val id: String,
        val sender: String,
        val score: Float,
        val levelDb: Float?,
        /** The wake word it heard; null from a version that doesn't say. */
        val wakeWord: String? = null,
        val responding: Boolean = false,
    )

    class Decision(val winnerId: String, val winner: Contender)

    private class Handled(val sender: String, val at: Long)
    private class Decided(val winnerId: String, val at: Long)

    private val lastHandler = HashMap<String, Handled>()
    private val lastDecision = HashMap<String, Decided>()

    /** Decides for [group] (the wake word) among [contenders]. Null if there were none. */
    @Synchronized
    fun decide(group: String, contenders: List<Contender>): Decision? {
        if (contenders.isEmpty()) return null
        val now = clock()
        val handler = lastHandler[group]?.takeIf { now - it.at <= LAST_HANDLER_MEMORY_MS }?.sender
        val best = pick(contenders, handler)
        // A device that interrupted its response didn't answer anything new.
        if (!best.responding) lastHandler[group] = Handled(best.sender, now)
        lastDecision[group] = Decided(best.id, now)
        return Decision(best.id, best)
    }

    /**
     * For a report that arrives after [group]'s window closed: the winner of the decision just made,
     * so the late device can be told it lost instead of waiting and then answering as well. Null if
     * no decision was made lately, meaning this is a fresh wake word.
     */
    @Synchronized
    fun lateVerdict(group: String): String? {
        val decided = lastDecision[group] ?: return null
        return if (clock() - decided.at < LATE_ARRIVAL_MS) decided.winnerId else null
    }

    companion object {
        /** How much louder a device must be than the next to win on level alone. */
        const val LEVEL_MARGIN_DB = 3f

        /** How long the device that last answered is preferred when levels are too close to call. */
        const val LAST_HANDLER_MEMORY_MS = 5 * 60 * 1000L

        /** After a decision, reports for the same wake word arriving within this are late, not new. */
        const val LATE_ARRIVAL_MS = 2_000L

        /** Applies the rules above. [contenders] must not be empty. */
        fun pick(contenders: List<Contender>, lastHandler: String?): Contender {
            // Rule 0: a device that is responding gets the wake word, whatever else is true.
            val candidates = contenders.filter { it.responding }.ifEmpty { contenders }
            val usable = if (candidates.all { it.levelDb != null }) {
                val loudest = candidates.maxOf { it.levelDb!! }
                candidates.filter { it.levelDb!! >= loudest - LEVEL_MARGIN_DB }
            } else {
                candidates
            }
            if (usable.size == 1) return usable.first()
            usable.firstOrNull { it.sender == lastHandler }?.let { return it }
            return usable.maxWith(compareBy<Contender> { it.score }.thenByDescending { it.sender })
        }
    }
}
