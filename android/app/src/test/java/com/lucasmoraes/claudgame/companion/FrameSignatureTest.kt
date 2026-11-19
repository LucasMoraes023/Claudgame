package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameSignatureTest {
    private fun sig(value: Int) = ByteArray(FrameSignature.SIZE) { value.toByte() }

    @Test
    fun identicalFramesHaveZeroDifference() {
        assertEquals(0.0, FrameSignature.diff(sig(80), sig(80)), 0.0)
    }

    @Test
    fun differenceIsTheMeanAbsoluteLuminanceChange() {
        assertEquals(50.0, FrameSignature.diff(sig(10), sig(60)), 0.0001)
        assertEquals(50.0, FrameSignature.diff(sig(60), sig(10)), 0.0001)
    }

    @Test
    fun handlesBytesAboveSevenBitsAsUnsigned() {
        assertEquals(255.0, FrameSignature.diff(sig(0), sig(255)), 0.0001)
    }

    @Test
    fun notComparableMeansMaximumDifference() {
        assertEquals(255.0, FrameSignature.diff(null, sig(1)), 0.0)
        assertEquals(255.0, FrameSignature.diff(sig(1), null), 0.0)
        assertEquals(255.0, FrameSignature.diff(ByteArray(3), sig(1)), 0.0)
        assertEquals(255.0, FrameSignature.diff(ByteArray(0), ByteArray(0)), 0.0)
    }
}
