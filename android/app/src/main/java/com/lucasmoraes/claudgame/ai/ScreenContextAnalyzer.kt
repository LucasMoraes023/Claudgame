package com.lucasmoraes.claudgame.ai

import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reconhecimento local e gratuito de texto na tela.
 * Toda falha de OCR é isolada para não derrubar o serviço de captura.
 */
class ScreenContextAnalyzer(
    private val onContextReady: (String) -> Unit
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile private var closed = false
    private var lastContext = ""

    fun analyze(image: Image, rotationDegrees: Int) {
        if (closed || busy) {
            safelyClose(image)
            return
        }

        busy = true
        try {
            val input = InputImage.fromMediaImage(image, rotationDegrees)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    try {
                        if (closed) return@addOnSuccessListener
                        val text = result.text
                            .replace("\\s+".toRegex(), " ")
                            .trim()
                        val context = buildContext(text)
                        if (context.isNotBlank() && context != lastContext) {
                            lastContext = context
                            try {
                                onContextReady(context)
                            } catch (error: Exception) {
                                Log.w("ClaudGameVision", "Falha ao enviar contexto", error)
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("ClaudGameVision", "Falha ao interpretar OCR", error)
                    }
                }
                .addOnFailureListener { error ->
                    Log.d("ClaudGameVision", "OCR indisponível nesta imagem", error)
                }
                .addOnCompleteListener {
                    safelyClose(image)
                    busy = false
                }
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao iniciar OCR", error)
            safelyClose(image)
            busy = false
        }
    }

    private fun buildContext(text: String): String {
        if (text.isBlank()) return ""

        val lower = text.lowercase()
        val events = mutableListOf<String>()

        if (containsAny(lower, "game over", "derrota", "derrotado", "you died", "morreu")) {
            events += "parece que a partida terminou em derrota"
        }
        if (containsAny(lower, "victory", "vitória", "vitoria", "winner", "venceu", "booyah")) {
            events += "parece que houve vitória"
        }
        if (containsAny(lower, "loading", "carregando")) {
            events += "a tela parece estar carregando"
        }
        if (containsAny(lower, "pause", "pausado", "menu")) {
            events += "o jogo parece estar em um menu ou pausa"
        }
        if (containsAny(lower, "objective", "objetivo", "mission", "missão", "missao", "quest")) {
            events += "há indicação de objetivo ou missão na tela"
        }

        val shortText = text.take(220)
        return if (events.isEmpty()) {
            "Texto visível na tela: $shortText"
        } else {
            events.joinToString("; ") + ". Texto visível: $shortText"
        }
    }

    private fun containsAny(text: String, vararg terms: String): Boolean =
        terms.any { text.contains(it) }

    private fun safelyClose(image: Image) {
        try {
            image.close()
        } catch (_: Exception) {
        }
    }

    fun close() {
        closed = true
        try {
            recognizer.close()
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao fechar OCR", error)
        }
    }
}
