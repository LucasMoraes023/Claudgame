package com.lucasmoraes.claudgame.ai

class AssistantEngine {
    fun reply(userText: String, trollEnabled: Boolean): String =
        reply(userText, trollEnabled, "")

    fun reply(userText: String, trollEnabled: Boolean, screenContext: String): String {
        val text = userText.trim().lowercase()

        if (text.isBlank()) return "Não consegui ouvir. Fala de novo."

        if (text.contains("quem é você") || text.contains("quem voce e")) {
            return "Eu sou o ClaudGame. Estou acompanhando sua partida e consigo usar o que aparece na tela para te ajudar."
        }

        if (text.contains("como você está") || text.contains("como voce esta")) {
            return if (screenContext.isBlank()) {
                "Estou ligado e acompanhando. Manda ver."
            } else {
                "Estou ligado e já consegui analisar informações da tela."
            }
        }

        if (text.contains("trolagem") || text.contains("troll")) {
            return if (trollEnabled) {
                "Modo trolagem está ligado. Agora aguenta as consequências."
            } else {
                "A trolagem está desligada. Se quiser caos, liga o modo trolagem."
            }
        }

        if (text.contains("o que tem na tela") ||
            text.contains("o que está na tela") ||
            text.contains("o que esta na tela") ||
            text.contains("analisa a tela") ||
            text.contains("analise a tela")) {
            return if (screenContext.isBlank()) {
                "Ainda não consegui identificar texto suficiente na tela. Deixa a partida aparecer e pergunta de novo."
            } else {
                "Olhei a tela. $screenContext"
            }
        }

        if (text.contains("me ajuda") || text.contains("ajuda") ||
            text.contains("o que eu faço") || text.contains("o que faço")) {
            return if (screenContext.isBlank()) {
                "Claro. Estou acompanhando. Me diga o que está acontecendo na partida."
            } else {
                "Claro. Pela tela que consegui analisar, $screenContext. Se quiser, me diga qual é seu objetivo que eu te oriento."
            }
        }

        if (trollEnabled) {
            val trolls = listOf(
                "Entendi. Só não vai colocar a culpa no jogo depois.",
                "Estou ouvindo. Quero ver se essa jogada vai dar bom.",
                "Beleza. Agora mostra serviço, porque falar é fácil."
            )
            return trolls[kotlin.math.abs(userText.hashCode()) % trolls.size]
        }

        return if (screenContext.isBlank()) {
            "Entendi. Você disse: $userText"
        } else {
            "Entendi. Pela tela, $screenContext"
        }
    }
}
