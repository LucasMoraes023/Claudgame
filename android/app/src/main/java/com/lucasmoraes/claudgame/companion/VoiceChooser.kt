package com.lucasmoraes.claudgame.companion

import java.util.Locale

/** Descrição neutra de uma voz do TTS (evita depender de android.speech.tts.Voice na lógica de escolha). */
data class VoiceInfo(
    val name: String,
    val language: String,
    val country: String,
    val quality: Int,
    val latency: Int,
    val needsNetwork: Boolean,
    val features: Set<String> = emptySet()
)

/**
 * Escolhe a voz da Zuleide entre as vozes pt-BR instaladas: prefere a de maior qualidade (as vozes de rede
 * do Google são as mais naturais) e, quando o nome ou os recursos indicam gênero, a feminina.
 * O Android não expõe gênero de forma padronizada, por isso o jogador também pode trocar de voz no Game Hub.
 */
object VoiceChooser {
    private val female = Regex("(^|[^a-z])(female|feminine|fem|feminina|mulher)([^a-z]|$)")
    private val male = Regex("(^|[^a-z])(male|masculine|masc|masculina|homem)([^a-z]|$)")

    fun brazilian(voices: Collection<VoiceInfo>): List<VoiceInfo> =
        voices.filter {
            it.language.equals("pt", ignoreCase = true) &&
                it.country.equals("BR", ignoreCase = true) &&
                "notInstalled" !in it.features
        }

    fun score(voice: VoiceInfo): Int {
        val descriptor = (voice.name + " " + voice.features.joinToString(" ")).lowercase(Locale.ROOT)
        var score = voice.quality * 10
        if (female.containsMatchIn(descriptor)) score += 2000
        if (male.containsMatchIn(descriptor)) score -= 2000
        if (voice.needsNetwork) score += 30 // vozes de rede costumam ser neurais, mais naturais
        score -= voice.latency / 100
        return score
    }

    /** Melhor primeiro; empates em ordem alfabética para o resultado ser estável. */
    fun ranked(voices: Collection<VoiceInfo>): List<VoiceInfo> =
        brazilian(voices).sortedWith(compareByDescending<VoiceInfo> { score(it) }.thenBy { it.name })

    fun choose(voices: Collection<VoiceInfo>, preferredName: String?): VoiceInfo? {
        val ranked = ranked(voices)
        return ranked.firstOrNull { it.name == preferredName } ?: ranked.firstOrNull()
    }

    /** Próxima voz da lista (volta ao início no fim). */
    fun next(voices: Collection<VoiceInfo>, currentName: String?): VoiceInfo? {
        val ranked = ranked(voices)
        if (ranked.isEmpty()) return null
        val index = ranked.indexOfFirst { it.name == currentName }
        return ranked[(index + 1) % ranked.size]
    }
}
