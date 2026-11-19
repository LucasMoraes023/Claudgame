package com.lucasmoraes.claudgame.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.util.Log
import com.lucasmoraes.claudgame.capture.CaptureBridge
import com.lucasmoraes.claudgame.companion.FrameSignature
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Converte um quadro da tela em JPEG + contexto de texto (OCR) e publica o resultado.
 *
 * Correções em relação à versão anterior:
 *  - o JPEG era passado a `InputImage.fromByteArray(..., ImageFormat.JPEG)`, que só aceita NV21/YV12
 *    e lançava exceção: NENHUM quadro chegava à IA ("falha ao converter a captura");
 *  - a conversão pixel a pixel rodava na thread principal (travava o painel flutuante);
 *    agora usa cópia em bloco respeitando o rowStride e roda na thread de captura;
 *  - a rotação do display era enviada ao OCR, mas o espelho já entrega a imagem "em pé".
 */
class ScreenContextAnalyzer(
    private val ocr: OcrEngine,
    private val onSnapshot: (CaptureBridge.Snapshot) -> Unit
) {
    private companion object {
        const val TAG = "ClaudGameVision"
        // Quadro menor = envio e resposta mais rápidos. 1440 px no lado maior preserva melhor HUD e textos pequenos.
        const val JPEG_MAX_SIDE = 1440
        const val JPEG_QUALITY = 78
        const val SOFT_JPEG_LIMIT = 1_200_000
        const val HARD_JPEG_LIMIT = 1_800_000
        const val GRID_X = 128
        const val GRID_Y = 72
        const val STALE_BUSY_MS = 20_000L
    }

    private val busy = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Volatile
    private var busySince = 0L

    /**
     * Tenta analisar [image]. Devolve true se assumiu a posse da imagem (e a fechará);
     * devolve false se está ocupado ou encerrado — nesse caso quem chamou continua dono da imagem.
     */
    fun tryAnalyze(image: Image): Boolean {
        if (closed) return false
        // Watchdog: se um OCR nunca devolver o resultado, não deixa o analisador travado para sempre.
        if (busy.get() && System.currentTimeMillis() - busySince > STALE_BUSY_MS) {
            Log.w(TAG, "Análise anterior não terminou em ${STALE_BUSY_MS / 1000}s; liberando o analisador")
            busy.set(false)
        }
        if (!busy.compareAndSet(false, true)) return false
        busySince = System.currentTimeMillis()

        var bitmap: Bitmap? = null
        try {
            bitmap = imageToBitmap(image)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao converter o quadro da tela", error)
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "Sem memória para converter o quadro da tela", error)
        } finally {
            closeQuietly(image)
        }

        if (bitmap == null) {
            finishWithoutFrame(CaptureBridge.STATUS_UNAVAILABLE, "conversão do quadro falhou")
            return true
        }
        if (looksBlack(bitmap)) {
            Log.w(TAG, "MediaProjection entregou quadro preto (${bitmap.width}x${bitmap.height})")
            recycleQuietly(bitmap)
            finishWithoutFrame(CaptureBridge.STATUS_BLACK, null)
            return true
        }
        val jpeg = try {
            encodeJpeg(bitmap)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao gerar JPEG", error)
            null
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "Sem memória para gerar JPEG", error)
            null
        }
        if (jpeg == null) {
            recycleQuietly(bitmap)
            finishWithoutFrame(CaptureBridge.STATUS_UNAVAILABLE, "JPEG indisponível")
            return true
        }

        Log.d(TAG, "Quadro convertido: ${bitmap.width}x${bitmap.height}, ${jpeg.size} bytes")
        val frame: Bitmap = bitmap
        val signature = signatureOf(frame)
        val completed = AtomicBoolean(false)
        val complete: (String) -> Unit = { text ->
            if (completed.compareAndSet(false, true)) finishWithFrame(frame, jpeg, text, signature)
        }
        try {
            ocr.recognize(frame, complete)
        } catch (error: Exception) {
            Log.w(TAG, "OCR falhou; enviando só a imagem", error)
            complete("")
        }
        return true
    }

    fun close() {
        closed = true
    }

    private fun finishWithFrame(frame: Bitmap, jpeg: ByteArray, text: String, signature: ByteArray) {
        try {
            if (!closed) {
                publish(
                    CaptureBridge.Snapshot(
                        ScreenContextBuilder.build(text), jpeg, CaptureBridge.STATUS_OK, signature = signature
                    )
                )
            }
        } finally {
            recycleQuietly(frame)
            busy.set(false)
        }
    }

    private fun finishWithoutFrame(status: String, detail: String?) {
        try {
            if (!closed) publish(CaptureBridge.Snapshot("", null, status, detail = detail))
        } finally {
            busy.set(false)
        }
    }

    private fun publish(snapshot: CaptureBridge.Snapshot) {
        try {
            onSnapshot(snapshot)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao publicar quadro", error)
        }
    }

    /**
     * ImageReader RGBA_8888 tem preenchimento ao fim de cada linha (rowStride > width * 4). Copia o buffer
     * numa bitmap mais larga (largura = rowStride / 4) e recorta; ignorar o stride gera imagem torta.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        if (pixelStride != 4 || rowStride < width * pixelStride || width <= 0 || height <= 0) return null

        val paddedWidth = rowStride / pixelStride
        val needed = paddedWidth.toLong() * height * 4L
        if (needed > Int.MAX_VALUE) return null
        val buffer: ByteBuffer = plane.buffer ?: return null
        buffer.rewind()

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        var result: Bitmap? = null
        try {
            if (buffer.remaining().toLong() >= needed) {
                padded.copyPixelsFromBuffer(buffer)
            } else {
                // Algumas implementações omitem o preenchimento da última linha.
                val complete = ByteBuffer.allocate(needed.toInt())
                complete.put(buffer)
                complete.rewind()
                padded.copyPixelsFromBuffer(complete)
            }
            padded.setHasAlpha(false)
            result = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            return result
        } finally {
            if (result !== padded) recycleQuietly(padded)
        }
    }

    /** Quadro preto = projeção bloqueada (ex.: conteúdo protegido). Amostra uma grade em vez de varrer tudo. */
    private fun looksBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        var luminanceSum = 0L
        var lit = 0
        for (gy in 0 until GRID_Y) {
            val y = ((gy + 0.5f) * height / GRID_Y).toInt().coerceIn(0, height - 1)
            for (gx in 0 until GRID_X) {
                val x = ((gx + 0.5f) * width / GRID_X).toInt().coerceIn(0, width - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                luminanceSum += luminance
                if (luminance > 12) lit++
            }
        }
        val samples = (GRID_X * GRID_Y).toDouble()
        return luminanceSum / samples < 2.0 && lit / samples < 0.001
    }

    private fun encodeJpeg(source: Bitmap): ByteArray? {
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest > JPEG_MAX_SIDE) {
            val ratio = JPEG_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        try {
            var quality = JPEG_QUALITY
            var bytes = compress(scaled, quality)
            while (bytes.size > SOFT_JPEG_LIMIT && quality > 40) {
                quality -= 8
                bytes = compress(scaled, quality)
            }
            return if (bytes.isNotEmpty() && bytes.size <= HARD_JPEG_LIMIT) bytes else null
        } finally {
            if (scaled !== source) recycleQuietly(scaled)
        }
    }

    /** Miniatura 32x18 em luminância: barata e suficiente para saber se a tela mudou. */
    private fun signatureOf(bitmap: Bitmap): ByteArray {
        val signature = ByteArray(FrameSignature.SIZE)
        val width = bitmap.width
        val height = bitmap.height
        var index = 0
        for (row in 0 until FrameSignature.ROWS) {
            val y = ((row + 0.5f) * height / FrameSignature.ROWS).toInt().coerceIn(0, height - 1)
            for (column in 0 until FrameSignature.COLUMNS) {
                val x = ((column + 0.5f) * width / FrameSignature.COLUMNS).toInt().coerceIn(0, width - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                signature[index++] = luminance.toByte()
            }
        }
        return signature
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream(256 * 1024)
        output.use {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) return ByteArray(0)
        }
        return output.toByteArray()
    }

    private fun closeQuietly(image: Image) {
        try {
            image.close()
        } catch (_: Exception) {
        }
    }

    private fun recycleQuietly(bitmap: Bitmap) {
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
        }
    }
}
