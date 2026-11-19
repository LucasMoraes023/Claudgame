package com.lucasmoraes.claudgame.ai

/**
 * Variante SEM ML Kit (-PclaudgameOcr=none). O build padrão usa `src/ocrMlKit`.
 * Esta pasta e `src/ocrMlKit` definem o mesmo objeto; o Gradle inclui apenas uma delas.
 */
object OcrEngines {
    const val NAME = "none"

    fun create(): OcrEngine = NoOcrEngine
}
