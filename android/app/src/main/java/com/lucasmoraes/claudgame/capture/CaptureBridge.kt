package com.lucasmoraes.claudgame.capture

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Ponte em memória (mesmo processo) entre a captura de tela e o companheiro flutuante.
 *
 * Substitui o broadcast + arquivo em cache usados antes. Motivos:
 *  - um broadcast dinâmico em Android < 13 é exportado: qualquer app poderia mandar um
 *    `frame_path` e fazer o ClaudGame ler/enviar um arquivo arbitrário à IA;
 *  - o arquivo era reescrito a cada frame enquanto outra thread o lia (JPEG truncado);
 *  - o quadro da tela ficava gravado em disco depois da sessão.
 */
object CaptureBridge {
    const val STATUS_OK = "ok"
    const val STATUS_BLACK = "black"
    const val STATUS_UNAVAILABLE = "unavailable"
    const val STATUS_STOPPED = "stopped"

    /** Último estado da captura: contexto OCR + JPEG do mesmo quadro. */
    class Snapshot(
        val screenContext: String,
        val jpeg: ByteArray?,
        val status: String,
        val capturedAt: Long = System.currentTimeMillis(),
        val detail: String? = null
    )

    fun interface Listener {
        fun onSnapshot(snapshot: Snapshot)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** true entre o início do companheiro (Hub) e o encerramento da captura. */
    @Volatile
    var sessionActive: Boolean = false

    @Volatile
    var latest: Snapshot? = null
        private set

    /** Pode ser chamado de qualquer thread; os ouvintes sempre rodam na thread principal. */
    fun publish(snapshot: Snapshot) {
        latest = snapshot
        mainHandler.post {
            for (listener in listeners) listener.onSnapshot(snapshot)
        }
    }

    fun clear() {
        latest = null
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }
}