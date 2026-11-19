package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap

/**
 * Reconhecimento de texto na tela. A implementação real (ML Kit) fica em `src/ocrMlKit`;
 * em `src/ocrNone` existe uma versão vazia para builds sem ML Kit (-PclaudgameOcr=none).
 * Em ambos os casos o quadro da tela continua sendo enviado à IA multimodal.
 */
interface OcrEngine {
    /**
     * Reconhece o texto de [bitmap]. [onDone] é chamado UMA vez (texto vazio se falhar), em qualquer thread.
     * O [bitmap] só pode ser reciclado pelo chamador depois de [onDone].
     */
    fun recognize(bitmap: Bitmap, onDone: (String) -> Unit)

    fun close()
}

/** Sem OCR local: devolve texto vazio e a IA multimodal analisa a imagem diretamente. */
object NoOcrEngine : OcrEngine {
    override fun recognize(bitmap: Bitmap, onDone: (String) -> Unit) = onDone("")

    override fun close() = Unit
}
