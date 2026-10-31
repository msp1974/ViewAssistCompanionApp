package com.msp1974.vacompanion.broadcasts

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordArbiterTest {

    private var now = 0L
    private val arbiter = WakeWordArbiter { now }

    private fun device(sender: String, level: Float?, score: Float = 0.9f, responding: Boolean = false) =
        Contender(id = "$sender-1", sender = sender, score = score, levelDb = level, responding = responding)

    private fun pick(vararg contenders: Contender, lastHandler: String? = null) =
        WakeWordArbiter.pick(contenders.toList(), lastHandler).sender

    private fun decide(vararg contenders: Contender, group: String = "hey_jarvis") =
        arbiter.decide(group, contenders.toList())

    // ---- level is the main rule

    @Test
    fun theLoudestDeviceWinsWhenClearlyLoudest() {
        assertEquals("a", pick(device("a", -30f), device("b", -36f)))
    }

    @Test
    fun levelBeatsAHigherScore() {
        assertEquals("a", pick(device("a", -30f, score = 0.60f), device("b", -40f, score = 0.99f)))
    }

    @Test
    fun levelBeatsBeingTheLastToAnswer() {
        assertEquals("a", pick(device("a", -30f), device("b", -40f), lastHandler = "b"))
    }

    @Test
    fun thePickedDeviceIsTheLoudestOfSeveral() {
        assertEquals("c", pick(device("a", -40f), device("b", -38f), device("c", -29f)))
    }

    // ---- the margin

    @Test
    fun aGapOfLessThanTheMarginIsTooCloseToCall() {
        // 2 dB apart: level can't decide, so the last to answer does.
        assertEquals("b", pick(device("a", -30f), device("b", -32f), lastHandler = "b"))
    }

    @Test
    fun aGapOfExactlyTheMarginIsStillTooCloseToCall() {
        assertEquals("b", pick(device("a", -30f), device("b", -33f), lastHandler = "b"))
    }

    @Test
    fun aGapJustOverTheMarginIsDecidedOnLevel() {
        assertEquals("a", pick(device("a", -30f), device("b", -33.1f), lastHandler = "b"))
    }

    // ---- the fallbacks, in order

    @Test
    fun whenTooCloseTheLastDeviceToAnswerIsPreferredEvenWithALowerScore() {
        assertEquals("b", pick(device("a", -30f, score = 0.99f), device("b", -31f, score = 0.70f), lastHandler = "b"))
    }

    @Test
    fun aLastHandlerOutsideTheCloseGroupIsIgnored() {
        // c answered last but is far quieter than a and b, so it is not in the running.
        assertEquals("a", pick(device("a", -30f, score = 0.95f), device("b", -31f, score = 0.80f), device("c", -50f), lastHandler = "c"))
    }

    @Test
    fun withNoLastHandlerTheHighestScoreWins() {
        assertEquals("b", pick(device("a", -30f, score = 0.80f), device("b", -31f, score = 0.95f)))
    }

    @Test
    fun withEqualScoresTheLowestDeviceIdWins() {
        assertEquals("a", pick(device("b", -30f, score = 0.9f), device("a", -31f, score = 0.9f)))
    }

    @Test
    fun aSingleDeviceWinsWhateverItsLevel() {
        assertEquals("a", pick(device("a", -75f)))
    }

    // ---- a device that gave no level

    @Test
    fun ifAnyDeviceHasNoLevelLevelsAreNotUsedAtAll() {
        // a is far louder, but b can't be compared with it, so the score decides.
        assertEquals("b", pick(device("a", -20f, score = 0.7f), device("b", null, score = 0.9f)))
    }

    @Test
    fun withoutLevelsTheLastHandlerStillComesBeforeScore() {
        assertEquals("a", pick(device("a", null, score = 0.7f), device("b", null, score = 0.9f), lastHandler = "a"))
    }

    // ---- a device that is responding always wins

    @Test
    fun aRespondingDeviceWinsEvenWhenTheOthersAreMuchLouder() {
        assertEquals("b", pick(device("a", -20f), device("b", -50f, responding = true)))
    }

    @Test
    fun aRespondingDeviceWinsOverAHigherScoreAndOverBeingTheLastToAnswer() {
        assertEquals(
            "b",
            pick(device("a", -30f, score = 0.99f), device("b", -45f, score = 0.60f, responding = true), lastHandler = "a")
        )
    }

    @Test
    fun aRespondingDeviceWinsWithoutALevelToo() {
        assertEquals("b", pick(device("a", -20f), device("b", null, responding = true)))
    }

    @Test
    fun aRespondingDeviceWinsAgainstSeveralOthers() {
        assertEquals("c", pick(device("a", -20f), device("b", -22f), device("c", -60f, responding = true)))
    }

    @Test
    fun whenSeveralAreRespondingTheUsualRulesChooseAmongThem() {
        // The loud one that is not responding is out; of the two that are, the louder wins.
        assertEquals(
            "c",
            pick(device("a", -10f), device("b", -50f, responding = true), device("c", -40f, responding = true))
        )
    }

    @Test
    fun theRespondingRuleIsOnlyAboutResponding() {
        // Nobody is responding, so it is the usual rules.
        assertEquals("a", pick(device("a", -30f), device("b", -45f)))
    }

    // ---- remembering who answered last

    @Test
    fun theDeviceThatAnsweredIsPreferredNextTimeWithinFiveMinutes() {
        val first = decide(device("a", -30f), device("b", -45f))!!
        assertEquals("a", first.winner.sender)

        now = 4 * 60 * 1000L // four minutes on
        val second = decide(device("a", -40f, score = 0.7f), device("b", -41f, score = 0.99f))!!
        assertEquals("a", second.winner.sender)
    }

    @Test
    fun afterFiveMinutesThePreviousAnswererNoLongerCounts() {
        decide(device("a", -30f), device("b", -45f))
        now = WakeWordArbiter.LAST_HANDLER_MEMORY_MS + 1
        val second = decide(device("a", -40f, score = 0.7f), device("b", -41f, score = 0.99f))!!
        assertEquals("b", second.winner.sender)
    }

    @Test
    fun theMemoryIsKeptPerWakeWord() {
        decide(device("a", -30f), device("b", -45f), group = "hey_jarvis")
        val other = decide(device("a", -40f, score = 0.7f), device("b", -41f, score = 0.99f), group = "computer")!!
        assertEquals("b", other.winner.sender)
    }

    @Test
    fun aDeviceThatOnlyInterruptedItsResponseIsNotRememberedAsHavingAnswered() {
        decide(device("a", -30f, responding = true), device("b", -31f))
        now = 10_000L
        // a would be preferred if it counted as the last to answer; it doesn't, so the score decides.
        val next = decide(device("a", -40f, score = 0.7f), device("b", -41f, score = 0.99f))!!
        assertEquals("b", next.winner.sender)
    }

    // ---- nothing to decide, late arrivals

    @Test
    fun noContendersMeansNoDecision() {
        assertNull(arbiter.decide("hey_jarvis", emptyList()))
    }

    @Test
    fun theDecisionNamesTheWinningReport() {
        val decision = decide(device("a", -30f), device("b", -45f))!!
        assertEquals("a-1", decision.winnerId)
    }

    @Test
    fun aLateReportIsToldWhoWonRatherThanLeftToGoAhead() {
        val decision = decide(device("a", -30f), device("b", -45f))!!
        now = 400L
        assertEquals(decision.winnerId, arbiter.lateVerdict("hey_jarvis"))
    }

    @Test
    fun aReportThatArrivesAfterAnInterruptionWasDecidedLosesToTheInterruptingDevice() {
        val decision = decide(device("a", -50f, responding = true))!!
        now = 300L
        assertEquals(decision.winnerId, arbiter.lateVerdict("hey_jarvis"))
    }

    @Test
    fun aReportLongAfterTheDecisionIsAFreshWakeWord() {
        decide(device("a", -30f), device("b", -45f))
        now = WakeWordArbiter.LATE_ARRIVAL_MS
        assertNull(arbiter.lateVerdict("hey_jarvis"))
    }

    @Test
    fun nothingIsLateBeforeTheFirstDecision() {
        assertNull(arbiter.lateVerdict("hey_jarvis"))
    }

    @Test
    fun lateVerdictsAreKeptPerWakeWord() {
        decide(device("a", -30f), device("b", -45f), group = "hey_jarvis")
        assertNull(arbiter.lateVerdict("computer"))
        assertTrue(arbiter.lateVerdict("hey_jarvis") != null)
    }
}
