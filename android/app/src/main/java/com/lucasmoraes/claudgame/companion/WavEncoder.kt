package com.lucasmoraes.claudgame.companion

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16 bits mono -> arquivo WAV (formato aceito pelo Gemini: audio/wav). */
object WavEncoder {
    fun encode(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // tamanho do bloco fmt
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // bytes por segundo
        buffer.putShort(2) // bytes por amostra*canais
        buffer.putShort(16) // bits por amostra
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        for (sample in pcm) buffer.putShort(sample)
        return buffer.array()
    }
}
