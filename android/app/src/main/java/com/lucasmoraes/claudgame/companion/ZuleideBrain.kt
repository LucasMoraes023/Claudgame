package com.lucasmoraes.claudgame.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lucasmoraes.claudgame.ClaudGamePrefs
import com.lucasmoraes.claudgame.capture.CaptureBridge
import kotlin.random.Random

/**
 * Cérebro da Zuleide. Junta as peças e decide o que fazer a cada momento:
 *  - ESCUTA automática (sem apertar nada): recorta cada fala do jogador e manda o áudio + a tela ao Gemini;
 *  - FALA espontânea: comenta a partida por conta própria (mais e mais zoeira no modo troll);
 *  - RESPOSTA em streaming: começa a falar na primeira frase, sem esperar o texto inteiro;
 *  - nunca fala por cima de si mesma: o microfone é pausado enquanto ela fala ou pensa.
 * Tudo roda na thread principal; os componentes de rede, áudio e TTS entregam seus resultados nela.
 */
class ZuleideBrain(
    private val context: Context,
    private val backendUrl: String,
    private val ui: Ui
) {
    interface Ui {
        fun onState(state: State)

        /** Aviso curto e temporário (ex.: "sem rede"). */
        fun onFlash(text: String)
    }

    enum class State { STARTING, LISTENING, HEARING, THINKING, SPEAKING, MUTED, NO_MIC }

    private companion object {
        const val TAG = "ZuleideBrain"
        const val TURN_TIMEOUT_MS = 30_000L
        const val RESUME_DELAY_MS = 450L
        const val SPEECH_TIMEOUT_MS = 45_000L
        const val NOISY_SILENCES_LIMIT = 3
        const val NOISY_PAUSE_MS = 30_000L
        const val STATIC_DIFF_THRESHOLD = 3.0
        const val ERROR_SPEECH_GAP_MS = 20_000L
        const val OFFLINE_QUIP_GAP_MS = 60_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val memory = ConversationMemory()
    private val client = ZuleideClient(backendUrl)
    private var voice: ZuleideVoice? = null
    private var listener: VoiceListener? = null
    private var oneShotListener: VoiceListener? = null

    private var state = State.STARTING
    private var started = false
    private var destroyed = false
    private var greeted = false
    private var hearing = false

    private var call: ZuleideClient.Call? = null
    private var currentKind: String? = null
    private var turnId = 0
    private var sentenceCount = 0

    private var lastActivityAt = 0L
    private var lastPlayerSpeechAt = 0L
    private var quietUntil = 0L
    private var backoffUntil = 0L
    private var serverOutdated = false
    private var lastErrorSpeechAt = 0L

    // Freio para ambiente barulhento: o jogo no alto-falante vira "fala" para o detector, e cada uma
    // custaria uma chamada ao Gemini. Depois de 3 "[SILENCIO]" seguidos a escuta automática pausa um tempo.
    private var silentTurnsInARow = 0
    private var autoListenPausedUntil = 0L
    private var forcedPending = false
    private var lastOfflineQuipAt = 0L

    private var lastSignature: ByteArray? = null
    private var screenStaticSince = 0L
    private var commentedOnThisScreen = false

    private val proactiveRunnable = Runnable { onProactiveTick() }
    private val resumeRunnable = Runnable { resumeListening() }
    private val watchdogRunnable = Runnable { onTurnTimeout() }
    private val speechWatchdogRunnable = Runnable { onSpeechTimeout() }

    private fun now() = SystemClock.elapsedRealtime()
    private fun troll() = ClaudGamePrefs.isTrollEnabled(context)
    private fun hasMic() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun nextProactiveDelay() = ProactivePlanner.nextDelayMs(troll(), Random.nextDouble())

    // ------------------------------------------------------------------ ciclo de vida

    fun start() {
        if (started || destroyed) return
        started = true
        val nowMs = now()
        lastActivityAt = nowMs
        screenStaticSince = nowMs
        voice = ZuleideVoice(context, ClaudGamePrefs.voiceName(context), voiceCallbacks)
        client.checkVersion { status -> onServerStatus(status) }
        syncListener()
        scheduleProactive(nextProactiveDelay())
    }

    fun stop() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        call?.cancel()
        call = null
        listener?.stop()
        listener = null
        oneShotListener?.stop()
        oneShotListener = null
        voice?.shutdown()
        voice = null
        client.close()
    }

    private fun onServerStatus(status: ZuleideClient.ServerStatus) {
        if (destroyed) return
        when (status) {
            ZuleideClient.ServerStatus.OK -> serverOutdated = false
            ZuleideClient.ServerStatus.OUTDATED -> {
                serverOutdated = true
                ui.onFlash("servidor antigo")
                speakLocalLimited(OfflineLines.serverOutdated())
            }
            ZuleideClient.ServerStatus.NOT_CONFIGURED -> {
                backoffUntil = now() + 60_000L
                ui.onFlash("sem chave Gemini")
            }
            ZuleideClient.ServerStatus.UNREACHABLE -> ui.onFlash("sem rede")
        }
    }

    // ------------------------------------------------------------------ controles do jogador

    /** Liga/desliga a escuta automática (botão do painel ou da notificação). */
    fun setListeningEnabled(enabled: Boolean) {
        ClaudGamePrefs.setListeningEnabled(context, enabled)
        syncListener()
    }

    /** O modo troll ou a fala espontânea foram alterados: reavalia o ritmo logo. */
    fun onPreferencesChanged() {
        if (destroyed) return
        scheduleProactive(minOf(nextProactiveDelay(), 12_000L))
    }

    /** Tocar no painel: interrompe a fala e escuta o jogador agora (útil com o jogo muito alto). */
    fun talkNow() {
        if (destroyed) return
        if (!hasMic()) {
            setState(State.NO_MIC)
            ui.onFlash("sem microfone")
            return
        }
        main.removeCallbacks(resumeRunnable)
        cancelCall()
        stopSpeaking()
        hearing = false
        forcedPending = true // esta fala vale mesmo durante a pausa por barulho
        val continuous = listener
        if (continuous != null) {
            continuous.forceListen()
        } else {
            stopOneShot()
            oneShotListener = VoiceListener(listenerCallbacks, oneShot = true).also {
                it.start()
                it.forceListen()
            }
        }
        setState(State.HEARING)
    }

    fun onSnapshot(snapshot: CaptureBridge.Snapshot) {
        if (destroyed || snapshot.status != CaptureBridge.STATUS_OK) return
        val signature = snapshot.signature
        if (signature == null || FrameSignature.diff(lastSignature, signature) > STATIC_DIFF_THRESHOLD) {
            screenStaticSince = now()
            commentedOnThisScreen = false
        }
        lastSignature = signature
    }

    // ------------------------------------------------------------------ escuta

    private fun idleState(): State = when {
        !ClaudGamePrefs.isListeningEnabled(context) -> State.MUTED
        !hasMic() -> State.NO_MIC
        else -> State.LISTENING
    }

    private fun setState(newState: State) {
        if (state == newState) return
        state = newState
        ui.onState(newState)
    }

    private fun syncListener() {
        if (destroyed) return
        if (!ClaudGamePrefs.isListeningEnabled(context)) {
            listener?.stop()
            listener = null
            hearing = false
        } else if (!hasMic()) {
            ui.onFlash("sem microfone")
        } else if (listener == null) {
            listener = VoiceListener(listenerCallbacks).also { it.start() }
        }
        val busy = call != null || voice?.isSpeaking == true || hearing
        if (!busy) setState(idleState()) else ui.onState(state)
    }

    private val listenerCallbacks = object : VoiceListener.Callbacks {
        override fun onSpeechStart() {
            if (destroyed) return
            hearing = true
            // O jogador falou por cima de um comentário espontâneo: cancela o comentário.
            if (currentKind == ZuleideClient.Turn.PROACTIVE) {
                cancelCall()
                stopSpeaking()
            }
            main.removeCallbacks(resumeRunnable)
            setState(State.HEARING)
        }

        override fun onUtterance(wav: ByteArray) {
            if (destroyed) return
            hearing = false
            stopOneShot()
            val nowMs = now()
            val forced = forcedPending
            forcedPending = false
            if (!forced && nowMs < autoListenPausedUntil) {
                maybeResumeListening() // ambiente barulhento: ignora até o jogador tocar no painel
                return
            }
            lastActivityAt = nowMs
            lastPlayerSpeechAt = nowMs
            if (serverOutdated) {
                ui.onFlash("servidor antigo")
                speakLocalLimited(OfflineLines.serverOutdated())
                maybeResumeListening()
                return
            }
            cancelCall()
            stopSpeaking()
            startTurn(ZuleideClient.Turn.USER, wav)
        }

        override fun onDiscarded() {
            if (destroyed) return
            hearing = false
            forcedPending = false
            stopOneShot()
            maybeResumeListening()
        }

        override fun onStatus(status: VoiceListener.Status) {
            if (destroyed) return
            when (status) {
                VoiceListener.Status.LISTENING -> if (!hearing && call == null && voice?.isSpeaking != true) setState(idleState())
                VoiceListener.Status.BLOCKED -> ui.onFlash("mic ocupado")
                VoiceListener.Status.FAILED -> {
                    listener = null
                    hearing = false
                    setState(State.NO_MIC)
                    ui.onFlash("sem microfone")
                }
            }
        }
    }

    private fun stopOneShot() {
        oneShotListener?.stop()
        oneShotListener = null
    }

    private fun maybeResumeListening() {
        if (destroyed) return
        main.removeCallbacks(resumeRunnable)
        main.postDelayed(resumeRunnable, RESUME_DELAY_MS)
    }

    private fun resumeListening() {
        if (destroyed || hearing) return
        if (call != null) return // ainda recebendo a resposta
        if (voice?.isSpeaking == true) return
        listener?.setPaused(false)
        setState(idleState())
    }

    // ------------------------------------------------------------------ turnos com a IA

    private fun staticMs(): Long = now() - screenStaticSince

    private fun startTurn(kind: String, wav: ByteArray?) {
        val snapshot = CaptureBridge.latest
        val frame = snapshot?.takeIf { it.status == CaptureBridge.STATUS_OK }
        val nowMs = now()
        val turn = ZuleideClient.Turn(
            kind = kind,
            troll = troll(),
            wav = wav,
            jpeg = frame?.jpeg,
            screenContext = frame?.screenContext.orEmpty(),
            history = memory.snapshot(),
            idleSeconds = if (lastPlayerSpeechAt == 0L) null else ((nowMs - lastPlayerSpeechAt) / 1000L).toInt(),
            screenStaticSeconds = (staticMs() / 1000L).toInt()
        )
        val id = ++turnId
        currentKind = kind
        sentenceCount = 0
        if (kind == ZuleideClient.Turn.USER) {
            listener?.setPaused(true)
            setState(State.THINKING)
        }
        call = client.start(turn, object : ZuleideClient.Listener {
            override fun onSentence(text: String) {
                if (id == turnId && !destroyed) handleSentence(text)
            }

            override fun onFinished(result: ZuleideClient.Result) {
                if (id == turnId && !destroyed) handleFinished(result)
            }

            override fun onFailure(failure: ZuleideClient.Failure) {
                if (id == turnId && !destroyed) handleFailure(failure)
            }
        })
        main.removeCallbacks(watchdogRunnable)
        main.postDelayed(watchdogRunnable, TURN_TIMEOUT_MS)
    }

    private fun cancelCall() {
        call?.cancel()
        call = null
        currentKind = null
        turnId++ // descarta callbacks atrasados do turno cancelado
        main.removeCallbacks(watchdogRunnable)
        // Se o comentário foi cortado porque o jogador falou e essa fala for descartada, ninguém mais
        // reagenda: sem isto a Zuleide ficaria muda para sempre.
        scheduleProactive(nextProactiveDelay())
    }

    private fun handleSentence(text: String) {
        sentenceCount++
        voice?.speak(text, flush = sentenceCount == 1)
        armSpeechWatchdog()
    }

    /** Alguns motores de TTS nunca avisam que terminaram: sem isto a escuta ficaria pausada para sempre. */
    private fun armSpeechWatchdog() {
        main.removeCallbacks(speechWatchdogRunnable)
        main.postDelayed(speechWatchdogRunnable, SPEECH_TIMEOUT_MS)
    }

    private fun onSpeechTimeout() {
        if (destroyed) return
        if (voice?.isSpeaking == true) {
            Log.w(TAG, "TTS não terminou em ${SPEECH_TIMEOUT_MS / 1000}s; interrompendo")
            stopSpeaking()
        }
        maybeResumeListening()
    }

    private fun handleFinished(result: ZuleideClient.Result) {
        main.removeCallbacks(watchdogRunnable)
        val kind = currentKind
        call = null
        currentKind = null
        result.quietMinutes?.let { quietUntil = if (it == 0) 0L else now() + it * 60_000L }
        if (kind == ZuleideClient.Turn.USER) trackNoise(result)
        if (!result.silent && result.spokenText.isNotBlank()) {
            if (kind == ZuleideClient.Turn.USER) {
                memory.add(ConversationMemory.Who.PLAYER, result.heard ?: "(falou algo por áudio)")
            }
            memory.add(ConversationMemory.Who.ZULEIDE, result.spokenText)
            lastActivityAt = now()
            if (kind == ZuleideClient.Turn.PROACTIVE) commentedOnThisScreen = true
        } else if (result.blocked && kind == ZuleideClient.Turn.USER) {
            speakLocalLimited(OfflineLines.blocked())
        }
        scheduleProactive(nextProactiveDelay())
        maybeResumeListening()
    }

    /** Respostas "[SILENCIO]" em sequência = o microfone está captando o jogo/ambiente, não o jogador. */
    private fun trackNoise(result: ZuleideClient.Result) {
        if (!result.silent || result.blocked) {
            silentTurnsInARow = 0
            return
        }
        silentTurnsInARow++
        if (silentTurnsInARow >= NOISY_SILENCES_LIMIT) {
            silentTurnsInARow = 0
            autoListenPausedUntil = now() + NOISY_PAUSE_MS
            ui.onFlash("muito barulho")
            speakLocalLimited(OfflineLines.noisy())
        }
    }

    private fun handleFailure(failure: ZuleideClient.Failure) {
        main.removeCallbacks(watchdogRunnable)
        val kind = currentKind
        call = null
        currentKind = null
        val nowMs = now()
        Log.w(TAG, "Turno falhou (${failure.kind}): ${failure.message}")
        when (failure.kind) {
            ZuleideClient.Failure.Kind.OUTDATED_SERVER -> {
                serverOutdated = true
                ui.onFlash("servidor antigo")
            }
            ZuleideClient.Failure.Kind.NOT_CONFIGURED -> {
                backoffUntil = nowMs + 60_000L
                ui.onFlash("sem chave Gemini")
            }
            ZuleideClient.Failure.Kind.QUOTA -> {
                backoffUntil = nowMs + 90_000L
                ui.onFlash("cota do Gemini")
            }
            ZuleideClient.Failure.Kind.NETWORK -> {
                backoffUntil = nowMs + 15_000L
                ui.onFlash("sem rede")
            }
            ZuleideClient.Failure.Kind.SERVER -> {
                backoffUntil = nowMs + 20_000L
                ui.onFlash("erro no servidor")
            }
        }
        if (kind == ZuleideClient.Turn.USER) {
            speakLocalLimited(if (serverOutdated) OfflineLines.serverOutdated() else OfflineLines.error())
        } else if (kind == ZuleideClient.Turn.PROACTIVE && ClaudGamePrefs.isProactiveEnabled(context) &&
            nowMs - lastOfflineQuipAt >= OFFLINE_QUIP_GAP_MS && !serverOutdated
        ) {
            // Sem IA, ela continua "viva" com falas locais (raras, para não enjoar).
            lastOfflineQuipAt = nowMs
            speakLocal(OfflineLines.idle(troll()))
        }
        scheduleProactive(nextProactiveDelay())
        maybeResumeListening()
    }

    private fun onTurnTimeout() {
        if (destroyed) return
        val kind = currentKind
        Log.w(TAG, "Turno sem resposta em ${TURN_TIMEOUT_MS / 1000}s; cancelando")
        cancelCall()
        ui.onFlash("demorou demais")
        if (kind == ZuleideClient.Turn.USER) speakLocalLimited(OfflineLines.error())
        scheduleProactive(nextProactiveDelay())
        maybeResumeListening()
    }

    // ------------------------------------------------------------------ fala espontânea

    private fun scheduleProactive(delayMs: Long) {
        main.removeCallbacks(proactiveRunnable)
        if (destroyed) return
        main.postDelayed(proactiveRunnable, delayMs)
    }

    private fun isBusy(): Boolean =
        call != null || hearing || voice?.isSpeaking == true || state == State.THINKING

    private fun onProactiveTick() {
        if (destroyed) return
        if (serverOutdated) {
            scheduleProactive(30_000L)
            return
        }
        val snapshot = CaptureBridge.latest
        val screenReady = snapshot != null && snapshot.status == CaptureBridge.STATUS_OK && snapshot.jpeg != null
        val decision = ProactivePlanner.decide(
            ProactivePlanner.Input(
                nowMs = now(),
                troll = troll(),
                enabled = ClaudGamePrefs.isProactiveEnabled(context),
                busy = isBusy(),
                quietUntilMs = quietUntil,
                backoffUntilMs = backoffUntil,
                lastActivityMs = lastActivityAt,
                screenReady = screenReady,
                screenStaticMs = staticMs(),
                commentedOnThisScreen = commentedOnThisScreen
            )
        )
        when (decision) {
            ProactivePlanner.Decision.Fire -> startTurn(ZuleideClient.Turn.PROACTIVE, null)
            is ProactivePlanner.Decision.Wait -> scheduleProactive(decision.retryInMs.coerceIn(1_000L, 30_000L))
        }
    }

    // ------------------------------------------------------------------ voz

    /** Interrompe a fala na hora e desarma o watchdog de fala. */
    private fun stopSpeaking() {
        main.removeCallbacks(speechWatchdogRunnable)
        voice?.stop()
    }

    /** Falas locais entram na fila (não cortam uma fala em andamento, como a saudação). */
    private fun speakLocal(text: String) {
        voice?.speak(text, flush = false)
    }

    private fun speakLocalLimited(text: String) {
        val nowMs = now()
        if (nowMs - lastErrorSpeechAt < ERROR_SPEECH_GAP_MS) return
        lastErrorSpeechAt = nowMs
        speakLocal(text)
    }

    private val voiceCallbacks = object : ZuleideVoice.Callbacks {
        override fun onReady(voiceName: String?, portugueseAvailable: Boolean) {
            if (destroyed) return
            if (!portugueseAvailable) ui.onFlash("sem voz pt-BR")
            if (!greeted) {
                greeted = true
                // Se o servidor já se mostrou antigo, o aviso dele substitui a saudação.
                if (!serverOutdated) speakLocal(OfflineLines.greeting(troll()))
            }
        }

        override fun onUnavailable() {
            if (!destroyed) ui.onFlash("sem voz")
        }

        override fun onSpeechStarted() {
            if (destroyed) return
            main.removeCallbacks(resumeRunnable)
            listener?.setPaused(true) // ela não pode se escutar
            setState(State.SPEAKING)
            armSpeechWatchdog()
        }

        override fun onSpeechFinished() {
            if (destroyed) return
            main.removeCallbacks(speechWatchdogRunnable)
            lastActivityAt = now()
            maybeResumeListening()
        }
    }
}
