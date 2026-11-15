package com.lucasmoraes.claudgame.ai

/** Variante SEM ML Kit (-PclaudgameOcr=none). */
object OcrEngines {
    const val NAME = "none"

    fun create(): OcrEngine = NoOcrEngine
}