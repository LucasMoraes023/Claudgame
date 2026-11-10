package com.lucasmoraes.claudgame.ai

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Prefere uma voz brasileira masculina quando o mecanismo TTS do aparelho
 * identifica isso no nome da voz. Se não houver, escolhe a voz pt-BR local
 * de melhor qualidade disponível, sem instalar nem contratar outro serviço.
 */
object NativeVoiceConfig {
    fun configure(tts: TextToSpeech): String {
        val locale = Locale("pt", "BR")
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "Português do Brasil indisponível no mecanismo de voz"
        }

        val brazilianVoices = tts.voices.orEmpty().filter {
            it.locale.language.equals("pt", ignoreCase = true) &&
                it.locale.country.equals("BR", ignoreCase = true)
        }
        val maleVoices = brazilianVoices.filter { it.name.contains("male", ignoreCase = true) }
        val pool = if (maleVoices.isNotEmpty()) maleVoices else brazilianVoices
        val selected = pool.maxWithOrNull(
            compareBy<Voice> { if (it.isNetworkConnectionRequired) 0 else 1 }
                .thenBy { it.quality }
                .thenBy { -it.latency }
        )

        if (selected != null) {
            tts.voice = selected
        }

        // Ajustes discretos para uma fala mais grave e menos acelerada.
        tts.setSpeechRate(0.96f)
        tts.setPitch(0.94f)
        return selected?.name ?: "Voz padrão do Android"
    }
}
