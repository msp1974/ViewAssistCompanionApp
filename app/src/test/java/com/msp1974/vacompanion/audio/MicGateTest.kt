package com.msp1974.vacompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MicGateTest {

    private var now = 0L
    private val gate = MicGate { now }

    private fun frameAt(db: Float): ShortArray {
        val amplitude = (10f.pow(db / 20f) * 32768f).toInt().coerceAtMost(32767)
        return ShortArray(512) { i -> if (i % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
    }

    @Test
    fun measuresTheLevelOfAFrame() {
        assertEquals(-40f, MicGate.frameDbfs(frameAt(-40f)), 0.1f)
        assertEquals(-100f, MicGate.frameDbfs(ShortArray(0)), 0.001f)
    }

    @Test
    fun atTheLowestLevelTheGateIsOffAndEverythingGetsThrough() {
        assertTrue(gate.isOpen(frameAt(-95f), MicGate.OFF_DBFS))
        assertTrue(gate.isOpen(ShortArray(512), MicGate.OFF_DBFS))
    }

    @Test
    fun quietSoundIsHeldBackUntilItGetsOverTheLevel() {
        assertFalse(gate.isOpen(frameAt(-50f), -40))
        assertFalse(gate.isOpen(frameAt(-45f), -40))
        assertTrue(gate.isOpen(frameAt(-35f), -40))
    }

    @Test
    fun aFrameJustOverTheLevelOpensTheGate() {
        assertTrue(gate.isOpen(frameAt(-39.5f), -40))
        assertFalse(MicGate().isOpen(frameAt(-40.5f), -40))
    }

    @Test
    fun staysOpenThroughQuietPatchesThenCloses() {
        assertTrue(gate.isOpen(frameAt(-30f), -40))
        now += MicGate.HOLD_MS - 1
        assertTrue("still within the hold", gate.isOpen(frameAt(-60f), -40))
        now += 2
        assertFalse("hold over", gate.isOpen(frameAt(-60f), -40))
    }

    @Test
    fun loudSoundExtendsTheHold() {
        assertTrue(gate.isOpen(frameAt(-30f), -40))
        now += MicGate.HOLD_MS - 100
        assertTrue(gate.isOpen(frameAt(-30f), -40))
        now += MicGate.HOLD_MS - 100
        assertTrue(gate.isOpen(frameAt(-60f), -40))
    }

    @Test
    fun aHigherLevelBlocksWhatALowerOneLetsThrough() {
        assertTrue(MicGate().isOpen(frameAt(-45f), -50))
        assertFalse(MicGate().isOpen(frameAt(-45f), -40))
    }
}
