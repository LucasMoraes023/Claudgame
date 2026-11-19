package com.lucasmoraes.claudgame.companion

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Fala com o Worker da Zuleide (cloudflare-worker/src/zuleide.js): manda a voz do jogador (WAV) e/ou a tela
 * (JPEG) e recebe a resposta em STREAMING, frase a frase. Os callbacks sempre rodam na thread principal.
 */
class ZuleideClient(private val baseUrl: String) {
    class Turn(
        val kind: String,
        val troll: Boolean,
        val wav: ByteArray? = null,
        val jpeg: ByteArray? = null,
        val screenContext: String = "",
        val history: List<ConversationMemory.Turn> = emptyList(),
        val idleSeconds: Int? = null,
        val screenStaticSeconds: Int? = null,
        val message: String? = null
    ) {
        companion object {
            const val USER = "user"
            const val PROACTIVE = "proactive"
        }
    }

    class Result(
        val silent: Boolean,
        val heard: String?,
        val quietMinutes: Int?,
        val spokenText: String,
        val blocked: Boolean
    )

    class Failure(val message: String, val kind: Kind) {
        enum class Kind { OUTDATED_SERVER, NOT_CONFIGURED, QUOTA, NETWORK, SERVER }
    }

    interface Listener {
        fun onSentence(text: String)
        fun onFinished(result: Result)
        fun onFailure(failure: Failure)
    }

    enum class ServerStatus { OK, OUTDATED, NOT_CONFIGURED, UNREACHABLE }

    class Call {
        @Volatile
        var cancelled = false
            private set

        @Volatile
        internal var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            try {
                connection?.disconnect() // desbloqueia a leitura da thread de rede
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val TAG = "ZuleideClient"
        const val MIN_PROTOCOL_VERSION = 2
    }

    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeCalls = CopyOnWriteArraySet<Call>()

    private fun endpoint(path: String) = baseUrl.trim().trimEnd('/') + path

