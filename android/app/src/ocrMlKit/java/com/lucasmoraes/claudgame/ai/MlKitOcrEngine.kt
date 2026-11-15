package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

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