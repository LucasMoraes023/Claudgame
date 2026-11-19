package com.lucasmoraes.claudgame.companion

import com.lucasmoraes.claudgame.companion.ConversationMemory.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMemoryTest {
    @Test
    fun keepsOrderAndIgnoresBlankText() {
        val memory = ConversationMemory()
        memory.add(Who.PLAYER, "oi")
        memory.add(Who.ZULEIDE, "   ")
        memory.add(Who.ZULEIDE, "e aí!")
        assertEquals(
            listOf(ConversationMemory.Turn(Who.PLAYER, "oi"), ConversationMemory.Turn(Who.ZULEIDE, "e aí!")),
            memory.snapshot()
        )
    }

    @Test
    fun dropsTheOldestTurnsBeyondTheLimit() {
        val memory = ConversationMemory(maxTurns = 3)
        for (i in 1..5) memory.add(Who.PLAYER, "fala $i")
        assertEquals(listOf("fala 3", "fala 4", "fala 5"), memory.snapshot().map { it.text })
    }

    @Test
    fun limitsTotalSizeAndTurnSize() {
        val memory = ConversationMemory(maxTurns = 50, maxChars = 500)
        for (i in 1..10) memory.add(Who.ZULEIDE, "x".repeat(400))
        val kept = memory.snapshot()
        assertTrue(kept.all { it.text.length <= 240 })
        assertTrue(kept.sumOf { it.text.length } <= 500)
        assertTrue(kept.isNotEmpty())
    }

    @Test
    fun clearEmptiesTheMemory() {
        val memory = ConversationMemory()
        memory.add(Who.PLAYER, "oi")
        memory.clear()
        assertTrue(memory.snapshot().isEmpty())
    }
}
