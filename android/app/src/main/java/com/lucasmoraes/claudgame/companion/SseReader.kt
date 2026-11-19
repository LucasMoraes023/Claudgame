package com.lucasmoraes.claudgame.companion

import java.io.BufferedReader

/** Leitor mínimo de Server-Sent Events: entrega o conteúdo de cada linha "data: ...". */
internal object SseReader {
    /** [onData] devolve false para parar a leitura. */
    fun read(reader: BufferedReader, onData: (String) -> Boolean) {
        while (true) {
            val line = reader.readLine() ?: return
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.substring(5).trim()
            if (payload.isEmpty()) continue
            if (!onData(payload)) return
        }
    }
}
