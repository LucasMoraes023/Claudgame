package com.lucasmoraes.claudgame.companion

/** Assinatura bem pequena de um quadro (grade de luminância) para saber se a tela mudou de verdade. */
object FrameSignature {
    const val COLUMNS = 32
    const val ROWS = 18
    const val SIZE = COLUMNS * ROWS

    /** Diferença média absoluta (0..255) entre duas assinaturas; 255 se não forem comparáveis. */
    fun diff(a: ByteArray?, b: ByteArray?): Double {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return 255.0
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return sum.toDouble() / a.size
    }
}
