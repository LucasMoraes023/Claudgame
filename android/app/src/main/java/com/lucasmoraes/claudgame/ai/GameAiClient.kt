package com.lucasmoraes.claudgame.ai

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.File
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Cliente do proxy seguro da IA. A chave Gemini fica no servidor, nunca no APK.
 * O endpoint pode ser informado em Gradle com -PclaudgameAiBackendUrl=...
 */
class GameAiClient(private val backendUrl: String) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun ask(
        message: String,
        screenContext: String,
        trollEnabled: Boolean,
        imagePath: String?,
        callback: (Result<String>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                require(backendUrl.isNotBlank()) {
                    "Servidor da IA ainda não configurado."
                }
                val endpoint = backendUrl.trimEnd('/') + "/api/game/analyze"
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    val payload = JSONObject()
                        .put("message", message.take(1500))
                        .put("screen_context", screenContext.take(2500))
                        .put("troll_enabled", trollEnabled)
                        .put("language", "pt-BR")
                    imagePath?.let { path ->
                        val imageFile = File(path)
                        if (imageFile.isFile && imageFile.length() in 1..3_500_000) {
                            payload.put("image_base64", Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP))
                            payload.put("mime_type", "image/jpeg")
                        }
                    }
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                        throw IllegalStateException(detail?.takeIf { it.isNotBlank() } ?: "Servidor respondeu HTTP $status")
                    }
                    val reply = JSONObject(body).optString("reply").trim()
                    require(reply.isNotBlank()) { "A IA retornou uma resposta vazia." }
                    reply
                } finally {
                    connection.disconnect()
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }
}