    /** Confere se o Worker publicado entende o protocolo novo (e já aquece a conexão TLS). */
    fun checkVersion(onResult: (ServerStatus) -> Unit) {
        try {
            executor.execute {
                val status = fetchVersion()
                mainHandler.post { onResult(status) }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun fetchVersion(): ServerStatus {
        if (baseUrl.isBlank()) return ServerStatus.UNREACHABLE
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint("/api/zuleide/version")).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            when {
                code == 404 -> ServerStatus.OUTDATED
                code !in 200..299 -> ServerStatus.UNREACHABLE
                else -> {
                    val body = connection.inputStream.use { readAll(it) }
                    val json = JSONObject(body)
                    when {
                        json.optInt("version", 0) < MIN_PROTOCOL_VERSION -> ServerStatus.OUTDATED
                        !json.optBoolean("configured", true) -> ServerStatus.NOT_CONFIGURED
                        else -> ServerStatus.OK
                    }
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Servidor inacessível: " + describeNetworkError(error))
            ServerStatus.UNREACHABLE
        } catch (error: JSONException) {
            ServerStatus.OUTDATED
        } finally {
            connection?.disconnect()
        }
    }

    fun start(turn: Turn, listener: Listener): Call {
        val call = Call()
        activeCalls.add(call)
        try {
            executor.execute {
                try {
                    run(call, turn, listener)
                } finally {
                    activeCalls.remove(call)
                }
            }
        } catch (_: RejectedExecutionException) {
            activeCalls.remove(call)
            call.cancel()
        }
        return call
    }

    private fun run(call: Call, turn: Turn, listener: Listener) {
        val parser = ReplyStreamParser()
        var blocked = false
        var failure: Failure? = null
        var sawDone = false
        var sentences = 0

        fun deliver(list: List<String>) {
            for (sentence in list) {
                sentences++
                mainHandler.post { if (!call.cancelled) listener.onSentence(sentence) }
            }
        }

        try {
            if (baseUrl.isBlank()) throw IllegalStateException("Servidor da IA ainda não configurado.")
            val body = TurnJson.build(turn).toByteArray(Charsets.UTF_8)
            val connection = URL(endpoint("/api/zuleide/turn")).openConnection() as HttpURLConnection
            call.connection = connection
            if (call.cancelled) return
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            // Sem compressão: o gzip seguraria o início da resposta e atrasaria a primeira frase.
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.outputStream.use { it.write(body) }

            val status = connection.responseCode
            if (status !in 200..299) {
                failure = failureFromResponse(status, connection.errorStream?.use { readAll(it) }.orEmpty())
            } else {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                    SseReader.read(reader) { payload ->
                        if (call.cancelled) return@read false
                        val event = try {
                            JSONObject(payload)
                        } catch (_: JSONException) {
                            return@read true
                        }
                        when {
                            event.has("t") -> deliver(parser.feed(event.optString("t")))
                            event.optBoolean("blocked") -> blocked = true
                            event.has("error") -> {
                                failure = Failure(event.optString("error", "Falha na IA"), Failure.Kind.SERVER)
                                return@read false
                            }
                            event.optBoolean("done") -> {
                                sawDone = true
                                return@read false
                            }
                        }
                        true
                    }
                }
            }
        } catch (error: IOException) {
            if (!call.cancelled) failure = Failure(describeNetworkError(error), Failure.Kind.NETWORK)
        } catch (error: Exception) {
            Log.w(TAG, "Falha no turno", error)
            if (!call.cancelled) failure = Failure(error.message ?: "Falha na IA", Failure.Kind.SERVER)
        } finally {
            try {
                call.connection?.disconnect()
            } catch (_: Exception) {
            }
        }

        if (call.cancelled) return
        val problem = failure
        if (problem != null) {
            mainHandler.post { if (!call.cancelled) listener.onFailure(problem) }
            return
        }
        deliver(parser.finish())
        if (!sawDone && sentences == 0 && !parser.isSilent && !blocked) {
            mainHandler.post {
                if (!call.cancelled) listener.onFailure(Failure("Conexão interrompida.", Failure.Kind.NETWORK))
            }
            return
        }
        val result = Result(
            silent = parser.isSilent || (sentences == 0),
            heard = parser.heard,
            quietMinutes = parser.quietMinutes,
            spokenText = parser.spokenText,
            blocked = blocked && sentences == 0
        )
        mainHandler.post { if (!call.cancelled) listener.onFinished(result) }
    }

    private fun failureFromResponse(status: Int, body: String): Failure {
        val detail = try {
            JSONObject(body).optString("detail")
        } catch (_: JSONException) {
            ""
        }
        return when {
            status == 404 -> Failure("Servidor antigo ou endereço errado.", Failure.Kind.OUTDATED_SERVER)
            status == 429 -> Failure(detail.ifBlank { "Cota do Gemini esgotada." }, Failure.Kind.QUOTA)
            status == 503 || detail.contains("Chave Gemini", ignoreCase = true) ->
                Failure(detail.ifBlank { "Chave Gemini não configurada." }, Failure.Kind.NOT_CONFIGURED)
            else -> Failure(detail.ifBlank { "Servidor respondeu HTTP $status" }, Failure.Kind.SERVER)
        }
    }

    private fun readAll(stream: InputStream): String =
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun close() {
        for (call in activeCalls) call.cancel()
        activeCalls.clear()
        executor.shutdownNow()
    }
}

/** Monta o JSON do pedido (ver buildGeminiRequest no Worker). */
internal object TurnJson {
    fun build(turn: ZuleideClient.Turn): String {
        val json = JSONObject()
            .put("kind", turn.kind)
            .put("troll", turn.troll)
            .put("screen_context", turn.screenContext.take(2500))
        turn.message?.takeIf { it.isNotBlank() }?.let { json.put("message", it.take(1500)) }
        turn.wav?.takeIf { it.isNotEmpty() }?.let {
            json.put("audio_base64", Base64.getEncoder().encodeToString(it))
            json.put("audio_mime_type", "audio/wav")
        }
        turn.jpeg?.takeIf { it.isNotEmpty() }?.let {
            json.put("image_base64", Base64.getEncoder().encodeToString(it))
            json.put("mime_type", "image/jpeg")
        }
        val history = JSONArray()
        for (item in turn.history) {
            history.put(
                JSONObject()
                    .put("who", if (item.who == ConversationMemory.Who.PLAYER) "player" else "zuleide")
                    .put("text", item.text)
            )
        }
        json.put("history", history)
        val hints = JSONObject()
        turn.idleSeconds?.let { hints.put("idle_seconds", it) }
        turn.screenStaticSeconds?.let { hints.put("screen_static_seconds", it) }
        json.put("hints", hints)
        return json.toString()
    }
}
