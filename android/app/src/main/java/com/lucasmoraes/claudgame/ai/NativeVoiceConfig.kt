package com.lucasmoraes.claudgame.ai

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Configura o TTS nativo do Android em português brasileiro.
 * Android não expõe um campo padronizado de gênero para Voice; portanto,
 * só classificamos como masculina uma voz cujo nome/features declarem male.
 */
object NativeVoiceConfig {
    private val maleMarker = Regex("(^|[^a-z])(male|masculine|masc)([^a-z]|$)")
    private val femaleMarker = Regex("(^|[^a-z])(female|feminine|fem)([^a-z]|$)")

    fun configure(tts: TextToSpeech): String {
        val locale = Locale("pt", "BR")
        val languageResult = tts.setLanguage(locale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            android.util.Log.e("ClaudGameVoice", "O mecanismo TTS não oferece pt-BR")
            return "Português do Brasil indisponível no mecanismo de voz"
        }

        val brazilianVoices = tts.voices.orEmpty().filter {
            it.locale.language.equals("pt", ignoreCase = true) &&
                it.locale.country.equals("BR", ignoreCase = true)
        }
        val maleVoices = brazilianVoices.filter { voice ->
            val descriptor = (voice.name + " " + voice.features.joinToString(" "))
                .lowercase(Locale.ROOT)
            maleMarker.containsMatchIn(descriptor) &&
                !femaleMarker.containsMatchIn(descriptor)
        }
        val selected = maleVoices.maxWithOrNull(
            compareBy<Voice> { it.quality }
                .thenBy { if (it.isNetworkConnectionRequired) 0 else 1 }
                .thenBy { -it.latency }
        )

        if (selected != null) {
            tts.voice = selected
            android.util.Log.i("ClaudGameVoice", "Voz masculina pt-BR selecionada: ${selected.name}")
        } else {
            android.util.Log.w(
                "ClaudGameVoice",
                "Nenhuma voz masculina pt-BR identificável. Vozes disponíveis: " +
                    brazilianVoices.joinToString { it.name }
            )
        }

        // Ritmo ligeiramente mais calmo e tom natural, sem falsear gênero via pitch.
        tts.setSpeechRate(0.94f)
        tts.setPitch(0.98f)
        return selected?.name ?: "Nenhuma voz masculina pt-BR identificada no mecanismo"
    }
}
