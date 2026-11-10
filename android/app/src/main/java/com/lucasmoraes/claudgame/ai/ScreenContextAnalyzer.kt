package com.lucasmoraes.claudgame.ai

import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * IA visual local e gratuita.
 *
 * Não envia a tela para a internet: o reconhecimento de texto acontece no aparelho.
 * O resultado vira contexto para o AssistantEngine.
 */
class ScreenContextAnalyzer(
    private val onContextReady: (String) -> Unit
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var lastContext = ""

    fun analyze(image: Image, rotationDegrees: Int) {
        if (busy) {
            image.close()
            return
        }

        busy = true
        val input = InputImage.fromMediaImage(image, rotationDegrees)

        recognizer.process(input)
            .addOnSuccessListener { result ->
                val text = result.text
                    .replace("\\s+".toRegex(), " ")
                    .trim()

                val context = buildContext(text)
                if (context.isNotBlank() && context != lastContext) {
                    lastContext = context
                    onContextReady(context)
                }
            }
            .addOnFailureListener { error ->
                Log.d("ClaudGameVision", "OCR indisponível nesta imagem: ${error.message}")
            }
            .addOnCompleteListener {
                image.close()
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

    fun close() {
        recognizer.close()
    }
}
