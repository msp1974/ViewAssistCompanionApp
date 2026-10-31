package com.msp1974.vacompanion.broadcasts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPresenceTest {

    private var now = 0L
    private val presence = PeerPresence({ now })

    private fun competitors(wakeWord: String? = "jarvis", self: String = "me") =
        presence.competitors(wakeWord, self)

    // ---- who is around

    @Test
    fun aDeviceThatHasHeardFromNobodyHasNoCompetitors() {
        assertTrue(competitors().isEmpty())
    }

    @Test
    fun aDeviceListeningForTheSameWakeWordIsACompetitor() {
        presence.seen("b", "jarvis")
        assertEquals(setOf("b"), competitors())
    }

    @Test
    fun whereTheDeviceIsDoesNotMatter() {
        // Arbitration is by wake word, so a device in another room competes just the same. Nothing
        // here even records an area.
        presence.seen("kitchen-device", "jarvis")
        presence.seen("bedroom-device", "jarvis")
        assertEquals(setOf("kitchen-device", "bedroom-device"), competitors())
    }

    @Test
    fun yourselfIsNeverYourOwnCompetitor() {
        presence.seen("me", "jarvis")
        assertTrue(competitors(self = "me").isEmpty())
    }

    @Test
    fun severalDevicesAreAllCounted() {
        presence.seen("b", "jarvis")
        presence.seen("c", "jarvis")
        presence.seen("d", "computer")
        assertEquals(setOf("b", "c"), competitors())
    }

    // ---- going away

    @Test
    fun aDeviceNotHeardFromForTooLongIsGone() {
        presence.seen("b", "jarvis")
        now = PeerPresence.PEER_TIMEOUT_MS
        assertEquals(setOf("b"), competitors()) // exactly at the limit is still here
        now = PeerPresence.PEER_TIMEOUT_MS + 1
        assertTrue(competitors().isEmpty())
    }

    @Test
    fun hearingFromADeviceAgainKeepsItAround() {
        presence.seen("b", "jarvis")
        now = 30_000
        presence.seen("b")
        now = 60_000 // 30 s since it was last heard
        assertEquals(setOf("b"), competitors())
    }

    // ---- which wake word

    @Test
    fun aDeviceListeningForADifferentWakeWordIsNotACompetitor() {
        presence.seen("b", "computer")
        assertTrue(competitors(wakeWord = "jarvis").isEmpty())
    }

    @Test
    fun wakeWordsAreComparedIgnoringCase() {
        presence.seen("b", "Hey_Jarvis")
        assertEquals(setOf("b"), competitors(wakeWord = "hey_jarvis"))
    }

    @Test
    fun aDeviceWithNoWakeWordCompetesWithNobody() {
        presence.seen("b", "none")
        assertTrue(competitors(wakeWord = "jarvis").isEmpty())
    }

    @Test
    fun aDeviceThatHasNotSaidWhichWakeWordIsAssumedToCompete() {
        presence.seen("b") // e.g. only its hello was heard
        assertEquals(setOf("b"), competitors(wakeWord = "jarvis"))
    }

    @Test
    fun ifOurOwnWakeWordIsUnknownEveryoneCompetes() {
        presence.seen("b", "computer")
        assertEquals(setOf("b"), competitors(wakeWord = null))
    }

    @Test
    fun aMessageThatDoesNotSayTheWakeWordKeepsWhatWasAnnounced() {
        presence.seen("b", "computer")
        presence.seen("b") // e.g. a duck message
        assertTrue(competitors(wakeWord = "jarvis").isEmpty())
    }

    @Test
    fun aDeviceChangingItsWakeWordIsFollowed() {
        presence.seen("b", "computer")
        assertTrue(competitors(wakeWord = "jarvis").isEmpty())
        presence.seen("b", "jarvis")
        assertEquals(setOf("b"), competitors(wakeWord = "jarvis"))
    }

    // ---- waiting for everyone who could have heard

    @Test
    fun theReportersToWaitForAreTheCompetitorsAndUsIfWeCompete() {
        presence.seen("b", "jarvis")
        assertEquals(setOf("b", "me"), presence.expectedReporters("jarvis", "me", selfCompetes = true))
        assertEquals(setOf("b"), presence.expectedReporters("jarvis", "me", selfCompetes = false))
    }

    @Test
    fun withNobodyAroundOnlyUsIsExpected() {
        assertEquals(setOf("me"), presence.expectedReporters("jarvis", "me", selfCompetes = true))
        assertTrue(presence.expectedReporters("jarvis", "me", selfCompetes = false).isEmpty())
    }

    @Test
    fun competesRulesAreSymmetricForKnownWakeWords() {
        assertTrue(PeerPresence.competes("jarvis", "jarvis"))
        assertFalse(PeerPresence.competes("jarvis", "computer"))
        assertFalse(PeerPresence.competes("computer", "jarvis"))
    }
}
