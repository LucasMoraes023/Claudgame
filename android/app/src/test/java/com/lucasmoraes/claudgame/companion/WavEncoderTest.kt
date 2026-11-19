package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {
    @Test
    fun writesAValidPcm16MonoHeader() {
        val pcm = shortArrayOf(1, -2, 300, Short.MAX_VALUE, Short.MIN_VALUE)
        val wav = WavEncoder.encode(pcm, 16_000)
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

        fun ascii(offset: Int) = String(wav, offset, 4, Charsets.US_ASCII)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", ascii(0))
        assertEquals(36 + pcm.size * 2, buffer.getInt(4))
        assertEquals("WAVE", ascii(8))
        assertEquals("fmt ", ascii(12))
        assertEquals(16, buffer.getInt(16))
        assertEquals(1, buffer.getShort(20).toInt()) // PCM
        assertEquals(1, buffer.getShort(22).toInt()) // mono
        assertEquals(16_000, buffer.getInt(24))
        assertEquals(32_000, buffer.getInt(28))
        assertEquals(2, buffer.getShort(32).toInt())
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals("data", ascii(36))
        assertEquals(pcm.size * 2, buffer.getInt(40))
    }

    @Test
    fun samplesAreLittleEndianAndPreserved() {
        val pcm = shortArrayOf(1, -2, 300, Short.MAX_VALUE, Short.MIN_VALUE)
        val buffer = ByteBuffer.wrap(WavEncoder.encode(pcm, 16_000)).order(ByteOrder.LITTLE_ENDIAN)
        for (i in pcm.indices) assertEquals(pcm[i], buffer.getShort(44 + i * 2))
    }

    @Test
    fun emptyAudioStillHasAHeader() {
        assertEquals(44, WavEncoder.encode(ShortArray(0), 16_000).size)
    }
}
