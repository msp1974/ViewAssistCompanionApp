package com.msp1974.vacompanion.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioDSPTest {

    private val audioDSP = AudioDSP()

    @Test
    fun `openWakeWord input preserves PCM16 sample scale`() {
        val pcm = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)

        val actual = audioDSP.openWakeWordInput(pcm)

        assertArrayEquals(
            floatArrayOf(-32768f, -1f, 0f, 1f, 32767f),
            actual,
            0f,
        )
    }

    @Test
    fun `normalised audio remains available for level and other consumers`() {
        val pcm = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)

        val actual = audioDSP.normaliseAudioBuffer(pcm)

        assertArrayEquals(
            floatArrayOf(-1f, 0f, 32767f / 32768f),
            actual,
            0f,
        )
    }

    @Test
    fun `PCM16 wire serialisation is bit exact little endian`() {
        val pcm = shortArrayOf(Short.MIN_VALUE, -2, -1, 0, 1, 256, Short.MAX_VALUE)

        val actual = audioDSP.shortArrayToByteBuffer(pcm)

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x80.toByte(),
                0xFE.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0x00, 0x00,
                0x01, 0x00,
                0x00, 0x01,
                0xFF.toByte(), 0x7F,
            ),
            actual,
        )
    }
}
