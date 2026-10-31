package com.msp1974.vacompanion.wakeword

import com.msp1974.vacompanion.wakeword.DetectionRearmGate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

// REARM-FILTER: remove with DetectionRearmGate.
class DetectionRearmGateTest {

    private var now = 0L
    private val gate = DetectionRearmGate({ now })
    private val threshold = 0.8f

    /** One score event [afterMs] after the last, as the engines report them (~30 ms apart). */
    private fun event(afterMs: Long, score: Float, id: String = "jarvis"): Outcome {
        now += afterMs
        return gate.onScore(id, score, detected = score >= threshold, threshold = threshold)
    }

    // ---- one utterance, one detection

    @Test
    fun theFirstScoreOverTheThresholdIsAFreshDetection() {
        assertEquals(Outcome.FRESH, event(0, 0.9f))
    }

    @Test
    fun scoresUnderTheThresholdAreNotDetections() {
        assertEquals(Outcome.NOT_A_DETECTION, event(0, 0.4f))
        assertEquals(Outcome.NOT_A_DETECTION, event(30, 0.79f))
    }

    @Test
    fun theRestOfTheSameWordIsResidualNotAnotherDetection() {
        assertEquals(Outcome.FRESH, event(0, 0.97f))
        for (i in 1..6) assertEquals(Outcome.RESIDUAL, event(30, 0.97f))
    }

    @Test
    fun scoresThatFallAwayButStayHighAreStillTheSameWord() {
        // Dipping under the threshold is not coming down: the word has not ended.
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        assertEquals(Outcome.NOT_A_DETECTION, event(30, 0.7f))
        assertEquals(Outcome.RESIDUAL, event(30, 0.9f))
    }

    // ---- a real new detection

    @Test
    fun aScoreThatComesBackDownThenUpAgainIsFresh() {
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        assertEquals(Outcome.NOT_A_DETECTION, event(30, 0.3f)) // under half the threshold
        assertEquals(Outcome.FRESH, event(30, 0.95f))
    }

    @Test
    fun theRearmLevelIsHalfTheThreshold() {
        event(0, 0.95f)
        event(30, 0.41f) // just over half of 0.8: not down yet
        assertEquals(Outcome.RESIDUAL, event(30, 0.9f))
        event(30, 0.39f) // under half
        assertEquals(Outcome.FRESH, event(30, 0.9f))
    }

    @Test
    fun aPauseInTheEventsAlsoRearmsBecauseTheEnginesStopReportingBelowTheirFloor() {
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        assertEquals(Outcome.FRESH, event(DetectionRearmGate.GAP_MS, 0.95f))
    }

    @Test
    fun aShorterPauseDoesNot() {
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        assertEquals(Outcome.RESIDUAL, event(DetectionRearmGate.GAP_MS - 1, 0.95f))
    }

    @Test
    fun aDetectionTheNextSecondAfterSilenceIsFresh() {
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        assertEquals(Outcome.RESIDUAL, event(30, 0.95f))
        assertEquals(Outcome.FRESH, event(1500, 0.95f))
    }

    // ---- not locking the wake word out

    @Test
    fun aScoreThatNeverComesDownIsEventuallyLetThroughAgain() {
        assertEquals(Outcome.FRESH, event(0, 0.95f))
        // Events 30 ms apart for ten seconds, never dropping.
        var last = Outcome.RESIDUAL
        var elapsed = 0L
        while (elapsed < DetectionRearmGate.MAX_HOLD_MS) {
            last = event(30, 0.95f)
            elapsed += 30
            if (elapsed < DetectionRearmGate.MAX_HOLD_MS) assertEquals(Outcome.RESIDUAL, last)
        }
        assertEquals(Outcome.FRESH, last)
    }

    // ---- bookkeeping

    @Test
    fun wakeWordsAreTrackedSeparately() {
        assertEquals(Outcome.FRESH, event(0, 0.95f, id = "jarvis"))
        assertEquals(Outcome.FRESH, event(10, 0.95f, id = "computer"))
        assertEquals(Outcome.RESIDUAL, event(10, 0.95f, id = "jarvis"))
        assertEquals(Outcome.RESIDUAL, event(10, 0.95f, id = "computer"))
    }

    @Test
    fun theThresholdInForceDecidesWhatCountsAsDown() {
        // The same score is "down" under a high threshold and not under a low one.
        now = 0
        assertEquals(Outcome.FRESH, gate.onScore("a", 0.95f, true, threshold = 0.9f))
        now += 30
        gate.onScore("a", 0.44f, false, threshold = 0.9f) // under 0.45 = half of 0.9
        now += 30
        assertEquals(Outcome.FRESH, gate.onScore("a", 0.95f, true, threshold = 0.9f))

        assertEquals(Outcome.FRESH, gate.onScore("b", 0.95f, true, threshold = 0.5f))
        now += 30
        gate.onScore("b", 0.44f, false, threshold = 0.5f) // over 0.25 = half of 0.5
        now += 30
        assertEquals(Outcome.RESIDUAL, gate.onScore("b", 0.95f, true, threshold = 0.5f))
    }

    @Test
    fun theIgnoredDetectionsAreCountedUntilTheNextFreshOne() {
        event(0, 0.95f)
        repeat(4) { event(30, 0.95f) }
        assertEquals(4, gate.suppressed("jarvis"))
        assertEquals(4, gate.takeSuppressed("jarvis"))
        assertEquals(0, gate.suppressed("jarvis"))
        assertEquals(0, gate.takeSuppressed("unknown"))
    }

    @Test
    fun aRealisticUtteranceGivesExactlyOneDetection() {
        // A word scoring over the threshold for ~10 frames, silence, then another word.
        val outcomes = mutableListOf<Outcome>()
        outcomes += event(0, 0.5f)
        repeat(10) { outcomes += event(30, 0.96f) }
        outcomes += event(30, 0.6f)
        outcomes += event(30, 0.2f)
        outcomes += event(2000, 0.9f) // the next word, two seconds later
        repeat(5) { outcomes += event(30, 0.93f) }
        assertEquals(2, outcomes.count { it == Outcome.FRESH })
        assertEquals(14, outcomes.count { it == Outcome.RESIDUAL })
    }
}
