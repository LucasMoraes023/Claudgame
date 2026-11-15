package com.lucasmoraes.claudgame.ai

import android.util.Log

/** Variante padrão: OCR local com ML Kit (modelo latino embutido, funciona offline). */
object OcrEngines {
    const val NAME = "mlkit"

    fun create(): OcrEngine = try {
        MlKitOcrEngine()
    } catch (error: Throwable) {
        Log.w("ClaudGameVision", "ML Kit indisponível; seguindo sem OCR local", error)
        NoOcrEngine
    }
}