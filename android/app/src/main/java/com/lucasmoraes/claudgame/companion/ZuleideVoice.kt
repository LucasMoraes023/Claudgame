package com.lucasmoraes.claudgame.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Voz da Zuleide (TextToSpeech nativo). Melhorias para soar mais natural e responder mais rápido:
 *  - usa o motor do Google (vozes pt-BR neurais) quando instalado; senão, o motor padrão do aparelho;
 *  - escolhe a voz pt-BR de maior qualidade (ou a que o jogador escolheu no Game Hub);
 *  - fala frase por frase (fila), então a primeira frase sai antes do resto da resposta terminar;
 *  - pede foco de áudio "ducking": o jogo abaixa o volume enquanto ela fala.
 * Todos os callbacks rodam na thread principal.
 */
class ZuleideVoice(
    context: Context,
    private val preferredVoice: String?,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady(voiceName: String?, portugueseAvailable: Boolean)
        fun onUnavailable()
        fun onSpeechStarted()
        fun onSpeechFinished()
    }

    private companion object {
        const val TAG = "ZuleideVoice"
        const val GOOGLE_TTS = "com.google.android.tts"
        const val SPEECH_RATE = 1.08f
        const val PITCH = 1.04f
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val brazil: Locale = Locale.Builder().setLanguage("pt").setRegion("BR").build()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var destroyed = false
    private var counter = 0
    private var focusRequest: AudioFocusRequest? = null
    private val waiting = ArrayList<Pair<String, Boolean>>()
    private val active = ConcurrentHashMap.newKeySet<String>()

    val isSpeaking: Boolean get() = active.isNotEmpty()

    init {
        create(GOOGLE_TTS, fallbackToDefault = true)
    }

    private fun create(enginePackage: String?, fallbackToDefault: Boolean) {
        try {
            tts = TextToSpeech(appContext, { status ->
                mainHandler.post { onInit(status, enginePackage, fallbackToDefault) }
            }, enginePackage)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao criar o TTS ($enginePackage)", error)
            if (enginePackage != null && fallbackToDefault) create(null, false) else unavailable()
        }
    }

    private fun onInit(status: Int, enginePackage: String?, fallbackToDefault: Boolean) {
        if (destroyed) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            try {
                engine?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
            if (enginePackage != null && fallbackToDefault) create(null, false) else unavailable()
            return
        }
        configure(engine, enginePackage)
    }

    private fun unavailable() {
        if (!destroyed) callbacks.onUnavailable()
    }

    private fun configure(engine: TextToSpeech, enginePackage: String?) {
        try {
            engine.setAudioAttributes(audioAttributes)
        } catch (error: Exception) {
            Log.w(TAG, "Atributos de áudio recusados", error)
        }
        val portuguese = try {
            engine.setLanguage(brazil) >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            false
        }
        var chosen: VoiceInfo? = null
        try {
            val voices = engine.voices.orEmpty()
            chosen = VoiceChooser.choose(voices.map { toInfo(it) }, preferredVoice)
            val real = voices.firstOrNull { it.name == chosen?.name }
            if (real != null) engine.voice = real
        } catch (error: Exception) {
            // Alguns motores lançam exceção ao listar vozes; a voz padrão do sistema continua valendo.
            Log.w(TAG, "Não foi possível listar/selecionar vozes", error)
        }
        try {
            engine.setSpeechRate(SPEECH_RATE)
            engine.setPitch(PITCH)
        } catch (_: Exception) {
        }
        engine.setOnUtteranceProgressListener(progress)
        ready = true
        Log.i(TAG, "TTS pronto: motor=${enginePackage ?: "padrão"} voz=${chosen?.name ?: "padrão"} pt-BR=$portuguese")
        callbacks.onReady(chosen?.name, portuguese)
        val queued = ArrayList(waiting)
        waiting.clear()
        for ((text, flush) in queued) speak(text, flush)
    }

    private fun toInfo(voice: Voice) = VoiceInfo(
        name = voice.name,
        language = voice.locale.language,
        country = voice.locale.country,
        quality = voice.quality,
        latency = voice.latency,
        needsNetwork = voice.isNetworkConnectionRequired,
        features = voice.features ?: emptySet()
    )

    /** Vozes pt-BR instaladas, da melhor para a pior (para o seletor do Game Hub). */
    fun brazilianVoices(): List<VoiceInfo> {
        val engine = tts
        if (!ready || engine == null) return emptyList()
        return try {
            VoiceChooser.ranked(engine.voices.orEmpty().map { toInfo(it) })
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun useVoice(name: String): Boolean {
        val engine = tts
        if (!ready || engine == null) return false
        return try {
            val real = engine.voices.orEmpty().firstOrNull { it.name == name } ?: return false
            engine.voice = real
            true
        } catch (_: Exception) {
            false
        }
    }

    fun speak(text: String, flush: Boolean) {
        if (destroyed || text.isBlank()) return
        val engine = tts
        if (!ready || engine == null) {
            waiting.add(text to flush)
            return
        }
        val id = "z" + counter++
        if (flush) active.clear()
        val startsBurst = active.isEmpty()
        active.add(id)
        val result = try {
            engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao falar", error)
            TextToSpeech.ERROR
        }
        if (result != TextToSpeech.SUCCESS) {
            active.remove(id)
            if (active.isEmpty()) {
                abandonFocus()
                callbacks.onSpeechFinished()
            }
            return
        }
        if (startsBurst) {
            requestFocus()
            callbacks.onSpeechStarted()
        }
    }

    /** Interrompe a fala na hora (o jogador começou a falar, tocou no painel etc.). */
    fun stop() {
        waiting.clear()
        active.clear()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        abandonFocus()
    }

    fun shutdown() {
        destroyed = true
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = finished(utteranceId)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = finished(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
    }

    /** Chamado em thread do TTS: só dispara o fim da fala quando a fila inteira acabou. */
    private fun finished(utteranceId: String?) {
        if (utteranceId == null) return
        if (active.remove(utteranceId) && active.isEmpty()) {
            mainHandler.post {
                if (!destroyed && active.isEmpty()) {
                    abandonFocus()
                    callbacks.onSpeechFinished()
                }
            }
        }
    }

    private fun requestFocus() {
        try {
            val manager = appContext.getSystemService(AudioManager::class.java) ?: return
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            manager.requestAudioFocus(request)
            focusRequest = request
        } catch (error: Exception) {
            Log.w(TAG, "Foco de áudio indisponível", error)
        }
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        focusRequest = null
        try {
            appContext.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        } catch (_: Exception) {
        }
    }
}
