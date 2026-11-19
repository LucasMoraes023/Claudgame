package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OfflineLinesTest {
    private val seeded = Random(42)

    @Test
    fun everyLineIsReadyToBeSpoken() {
        val all = HashSet<String>()
        repeat(400) {
            all += OfflineLines.greeting(true, seeded)
            all += OfflineLines.greeting(false, seeded)
            all += OfflineLines.idle(true, seeded)
            all += OfflineLines.idle(false, seeded)
            all += OfflineLines.error(seeded)
        }
        all += OfflineLines.serverOutdated()
        all += OfflineLines.blocked()
        all += OfflineLines.noisy()
        assertTrue(all.size >= 20)
        for (line in all) {
            assertTrue(line, line.isNotBlank())
            assertEquals("a fala já deve estar limpa: $line", line, SpeechText.clean(line))
            assertTrue(line, line.length < 140)
        }
    }

    @Test
    fun neverRepeatsTheSameLineTwiceInARow() {
        var previous = ""
        repeat(300) {
            val line = OfflineLines.idle(true, seeded)
            assertNotEquals(previous, line)
            previous = line
        }
    }

    @Test
    fun trollAndNormalModesHaveDifferentLines() {
        val normal = (1..200).map { OfflineLines.idle(false, seeded) }.toSet()
        val troll = (1..200).map { OfflineLines.idle(true, seeded) }.toSet()
        assertTrue(normal.intersect(troll).isEmpty())
        assertTrue(troll.size >= 8)
    }

    @Test
    fun namesTheAssistant() {
        assertTrue((1..50).map { OfflineLines.greeting(false, seeded) + OfflineLines.greeting(true, seeded) }.any { it.contains(Persona.NAME) })
        assertFalse(Persona.NAME.isBlank())
    }
}
