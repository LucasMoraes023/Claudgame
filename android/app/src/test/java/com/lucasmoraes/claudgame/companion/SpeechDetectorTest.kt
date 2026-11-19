package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeechDetectorTest {
    private fun tone(amplitude: Int, size: Int = 320): ShortArray =
        ShortArray(size) { i -> (amplitude * sin(2 * PI * 440.0 * i / 16000.0)).toInt().toShort() }

    private fun silence(size: Int = 320): ShortArray = ShortArray(size)

    private class Run(val events: List<SpeechDetector.Event>) {
        val started get() = events.count { it === SpeechDetector.Event.SpeechStarted }
        val utterances get() = events.filterIsInstance<SpeechDetector.Event.Utterance>()
        val discarded get() = events.count { it === SpeechDetector.Event.Discarded }
    }

    private fun feed(detector: SpeechDetector, vararg segments: Pair<ShortArray, Int>): Run {
        val events = ArrayList<SpeechDetector.Event>()
        for ((frame, count) in segments) repeat(count) { events.add(detector.process(frame)) }
        return Run(events.filter { it !== SpeechDetector.Event.None })
    }

    @Test
    fun silenceNeverTriggers() {
        val detector = SpeechDetector()
        val run = feed(detector, silence() to 500)
        assertTrue(run.events.isEmpty())
        assertTrue(detector.noiseFloor < 100)
    }

    @Test
    fun cutsOneSpokenSentenceWithPreRollAndTail() {
        val detector = SpeechDetector()
        val run = feed(detector, silence() to 20, tone(3000) to 50, silence() to 40)
        assertEquals(1, run.started)
        assertEquals(1, run.utterances.size)
        // 10 quadros de pré-roll silencioso + 50 de voz + 10 de cauda = 70 quadros de 320 amostras.
        assertEquals(70 * 320, run.utterances[0].pcm.size)
    }

    @Test
    fun clicksAreIgnored() {
        val run = feed(SpeechDetector(), silence() to 30, tone(5000) to 3, silence() to 60)
        assertTrue(run.events.isEmpty())
    }

    @Test
    fun veryShortBurstsAreDiscarded() {
        val run = feed(SpeechDetector(), silence() to 30, tone(5000) to 6, silence() to 60)
        assertEquals(1, run.started)
        assertEquals(1, run.discarded)
        assertTrue(run.utterances.isEmpty())
    }

    @Test
    fun continuousLoudNoiseIsDiscardedAndRaisesTheThreshold() {
        val detector = SpeechDetector()
        val run = feed(detector, tone(3000) to 700)
        assertEquals(1, run.discarded)
        assertTrue(run.utterances.isEmpty())
        assertTrue("noiseFloor=${detector.noiseFloor}", detector.noiseFloor > 1500)
    }

    @Test
    fun recoversAfterTheNoiseStops() {
        val detector = SpeechDetector()
        feed(detector, tone(3000) to 700)
        val run = feed(detector, silence() to 120, tone(3000) to 50, silence() to 40)
        assertEquals(1, run.utterances.size)
    }

    @Test
    fun longSpeechWithPausesIsSentNotDiscarded() {
        val detector = SpeechDetector()
        val segments = ArrayList<Pair<ShortArray, Int>>()
        repeat(14) {
            segments.add(tone(3000) to 40)
            segments.add(silence() to 10)
        }
        val run = feed(detector, *segments.toTypedArray())
        assertEquals(0, run.discarded)
        assertFalse(run.utterances.isEmpty())
    }

    @Test
    fun tapToTalkCapturesEvenIfTheUserStartsLate() {
        val detector = SpeechDetector()
        feed(detector, silence() to 30)
        detector.forceStart()
        val run = feed(detector, silence() to 50, tone(3000) to 50, silence() to 40)
        assertEquals(1, run.started)
        assertEquals(1, run.utterances.size)
    }

    @Test
    fun tapToTalkGivesUpAfterThreeSecondsOfSilence() {
        val detector = SpeechDetector()
        detector.forceStart()
        val run = feed(detector, silence() to 200)
        assertEquals(1, run.started)
        assertEquals(1, run.discarded)
    }

    @Test
    fun resetClearsAnUtteranceInProgress() {
        val detector = SpeechDetector()
        feed(detector, tone(3000) to 20)
        detector.reset()
        val run = feed(detector, silence() to 100)
        assertTrue(run.events.isEmpty())
    }
}
