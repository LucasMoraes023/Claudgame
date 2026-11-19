package com.lucasmoraes.claudgame.companion

import kotlin.math.sqrt

/**
 * Detector de fala por energia (VAD) para a escuta automática. Lógica pura: recebe quadros de áudio PCM
 * 16 bits (20 ms cada) e devolve eventos. Não usa nenhuma API do Android, por isso é testável na JVM.
 *
 * Como decide:
 *  - aprende o ruído de fundo enquanto ninguém fala (sobe devagar, desce rápido);
 *  - a fala começa quando a energia fica acima de ~3x o ruído por 100 ms e termina após 600 ms de silêncio;
 *  - guarda 300 ms antes do início (para não cortar a primeira sílaba) e 200 ms depois do fim;
 *  - descarta estalos (< ~360 ms de voz) e ruído contínuo (12 s sem pausa), que levantam o limiar.
 */
class SpeechDetector(
    val frameSize: Int = 320,
    private val startFrames: Int = 5,
    private val endSilenceFrames: Int = 30,
    private val preRollFrames: Int = 15,
    private val keepTailFrames: Int = 10,
    private val minVoicedFrames: Int = 18,
    private val maxFrames: Int = 600,
    private val forcedGraceFrames: Int = 150
) {
    sealed class Event {
        object None : Event()
        object SpeechStarted : Event()
        class Utterance(val pcm: ShortArray) : Event()
        object Discarded : Event()
    }

    private companion object {
        const val START_MULTIPLIER = 3.0
        const val STOP_MULTIPLIER = 1.8
        const val MIN_START_LEVEL = 450.0
        const val MIN_STOP_LEVEL = 260.0
        const val MIN_NOISE = 60.0
        const val MAX_NOISE = 3000.0
        const val INITIAL_NOISE = 250.0
        const val NOISE_RUN_RATIO = 0.05
    }

    var noiseFloor: Double = INITIAL_NOISE
        private set

    private var speaking = false
    private var forced = false
    private var waitingFirstVoice = false
    private var loudRun = 0
    private var silentRun = 0
    private var silentTotal = 0
    private var voicedFrames = 0
    private var forcedWaited = 0
    private var levelSum = 0.0
    private val preRoll = ArrayDeque<ShortArray>()
    private val collected = ArrayList<ShortArray>()

    /** "Tocar para falar": começa a capturar no próximo quadro, mesmo que ainda esteja em silêncio. */
    fun forceStart() {
        if (!speaking) forced = true
    }

    fun reset() {
        speaking = false
        forced = false
        waitingFirstVoice = false
        loudRun = 0
        silentRun = 0
        silentTotal = 0
        voicedFrames = 0
        forcedWaited = 0
        levelSum = 0.0
        preRoll.clear()
        collected.clear()
    }

    fun process(frame: ShortArray, length: Int = frame.size): Event {
        if (length <= 0) return Event.None
        val level = rms(frame, length)
        val startThreshold = maxOf(noiseFloor * START_MULTIPLIER, MIN_START_LEVEL)
        val stopThreshold = maxOf(noiseFloor * STOP_MULTIPLIER, MIN_STOP_LEVEL)

        if (!speaking) {
            learnNoise(level)
            remember(frame, length)
            if (forced) {
                forced = false
                begin(waitForVoice = true)
                return Event.SpeechStarted
            }
            loudRun = if (level > startThreshold) loudRun + 1 else 0
            if (loudRun >= startFrames) {
                begin(waitForVoice = false)
                return Event.SpeechStarted
            }
            return Event.None
        }

        collected.add(frame.copyOf(length))
        levelSum += level
        val voiced = if (waitingFirstVoice) level > startThreshold else level > stopThreshold
        if (voiced) {
            voicedFrames++
            silentRun = 0
            waitingFirstVoice = false
        } else {
            silentTotal++
            if (waitingFirstVoice) forcedWaited++ else silentRun++
        }

        if (waitingFirstVoice && forcedWaited >= forcedGraceFrames) return discard()
        if (!waitingFirstVoice && silentRun >= endSilenceFrames) return finish()
        if (collected.size >= maxFrames) return finishAtLimit()
        return Event.None
    }

    private fun begin(waitForVoice: Boolean) {
        speaking = true
        collected.clear()
        collected.addAll(preRoll)
        preRoll.clear()
        voicedFrames = if (waitForVoice) 0 else loudRun
        waitingFirstVoice = waitForVoice
        forcedWaited = 0
        silentRun = 0
        silentTotal = 0
        levelSum = 0.0
        loudRun = 0
    }

    private fun finish(): Event {
        if (voicedFrames < minVoicedFrames) return discard()
        val trailing = (silentRun - keepTailFrames).coerceAtLeast(0)
        val usable = (collected.size - trailing).coerceAtLeast(0)
        var total = 0
        for (i in 0 until usable) total += collected[i].size
        val pcm = ShortArray(total)
        var position = 0
        for (i in 0 until usable) {
            val part = collected[i]
            System.arraycopy(part, 0, pcm, position, part.size)
            position += part.size
        }
        reset()
        return Event.Utterance(pcm)
    }

    /** 12 s seguidos: sem pausas = ruído contínuo (jogo alto); com pausas = fala longa, envia assim mesmo. */
    private fun finishAtLimit(): Event {
        if (silentTotal < collected.size * NOISE_RUN_RATIO) {
            noiseFloor = (levelSum / collected.size).coerceIn(MIN_NOISE, MAX_NOISE)
            return discard()
        }
        return finish()
    }

    private fun discard(): Event {
        reset()
        return Event.Discarded
    }

    private fun learnNoise(level: Double) {
        val target = level.coerceIn(MIN_NOISE, MAX_NOISE)
        noiseFloor = if (target < noiseFloor) noiseFloor * 0.8 + target * 0.2 else noiseFloor * 0.98 + target * 0.02
    }

    private fun remember(frame: ShortArray, length: Int) {
        preRoll.addLast(frame.copyOf(length))
        while (preRoll.size > preRollFrames) preRoll.removeFirst()
    }

    private fun rms(frame: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = frame[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / length)
    }
}
