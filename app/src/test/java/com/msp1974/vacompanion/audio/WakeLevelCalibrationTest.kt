package com.msp1974.vacompanion.audio

import com.msp1974.vacompanion.audio.WakeLevelCalibration.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLevelCalibrationTest {

    private val calibration = WakeLevelCalibration()
    private val needed = WakeLevelCalibration.READINGS_NEEDED

    // ---- collecting

    @Test
    fun ignoresReadingsUntilStarted() {
        calibration.addReading(-40f)
        assertEquals(Phase.IDLE, calibration.state.value.phase)
        assertTrue(calibration.state.value.readings.isEmpty())
        assertFalse(calibration.isActive)
    }

    @Test
    fun collectsReadingsOnceStarted() {
        calibration.start()
        calibration.addReading(-40f)
        calibration.addReading(-41f)
        assertTrue(calibration.isActive)
        assertEquals(listOf(-40f, -41f), calibration.state.value.readings)
    }

    @Test
    fun stopsByItselfAfterTheLastReadingAndKeepsThem() {
        calibration.start()
        repeat(needed) { calibration.addReading(-40f - it) }
        assertEquals(Phase.DONE, calibration.state.value.phase)
        assertFalse("collecting should have stopped", calibration.isActive)
        assertEquals(needed, calibration.state.value.readings.size)
        calibration.addReading(-10f) // anything heard afterwards is not added
        assertEquals(needed, calibration.state.value.readings.size)
    }

    @Test
    fun thereIsNoSummaryUntilItIsDone() {
        calibration.start()
        repeat(needed - 1) { calibration.addReading(-40f) }
        assertNull(calibration.summary())
        calibration.addReading(-40f)
        assertEquals(-40f, calibration.summary()!!.medianDb, 0.001f)
    }

    @Test
    fun cancelForgetsEverything() {
        calibration.start()
        calibration.addReading(-40f)
        calibration.cancel()
        assertEquals(Phase.IDLE, calibration.state.value.phase)
        assertFalse(calibration.isActive)
        assertTrue(calibration.state.value.readings.isEmpty())
        assertNull(calibration.summary())
    }

    @Test
    fun startingAgainDiscardsEarlierReadings() {
        calibration.start()
        repeat(needed) { calibration.addReading(-40f) }
        calibration.start()
        assertTrue(calibration.isActive)
        assertTrue(calibration.state.value.readings.isEmpty())
    }

    // ---- the calibration offset

    @Test
    fun theOffsetBringsTheMedianToTheReferenceLevel() {
        val summary = WakeLevelCalibration.summarise(listOf(-41f, -40.2f, -42.5f, -39.8f, -41.3f))
        assertEquals(-41f, summary.medianDb, 0.001f)
        assertEquals(WakeLevelCalibration.REFERENCE_DBFS - -41f, summary.offsetDb, 0.001f)
    }

    @Test
    fun theMedianIgnoresOneWildReading() {
        // A cough or a door in one reading must not drag the calibration.
        assertEquals(-41f, WakeLevelCalibration.summarise(listOf(-41f, -40f, -42f, -41f, -15f)).medianDb, 0.001f)
    }

    @Test
    fun anEvenNumberOfReadingsAveragesTheMiddlePair() {
        assertEquals(-41f, WakeLevelCalibration.summarise(listOf(-44f, -40f, -42f, -38f)).medianDb, 0.001f)
    }

    @Test
    fun spreadIsTheGapBetweenTheQuietestAndLoudest() {
        assertEquals(6f, WakeLevelCalibration.summarise(listOf(-44f, -40f, -42f, -38f, -41f)).spreadDb, 0.001f)
    }

    @Test
    fun steadyReadingsAreConsistentAndWideOnesAreNot() {
        assertTrue(WakeLevelCalibration.summarise(listOf(-41f, -40f, -42f, -41f, -40.5f)).consistent)
        assertTrue(WakeLevelCalibration.summarise(listOf(-44f, -38f)).consistent) // exactly 6 dB
        assertFalse(WakeLevelCalibration.summarise(listOf(-47f, -38f)).consistent)
    }

    @Test
    fun theOffsetIsLimitedSoABadReadingCannotThrowADeviceOff() {
        assertEquals(WakeLevelCalibration.MAX_OFFSET_DB, WakeLevelCalibration.summarise(listOf(-95f)).offsetDb, 0.001f)
    }

    @Test
    fun aVeryLoudMicrophoneGetsANegativeOffset() {
        assertEquals(-27f, WakeLevelCalibration.summarise(listOf(-3f)).offsetDb, 0.001f)
    }

    @Test
    fun twoMicrophonesOfVeryDifferentSensitivityReadTheSameAfterCalibration() {
        // The same voice at the same distance reads 14 dB apart on the two microphones.
        val sensitive = WakeLevelCalibration.summarise(listOf(-28f, -27f, -29f, -28f, -28.5f))
        val dull = WakeLevelCalibration.summarise(listOf(-42f, -41f, -43f, -42f, -42.5f))
        assertEquals(
            MicLevelMonitor.calibrated(-28f, sensitive.offsetDb)!!,
            MicLevelMonitor.calibrated(-42f, dull.offsetDb)!!,
            0.5f
        )
    }

    @Test
    fun calibratedAddsTheOffsetToTheRawLevel() {
        assertEquals(-31f, MicLevelMonitor.calibrated(-41f, 10f)!!, 0.001f)
        assertEquals(-45f, MicLevelMonitor.calibrated(-41f, -4f)!!, 0.001f)
    }

    @Test
    fun anUnknownLevelStaysUnknownWhateverTheOffset() {
        assertNull(MicLevelMonitor.calibrated(null, 10f))
    }
}
