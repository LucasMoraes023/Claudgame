package com.lucasmoraes.claudgame.ai

import android.util.Log

/** Variante padrão: OCR local com ML Kit (modelo latino embutido, funciona offline). */
object OcrEngines {
    const val NAME = "mlkit"

    fun create(): OcrEngine = try {
        MlKitOcrEngine()
    } catch (error: Throwable) {
        // Se o ML Kit não puder iniciar neste aparelho, o app segue sem OCR local.
        Log.w("ClaudGameVision", "ML Kit indisponível; seguindo sem OCR local", error)
        NoOcrEngine
    }
}
