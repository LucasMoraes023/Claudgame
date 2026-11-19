package com.lucasmoraes.claudgame.companion

/**
 * Prepara o texto da IA para o sintetizador de voz: tira emojis, markdown e links (que o TTS lê em voz alta
 * como "asterisco asterisco") e troca "kkk" por uma risada pronunciável.
 */
object SpeechText {
    private val url = Regex("https?://\\S+")
    private val symbols = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2190}-\\x{21FF}\\x{FE0F}\\x{200D}\\x{20E3}]"
    )
    private val markdown = Regex("[*_`~#>|]+")
    private val laughK = Regex("\\b[kK]{3,}\\b")
    private val laughHa = Regex("\\b(?:[hH][aA]){2,}[hH]?\\b")
    private val laughRs = Regex("\\b[rR][sS]([rR][sS])+\\b")
    private val spaces = Regex("\\s+")

    fun clean(raw: String): String =
        raw.replace(url, " ")
            .replace(symbols, "")
            .replace(markdown, " ")
            .replace(laughK, "ha ha ha")
            .replace(laughHa, "ha ha ha")
            .replace(laughRs, "ha ha")
            .replace(spaces, " ")
            .trim()
}
