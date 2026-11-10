package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream

class ScreenContextAnalyzer(
    private val onContextReady: (String, ByteArray?, String) -> Unit
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var busy = false
    @Volatile private var closed = false
    private var lastContext = ""

    fun analyze(image: Image, rotationDegrees: Int) {
        if (closed || busy) { safelyClose(image); return }
        busy = true
        try {
            val (jpeg, frameStatus) = imageToJpeg(image)
            val input = InputImage.fromMediaImage(image, rotationDegrees)
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    try {
                        if (closed) return@addOnSuccessListener
                        val text = result.text.replace("\\s+".toRegex(), " ").trim()
                        val context = buildContext(text)
                        if (context.isNotBlank()) lastContext = context
                        safeNotify(context.ifBlank { lastContext }, jpeg, frameStatus)
                    } catch (error: Exception) {
                        Log.w("ClaudGameVision", "Falha ao interpretar OCR", error)
                        if (!closed) safeNotify(lastContext, jpeg, frameStatus)
                    }
                }
                .addOnFailureListener { error ->
                    Log.d("ClaudGameVision", "OCR indisponível nesta imagem", error)
                    if (!closed) safeNotify(lastContext, jpeg, frameStatus)
                }
                .addOnCompleteListener { safelyClose(image); busy = false }
        } catch (error: Exception) {
            Log.e("ClaudGameVision", "Falha ao processar frame", error)
            safelyClose(image)
            busy = false
            if (!closed) safeNotify(lastContext, null, "unavailable")
        }
    }

    private fun safeNotify(context: String, jpeg: ByteArray?, status: String) {
        try { onContextReady(context, jpeg, status) }
        catch (error: Exception) { Log.w("ClaudGameVision", "Falha ao publicar frame", error) }
    }

    // ImageReader RGBA tem padding por linha. Copiar o buffer inteiro para Bitmap
    // ignora rowStride/pixelStride e pode gerar frames corrompidos ou pretos.
    private fun imageToJpeg(image: Image): Pair<ByteArray?, String> {
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val plane = image.planes.firstOrNull() ?: return Pair(null, "unavailable")
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            if (pixelStride < 4 || rowStride <= 0) return Pair(null, "unavailable")

            val pixels = IntArray(image.width * image.height)
            var nonDark = 0L
            var luminanceTotal = 0L
            for (y in 0 until image.height) {
                val rowOffset = base + y * rowStride
                for (x in 0 until image.width) {
                    val offset = rowOffset + x * pixelStride
                    if (offset + 3 >= buffer.limit()) return Pair(null, "unavailable")
                    val red = buffer.get(offset).toInt() and 0xff
                    val green = buffer.get(offset + 1).toInt() and 0xff
                    val blue = buffer.get(offset + 2).toInt() and 0xff
                    val alpha = buffer.get(offset + 3).toInt() and 0xff
                    val a = if (alpha == 0) 255 else alpha
                    pixels[y * image.width + x] = (a shl 24) or (red shl 16) or (green shl 8) or blue
                    val lum = (red * 299 + green * 587 + blue * 114) / 1000
                    luminanceTotal += lum
                    if (lum > 12) nonDark++
                }
            }
            val total = pixels.size.toLong().coerceAtLeast(1L)
            val average = luminanceTotal.toDouble() / total
            val visibleRatio = nonDark.toDouble() / total
            if (average < 2.0 && visibleRatio < 0.001) {
                Log.w("ClaudGameVision", "MediaProjection entregou frame preto (" + image.width + "x" + image.height + ")")
                return Pair(null, "black")
            }

            val baseBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            bitmap = baseBitmap
            baseBitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            var candidate: Bitmap = baseBitmap
            var quality = 76
            var bytes = encode(candidate, quality)
            while (bytes.size > 3_300_000 && quality > 48) {
                quality -= 8
                bytes = encode(candidate, quality)
            }
            if (bytes.size > 3_300_000) {
                val ratio = minOf(1f, 1280f / maxOf(candidate.width, candidate.height).toFloat())
                scaled = Bitmap.createScaledBitmap(candidate, (candidate.width * ratio).toInt().coerceAtLeast(1), (candidate.height * ratio).toInt().coerceAtLeast(1), true)
                candidate = scaled!!
                bytes = encode(candidate, 58)
            }
            if (bytes.isEmpty() || bytes.size > 3_500_000) return Pair(null, "unavailable")
            Log.d("ClaudGameVision", "Frame convertido: " + candidate.width + "x" + candidate.height + ", " + bytes.size + " bytes; brilho médio=" + average)
            return Pair(bytes, "ok")
        } catch (error: Exception) {
            Log.e("ClaudGameVision", "Falha ao converter RGBA em JPEG", error)
            return Pair(null, "unavailable")
        } finally {
            if (scaled != null && scaled !== bitmap) scaled?.recycle()
            bitmap?.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray().also { output.close() }
    }

    private fun buildContext(text: String): String {
        if (text.isBlank()) return ""
        val lower = text.lowercase()
        val events = mutableListOf<String>()
        if (containsAny(lower, "game over", "derrota", "derrotado", "you died", "morreu")) events += "parece que a partida terminou em derrota"
        if (containsAny(lower, "victory", "vitória", "vitoria", "winner", "venceu", "booyah")) events += "parece que houve vitória"
        if (containsAny(lower, "loading", "carregando")) events += "a tela parece estar carregando"
        if (containsAny(lower, "pause", "pausado", "menu")) events += "o jogo parece estar em um menu ou pausa"
        if (containsAny(lower, "objective", "objetivo", "mission", "missão", "missao", "quest")) events += "há indicação de objetivo ou missão na tela"
        val shortText = text.take(220)
        return if (events.isEmpty()) "Texto visível na tela: $shortText" else events.joinToString("; ") + ". Texto visível: $shortText"
    }

    private fun containsAny(text: String, vararg terms: String): Boolean = terms.any { text.contains(it) }
    private fun safelyClose(image: Image) { try { image.close() } catch (_: Exception) {} }
    fun close() {
        closed = true
        try { recognizer.close() } catch (error: Exception) { Log.w("ClaudGameVision", "Falha ao fechar OCR", error) }
    }
}
