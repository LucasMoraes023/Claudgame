package com.lucasmoraes.claudgame.ai

class AssistantEngine {
    fun reply(userText: String, trollEnabled: Boolean): String {
        val text = userText.trim().lowercase()

        if (text.isBlank()) return "Não consegui ouvir. Fala de novo."

        if (text.contains("quem é você") || text.contains("quem voce e")) {
            return "Eu sou o ClaudGame. Estou aqui para acompanhar sua partida."
        }

        if (text.contains("como você está") || text.contains("como voce esta")) {
            return "Estou ligado e acompanhando. Manda ver."
        }

        if (text.contains("trolagem") || text.contains("troll")) {
            return if (trollEnabled) {
                "Modo trolagem está ligado. Agora aguenta as consequências."
            } else {
                "A trolagem está desligada. Se quiser caos, liga o modo trolagem."
            }
        }

        if (text.contains("me ajuda") || text.contains("ajuda")) {
            return "Claro. Me diga o que está acontecendo na partida e eu tento te orientar."
        }

        if (trollEnabled) {
            val trolls = listOf(
                "Entendi. Só não vai colocar a culpa no jogo depois.",
                "Estou ouvindo. Quero ver se essa jogada vai dar bom.",
                "Beleza. Agora mostra serviço, porque falar é fácil."
            )
            return trolls[kotlin.math.abs(userText.hashCode()) % trolls.size]
        }

        return "Entendi. Você disse: $userText"
    }
}
