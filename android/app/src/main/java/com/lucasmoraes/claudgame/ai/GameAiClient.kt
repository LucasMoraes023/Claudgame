package com.lucasmoraes.claudgame.ai

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import javax.net.ssl.SSLException

/**
 * Cliente do proxy seguro da IA. A chave Gemini fica no servidor, nunca no APK.
 * O endpoint vem de BuildConfig.AI_BACKEND_URL (Gradle: -PclaudgameAiBackendUrl=...).
 *
 * O quadro da tela chega como bytes JPEG em memória (antes era lido de um arquivo em cache que podia
 * estar sendo reescrito no mesmo instante). O callback sempre é entregue na thread principal.
 */
class GameAiClient(private val backendUrl: String) {
    private companion object {
        const val TAG = "ClaudGameAI"
        const val MAX_IMAGE_BYTES = 3_500_000
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(
        message: String,
        screenContext: String,
        trollEnabled: Boolean,
        jpeg: ByteArray?,
        callback: (Result<String>) -> Unit
    ) {
        try {
            executor.execute {
                val result = runCatching { request(message, screenContext, trollEnabled, jpeg) }
                mainHandler.post { callback(result) }
            }
        } catch (error: RejectedExecutionException) {
            callback(Result.failure(IllegalStateException("O cliente de IA já foi encerrado.", error)))
        }
    }

    private fun request(message: String, screenContext: String, trollEnabled: Boolean, jpeg: ByteArray?): String {
        require(backendUrl.isNotBlank()) { "Servidor da IA ainda não configurado." }
        val endpoint = backendUrl.trimEnd('/') + "/api/game/analyze"

        val payload = JSONObject()
            .put("message", message.take(1500))
            .put("screen_context", screenContext.take(2500))
            .put("troll_enabled", trollEnabled)
            .put("language", "pt-BR")
            .put("image_status", "unavailable")
        if (jpeg != null && jpeg.size in 1..MAX_IMAGE_BYTES) {
            payload.put("image_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            payload.put("mime_type", "image/jpeg")
            payload.put("image_status", "attached")
            Log.d(TAG, "Enviando imagem real à IA: ${jpeg.size} bytes")
        }
        val body = payload.toString().toByteArray(Charsets.UTF_8)

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = try {
                    JSONObject(text).optString("detail")
                } catch (_: JSONException) {
                    null
                }
                throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Servidor respondeu HTTP $status")
            }
            val reply = try {
                JSONObject(text).optString("reply").trim()
            } catch (error: JSONException) {
                throw IllegalStateException("Resposta inválida do servidor da IA.", error)
            }
            require(reply.isNotBlank()) { "A IA retornou uma resposta vazia." }
            return reply
        } catch (error: IOException) {
            throw IllegalStateException(describeNetworkError(error), error)
        } finally {
            connection.disconnect()
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}

internal fun describeNetworkError(error: IOException): String = when (error) {
    is UnknownHostException -> "Sem conexão com a internet."
    is SocketTimeoutException -> "O servidor da IA demorou demais para responder."
    is ConnectException -> "Não foi possível conectar ao servidor da IA."
    is SSLException -> "Falha de conexão segura com o servidor da IA."
    else -> "Falha de rede ao falar com a IA."
}