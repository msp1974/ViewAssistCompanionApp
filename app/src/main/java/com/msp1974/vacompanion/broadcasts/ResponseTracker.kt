package com.msp1974.vacompanion.broadcasts

import android.os.SystemClock

/**
 * Which devices are responding to someone (have an audio pipeline running), and which group had a
 * response interrupted a moment ago. Every device keeps one, fed by the others' broadcasts.
 *
 * A wake word said while a device is responding is for that device - an interruption - so no other
 * device in its group should answer it. Knowing a response is running isn't enough to guarantee
 * that: interrupting it ends its pipeline, which says it has completed, so a moment later it looks
 * as if nobody is responding and the other devices that heard the same wake word would go ahead.
 * [interrupted] is remembered for [interruptionHoldMs] to cover that gap.
 */
class ResponseTracker(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val pipelineTimeoutMs: Long = PIPELINE_TIMEOUT_MS,
    private val interruptionHoldMs: Long = INTERRUPTION_HOLD_MS,
) {
    private class Active(val group: String, val since: Long)

    private val active = HashMap<String, Active>()
    private val interruptedAt = HashMap<String, Long>()

    /** [sender] has a pipeline running, having claimed [group]. */
    @Synchronized
    fun started(sender: String, group: String) {
        active[sender] = Active(group, clock())
    }

    /** [sender]'s pipeline has finished, however it ended. */
    @Synchronized
    fun completed(sender: String) {
        active.remove(sender)
    }

    /** A response in [group] was just interrupted by a wake word. */
    @Synchronized
    fun interrupted(group: String) {
        if (group.isNotEmpty()) interruptedAt[group] = clock()
    }

    /** Another device than [self] in [group] that is responding, or null. Blank groups match nothing. */
    @Synchronized
    fun responder(group: String, self: String): String? {
        if (group.isEmpty()) return null
        prune()
        return active.entries.firstOrNull { it.key != self && it.value.group == group }?.key
    }

    /** Whether a device other than [chosen] is responding in [group]. */
    @Synchronized
    fun isBusyFor(group: String, chosen: String): Boolean {
        prune()
        return active.any { (sender, a) -> sender != chosen && a.group == group }
    }

    /** Whether a response in [group] was interrupted within the last [interruptionHoldMs]. */
    @Synchronized
    fun recentlyInterrupted(group: String): Boolean {
        if (group.isEmpty()) return false
        val at = interruptedAt[group] ?: return false
        return clock() - at < interruptionHoldMs
    }

    // Failsafe so a pipeline whose completion was never heard doesn't hold its group forever.
    private fun prune() {
        val now = clock()
        active.entries.removeIf { now - it.value.since > pipelineTimeoutMs }
    }

    companion object {
        const val PIPELINE_TIMEOUT_MS = 90_000L

        /** Long enough for the other devices' reports of the same wake word to arrive. */
        const val INTERRUPTION_HOLD_MS = 2_000L
    }
}
