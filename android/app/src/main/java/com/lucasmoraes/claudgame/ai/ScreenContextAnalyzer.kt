package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream

/**
 * OCR local + captura JPEG compacta do frame atual.
 * A imagem original é fechada assim que o ML Kit termina; somente bytes compactados
 * são entregues ao serviço para a chamada multimodal online quando o jogador perguntar.
 */
class ScreenContextAnalyzer(
    private val onContextReady: (String, ByteArray?) -> Unit
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
            val frameJpeg = imageToJpeg(image)
            val input = InputImage.fromMediaImage(image, rotationDegrees)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    try {
                        if (closed) return@addOnSuccessListener
                        val text = result.text
                            .replace("\\s+".toRegex(), " ")
                            .trim()
                        val context = buildContext(text)
                        if (context.isNotBlank()) lastContext = context
                        // Atualiza o frame mesmo quando o OCR não mudou: o jogador pode
                        // perguntar sobre uma jogada que acabou de acontecer.
                        try {
                            onContextReady(context.ifBlank { lastContext }, frameJpeg)
                        } catch (error: Exception) {
                            Log.w("ClaudGameVision", "Falha ao enviar contexto/frame", error)
                        }
                    } catch (error: Exception) {
                        Log.w("ClaudGameVision", "Falha ao interpretar OCR", error)
                    }
                }
                .addOnFailureListener { error ->
                    Log.d("ClaudGameVision", "OCR indisponível nesta imagem", error)
                    if (!closed && frameJpeg != null) {
                        try { onContextReady(lastContext, frameJpeg) } catch (_: Exception) {}
                    }
                }
                .addOnCompleteListener {
                    safelyClose(image)
                    busy = false
                }
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao iniciar análise", error)
            safelyClose(image)
            busy = false
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride <= 0 || rowStride <= 0) return null
            val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            val buffer = plane.buffer.duplicate().apply { rewind() }
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth == image.width) bitmap
                else Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            val output = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 68, output)
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            output.toByteArray().also { output.close() }
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Não foi possível compactar frame", error)
            null
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
        try { image.close() } catch (_: Exception) {}
    }

    fun close() {
        closed = true
        try { recognizer.close() } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao fechar OCR", error)
        }
    }
}
