package com.lucasmoraes.claudgame.ai

/**
 * Transforma o texto reconhecido na tela em um contexto curto para a IA, destacando eventos de
 * jogo (derrota, vitória, carregamento, menu, objetivo). É lógica pura, coberta por testes unitários.
 */
object ScreenContextBuilder {
    private const val MAX_VISIBLE_TEXT = 220
    private val whitespace = Regex("\\s+")

    fun build(rawText: String): String {
        val text = rawText.replace(whitespace, " ").trim()
        if (text.isEmpty()) return ""

        val lower = text.lowercase()
        val events = mutableListOf<String>()
        if (containsAny(lower, "game over", "derrota", "derrotado", "you died", "morreu")) {
            events += "parece que a partida terminou em derrota"
        }
        if (containsAny(lower, "victory", "vitória", "vitoria", "winner", "venceu", "booyah")) {
            events += "parece que houve vitória"
        }
        if (containsAny(lower, "loading", "carregando")) {
            events += "a tela parece estar carregando"
        }
        if (containsAny(lower, "pause", "pausado", "menu")) {
            events += "o jogo parece estar em um menu ou pausa"
        }
        if (containsAny(lower, "objective", "objetivo", "mission", "missão", "missao", "quest")) {
            events += "há indicação de objetivo ou missão na tela"
        }

        val visible = text.take(MAX_VISIBLE_TEXT)
        return if (events.isEmpty()) {
            "Texto visível na tela: $visible"
        } else {
            events.joinToString("; ") + ". Texto visível: $visible"
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(it) }
}
