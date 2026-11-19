package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OCR com ML Kit.
 *
 * O quadro da MediaProjection chega como RGBA_8888, formato que `InputImage.fromMediaImage` NÃO aceita
 * (só YUV), e `InputImage.fromByteArray` só aceita NV21/YV12 — passar JPEG ali lança exceção.
 * O caminho correto para um RGBA é `InputImage.fromBitmap`. A rotação é 0 porque o espelho da tela
 * já entrega o quadro na orientação em que o jogador está vendo.
 */
class MlKitOcrEngine : OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun recognize(bitmap: Bitmap, onDone: (String) -> Unit) {
        val delivered = AtomicBoolean(false)
        val deliver: (String) -> Unit = { text ->
            if (delivered.compareAndSet(false, true)) onDone(text)
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> deliver(result.text) }
                .addOnFailureListener { error ->
                    Log.d("ClaudGameVision", "OCR indisponível nesta imagem", error)
                    deliver("")
                }
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao iniciar OCR", error)
            deliver("")
        }
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (error: Exception) {
            Log.w("ClaudGameVision", "Falha ao fechar OCR", error)
        }
    }
}
