package com.msp1974.vacompanion.broadcasts

import com.msp1974.vacompanion.broadcasts.WakeWordArbiter.Contender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseTrackerTest {

    private var now = 0L
    private val tracker = ResponseTracker({ now })

    private val jarvis = "hey_jarvis"
    private val computer = "computer"

    // ---- who is responding

    @Test
    fun nobodyIsRespondingToStartWith() {
        assertNull(tracker.responder(jarvis, "me"))
        assertFalse(tracker.isBusyFor(jarvis, "me"))
    }

    @Test
    fun aDeviceThatStartedAPipelineIsRespondingInItsGroup() {
        tracker.started("a", jarvis)
        assertEquals("a", tracker.responder(jarvis, "me"))
        assertTrue(tracker.isBusyFor(jarvis, "me"))
    }

    @Test
    fun yourOwnResponseDoesNotCountAgainstYou() {
        tracker.started("me", jarvis)
        assertNull(tracker.responder(jarvis, "me"))
        assertFalse(tracker.isBusyFor(jarvis, "me"))
    }

    @Test
    fun aResponseToAnotherWakeWordDoesNotCount() {
        tracker.started("a", computer)
        assertNull(tracker.responder(jarvis, "me"))
        assertFalse(tracker.isBusyFor(jarvis, "me"))
    }

    @Test
    fun aBlankWakeWordHasNoResponder() {
        tracker.started("a", "")
        assertNull(tracker.responder("", "me"))
    }

    @Test
    fun aFinishedResponseIsForgotten() {
        tracker.started("a", jarvis)
        tracker.completed("a")
        assertNull(tracker.responder(jarvis, "me"))
    }

    @Test
    fun aResponseWhoseEndWasNeverHeardIsEventuallyDropped() {
        tracker.started("a", jarvis)
        now = ResponseTracker.PIPELINE_TIMEOUT_MS
        assertEquals("a", tracker.responder(jarvis, "me"))
        now = ResponseTracker.PIPELINE_TIMEOUT_MS + 1
        assertNull(tracker.responder(jarvis, "me"))
    }

    @Test
    fun theChosenDeviceItselfIsNotBusyForItself() {
        tracker.started("a", jarvis)
        assertFalse(tracker.isBusyFor(jarvis, "a"))
        assertTrue(tracker.isBusyFor(jarvis, "b"))
    }

    // ---- interruptions

    @Test
    fun anInterruptionIsRememberedForAMomentOnly() {
        tracker.interrupted(jarvis)
        now = ResponseTracker.INTERRUPTION_HOLD_MS - 1
        assertTrue(tracker.recentlyInterrupted(jarvis))
        now = ResponseTracker.INTERRUPTION_HOLD_MS
        assertFalse(tracker.recentlyInterrupted(jarvis))
    }

    @Test
    fun anInterruptionIsPerWakeWord() {
        tracker.interrupted(jarvis)
        assertFalse(tracker.recentlyInterrupted(computer))
    }

    @Test
    fun nothingIsInterruptedBeforeAnythingIs() {
        assertFalse(tracker.recentlyInterrupted(jarvis))
        assertFalse(tracker.recentlyInterrupted(""))
    }

    @Test
    fun aBlankWakeWordCannotBeInterrupted() {
        tracker.interrupted("")
        assertFalse(tracker.recentlyInterrupted(""))
    }

    @Test
    fun theInterruptionOutlivesTheResponseItInterrupted() {
        // The interrupted device's pipeline ends, so it reports completed - but the group is still
        // known to have just had a response interrupted.
        tracker.started("a", jarvis)
        tracker.interrupted(jarvis)
        tracker.completed("a")
        assertNull(tracker.responder(jarvis, "b"))
        assertTrue(tracker.recentlyInterrupted(jarvis))
    }

    // ---- the case that went wrong: another device hears the interrupting wake word

    private val arbiter = WakeWordArbiter { now }

    private fun busyFor(chosen: String) =
        tracker.isBusyFor(jarvis, chosen) || tracker.recentlyInterrupted(jarvis)

    private fun deviceBHeardIt() = listOf(Contender("b-1", "b", 0.9f, -40f, "jarvis"))

    @Test
    fun aDeviceThatHearsTheInterruptionWhileAnotherRespondsDoesNotWin() {
        tracker.started("a", jarvis)
        val decision = arbiter.decide(jarvis, deviceBHeardIt()) { busyFor(it) }!!
        assertEquals("", decision.winnerId)
    }

    @Test
    fun andNeitherDoesItOnceTheInterruptedDeviceHasFinished() {
        // a is interrupted and finishes before b's report is decided: this used to let b win.
        tracker.started("a", jarvis)
        tracker.interrupted(jarvis)
        tracker.completed("a")
        now = 400
        val decision = arbiter.decide(jarvis, deviceBHeardIt()) { busyFor(it) }!!
        assertEquals("", decision.winnerId)
    }

    @Test
    fun afterTheHoldTheGroupIsFreeToAnswerAgain() {
        tracker.started("a", jarvis)
        tracker.interrupted(jarvis)
        tracker.completed("a")
        now = ResponseTracker.INTERRUPTION_HOLD_MS
        val decision = arbiter.decide(jarvis, deviceBHeardIt()) { busyFor(it) }!!
        assertEquals("b-1", decision.winnerId)
    }
}
