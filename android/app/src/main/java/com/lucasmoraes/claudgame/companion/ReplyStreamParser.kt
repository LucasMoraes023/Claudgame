package com.lucasmoraes.claudgame.companion

/**
 * Lê a resposta da IA enquanto ela chega (streaming) e entrega FRASES prontas para falar, assim a Zuleide
 * começa a falar na primeira frase, sem esperar o texto inteiro.
 *
 * Formato esperado do modelo (ver cloudflare-worker/src/zuleide.js):
 *   <fala da Zuleide, texto corrido>
 *   ###OUVI: <transcrição do que o jogador disse>     (não é falada)
 *   ###CALAR: <minutos>                                (não é falada)
 * ou, quando não há nada a dizer, exatamente [SILENCIO].
 */
class ReplyStreamParser {
    private companion object {
        const val SOFT_SPLIT_MIN = 55   // só quebra frases longas em vírgula depois disto
        const val SOFT_SPLIT_LOOKAHEAD = 40
        val silenceToken = Regex("^\\[\\s*SIL[EÊ]NCIO\\b", RegexOption.IGNORE_CASE)
    }

    private val raw = StringBuilder()
    private val spoken = StringBuilder()
    private var emittedUntil = 0

    var isSilent = false
        private set
    var heard: String? = null
        private set
    var quietMinutes: Int? = null
        private set

    /** Tudo o que já foi entregue para falar (vai para o histórico da conversa). */
    val spokenText: String get() = spoken.toString().trim()

    fun feed(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        raw.append(chunk)
        return drain(final = false)
    }

    fun finish(): List<String> = drain(final = true)

    private fun drain(final: Boolean): List<String> {
        val text = raw.toString()
        val metaStart = text.indexOf("###")
        if (metaStart >= 0) parseMeta(text.substring(metaStart))

        // Uma frase só é entregue ao chegar a pontuação final, então um "#" solto no fim do buffer
        // (começo de "###") nunca chega a ser falado antes de o resto da linha de metadados aparecer.
        val reply = text.substring(0, if (metaStart >= 0) metaStart else text.length)

        if (isSilent) return emptyList()
        val head = reply.trimStart()
        if (head.startsWith("[")) {
            if (silenceToken.containsMatchIn(head)) {
                isSilent = true
                return emptyList()
            }
            if (!final && !head.contains(']')) return emptyList() // ainda pode virar [SILENCIO]
        } else if (final && head.trim().equals("SILENCIO", ignoreCase = true)) {
            isSilent = true
            return emptyList()
        }

        val sentences = ArrayList<String>()
        var cursor = emittedUntil
        while (cursor < reply.length) {
            val end = sentenceEnd(reply, cursor, final) ?: break
            val sentence = SpeechText.clean(reply.substring(cursor, end))
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
                spoken.append(sentence).append(' ')
            }
            cursor = end
        }
        emittedUntil = cursor
        return sentences
    }

    /** Fim (exclusivo) da próxima frase completa a partir de [from], ou null se ainda não chegou. */
    private fun sentenceEnd(text: String, from: Int, final: Boolean): Int? {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return null
        val start = i
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var j = i + 1
                while (j < text.length && (text[j] == '.' || text[j] == '!' || text[j] == '?' || text[j] == '…' ||
                        text[j] == '"' || text[j] == '”' || text[j] == '\'')
                ) j++
                if (j >= text.length) return if (final) j else null // pode vir mais pontuação/texto
                if (text[j].isWhitespace()) {
                    while (j < text.length && text[j].isWhitespace()) j++
                    return j
                }
                i = j
                continue
            }
            if ((c == ',' || c == ';' || c == ':') && i - start >= SOFT_SPLIT_MIN &&
                text.length - (i + 1) >= SOFT_SPLIT_LOOKAHEAD && text[i + 1] == ' '
            ) {
                return i + 2
            }
            i++
        }
        return if (final) text.length else null
    }

    private fun parseMeta(tail: String) {
        for (piece in tail.split("###")) {
            if (piece.isBlank()) continue
            val key = piece.substringBefore(':').trim().uppercase()
            val value = piece.substringAfter(':', "").trim()
            when (key) {
                "OUVI" -> heard = value.ifEmpty { null }
                "CALAR" -> value.takeWhile { it.isDigit() }.toIntOrNull()?.let { quietMinutes = it.coerceIn(0, 240) }
            }
        }
    }
}
