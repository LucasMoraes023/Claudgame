package com.lucasmoraes.claudgame.companion

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.abs

/**
 * Escuta automática: mantém o microfone aberto numa thread própria e usa o [SpeechDetector] para recortar
 * cada fala do jogador. Entrega o áudio já em WAV (16 kHz, mono) pronto para o Gemini; nenhum áudio é
 * gravado em disco. Todos os callbacks rodam na thread principal.
 *
 * Usa o microfone com tipo VOICE_RECOGNITION (sem o "bip" do reconhecedor do Android) e liga supressão de
 * ruído e cancelamento de eco quando o aparelho oferece.
 */
class VoiceListener(
    private val callbacks: Callbacks,
    private val oneShot: Boolean = false
) {
    interface Callbacks {
        fun onSpeechStart()
        fun onUtterance(wav: ByteArray)
        fun onDiscarded()
        fun onStatus(status: Status)
    }

    enum class Status { LISTENING, BLOCKED, FAILED }

    private companion object {
        const val TAG = "ZuleideMic"
        const val SAMPLE_RATE = 16_000
        const val BLOCKED_AFTER_FRAMES = 200 // ~4 s de silêncio digital absoluto
        const val MAX_RETRIES = 3
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    private var forceRequested = false

    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "ZuleideMic").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        val current = thread
        thread = null
        if (current != null && current !== Thread.currentThread()) {
            try {
                current.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** Enquanto pausado o microfone continua aberto, mas nada é analisado (ex.: Zuleide falando). */
    fun setPaused(value: Boolean) {
        paused = value
    }

    /** "Tocar para falar": a próxima fala é capturada mesmo que comece baixinho. */
    fun forceListen() {
        paused = false
        forceRequested = true
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun loop() {
        val detector = SpeechDetector()
        val frame = ShortArray(detector.frameSize)
        var record: AudioRecord? = null
        var effects: List<AudioEffect> = emptyList()
        var failures = 0
        var zeroFrames = 0
        var blocked = false
        try {
            while (running) {
                if (record == null) {
                    record = createRecord()
                    if (record == null) {
                        failures++
                        Log.w(TAG, "Não foi possível abrir o microfone (tentativa $failures)")
                        if (failures >= MAX_RETRIES) {
                            post { callbacks.onStatus(Status.FAILED) }
                            return
                        }
                        sleep(1_500)
                        continue
                    }
                    failures = 0
                    effects = attachEffects(record.audioSessionId)
                    post { callbacks.onStatus(Status.LISTENING) }
                }
                val read = readFully(record, frame)
                if (read < 0) {
                    // O sistema derrubou o microfone (ex.: outro app assumiu): tenta reabrir.
                    release(record, effects)
                    record = null
                    effects = emptyList()
                    sleep(700)
                    continue
                }
                if (read == 0) continue

                if (isDigitalSilence(frame, read)) {
                    zeroFrames++
                    if (zeroFrames == BLOCKED_AFTER_FRAMES && !blocked) {
                        blocked = true
                        post { callbacks.onStatus(Status.BLOCKED) }
                    }
                } else {
                    zeroFrames = 0
                    if (blocked) {
                        blocked = false
                        post { callbacks.onStatus(Status.LISTENING) }
                    }
                }

                if (paused) {
                    detector.reset()
                    continue
                }
                if (forceRequested) {
                    forceRequested = false
                    detector.forceStart()
                }
                when (val event = detector.process(frame, read)) {
                    SpeechDetector.Event.None -> Unit
                    SpeechDetector.Event.SpeechStarted -> post { callbacks.onSpeechStart() }
                    is SpeechDetector.Event.Utterance -> {
                        val wav = WavEncoder.encode(event.pcm, SAMPLE_RATE)
                        post { callbacks.onUtterance(wav) }
                        if (oneShot) running = false
                    }
                    SpeechDetector.Event.Discarded -> {
                        post { callbacks.onDiscarded() }
                        if (oneShot) running = false
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Falha na captura de áudio", error)
            post { callbacks.onStatus(Status.FAILED) }
        } finally {
            release(record, effects)
        }
    }

    private fun createRecord(): AudioRecord? {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) return null
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, SAMPLE_RATE * 2) // ~1 s de folga
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                return null
            }
            record
        } catch (error: Exception) {
            // SecurityException (sem permissão) ou IllegalArgumentException (parâmetros recusados)
            Log.e(TAG, "AudioRecord indisponível", error)
            null
        }
    }

    private fun attachEffects(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>()
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.let {
                it.enabled = true
                effects.add(it)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Supressão de ruído indisponível", error)
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.let {
                it.enabled = true
                effects.add(it)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Cancelamento de eco indisponível", error)
        }
        return effects
    }

    private fun release(record: AudioRecord?, effects: List<AudioEffect>) {
        for (effect in effects) {
            try {
                effect.release()
            } catch (_: Exception) {
            }
        }
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: Exception) {
            }
            try {
                record.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun readFully(record: AudioRecord, buffer: ShortArray): Int {
        var total = 0
        while (total < buffer.size && running) {
            val count = record.read(buffer, total, buffer.size - total)
            if (count < 0) return count
            if (count == 0) {
                sleep(5)
                continue
            }
            total += count
        }
        return total
    }

    private fun isDigitalSilence(frame: ShortArray, length: Int): Boolean {
        for (i in 0 until length) if (abs(frame[i].toInt()) > 0) return false
        return true
    }

    private fun sleep(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
