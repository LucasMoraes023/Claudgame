package com.lucasmoraes.claudgame.companion

/** Memória curta da conversa (últimas falas), enviada ao modelo para a conversa ter continuidade. */
class ConversationMemory(private val maxTurns: Int = 20, private val maxChars: Int = 4000) {
    enum class Who { PLAYER, ZAYRO }

    data class Turn(val who: Who, val text: String)

    private val turns = ArrayDeque<Turn>()
    private val spaces = Regex("\\s+")

    fun add(who: Who, text: String) {
        val clean = text.replace(spaces, " ").trim().take(350)
        if (clean.isEmpty()) return
        turns.addLast(Turn(who, clean))
        while (turns.size > maxTurns || totalChars() > maxChars) turns.removeFirst()
    }

    fun snapshot(): List<Turn> = turns.toList()

    fun clear() = turns.clear()

    private fun totalChars(): Int = turns.sumOf { it.text.length }
}
