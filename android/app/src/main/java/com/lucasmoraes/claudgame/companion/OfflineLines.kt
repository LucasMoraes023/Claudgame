package com.lucasmoraes.claudgame.companion

import kotlin.random.Random

/**
 * Falas locais da ZAYRO para quando a IA online não está disponível (sem internet, cota, servidor antigo)
 * e para a saudação inicial, que sai na hora, sem esperar rede.
 */
object OfflineLines {
    private val greetingNormal = listOf(
        "Oi, aqui é a ZAYRO! Pode falar comigo quando quiser.",
        "ZAYRO na área! Eu comento, você joga. Combinado?",
        "Cheguei! Tô de olho na partida, qualquer coisa é só falar."
    )
    private val greetingTroll = listOf(
        "A ZAYRO chegou, e eu vim pra pegar no seu pé!",
        "Prepara, que a ZAYRO vai te zoar a partida inteira.",
        "Opa, chegou a sua pior companheira de jogo! Boa sorte."
    )
    private val idleNormal = listOf(
        "Tô aqui de olho na partida, viu? Qualquer coisa me chama.",
        "Respira e foca, que a gente vira esse jogo.",
        "Se precisar de uma dica, é só falar comigo.",
        "Bora, campeão! Eu acredito em você.",
        "Tá indo bem, hein? Continua assim.",
        "Não esquece de beber uma água, tá? Jogador hidratado joga melhor."
    )
    private val idleTroll = listOf(
        "Tá jogando ou tá meditando? Porque tá parado demais.",
        "Isso aí foi estratégia ou foi susto mesmo?",
        "Eu já vi tartaruga jogar mais rápido que você.",
        "Se jogar fosse crime, você já estava condenado.",
        "Tô de olho em você, viu? Tudo anotado aqui.",
        "Quer uma dica? Joga melhor. De nada!",
        "Ih, e agora? Vai chorar ou vai tentar de novo?",
        "Eita, que lindo de ver. Quer dizer, de rir.",
        "Já até preparei o velório do seu personagem.",
        "Se eu fosse você eu me aposentava. Mas continua aí, que eu me divirto."
    )
    private val errors = listOf(
        "Eita, a internet me deixou na mão. Fala de novo daqui a pouco.",
        "Perdi a conexão aqui, já já eu volto.",
        "Não consegui te responder agora, tenta de novo em instantes."
    )
    private const val OUTDATED = "Preciso que atualize o meu servidor, viu? Aí eu falo direito."
    private const val BLOCKED = "Essa eu não posso comentar, vamos mudar de assunto."
    private const val NOISY = "Tá barulhento demais aqui, viu? Usa um fone, ou toca no meu nome pra falar comigo."

    private val lastIndex = HashMap<List<String>, Int>()

    fun greeting(troll: Boolean, random: Random = Random.Default): String =
        pick(if (troll) greetingTroll else greetingNormal, random)

    fun idle(troll: Boolean, random: Random = Random.Default): String =
        pick(if (troll) idleTroll else idleNormal, random)

    fun error(random: Random = Random.Default): String = pick(errors, random)

    fun serverOutdated(): String = OUTDATED

    fun blocked(): String = BLOCKED

    fun noisy(): String = NOISY

    /** Sorteia sem repetir a fala anterior da mesma lista. */
    @Synchronized
    private fun pick(lines: List<String>, random: Random): String {
        var index = random.nextInt(lines.size)
        val previous = lastIndex[lines]
        if (lines.size > 1 && index == previous) index = (index + 1) % lines.size
        lastIndex[lines] = index
        return lines[index]
    }
}
