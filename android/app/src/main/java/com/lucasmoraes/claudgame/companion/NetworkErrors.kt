package com.lucasmoraes.claudgame.companion

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Mensagem curta e legível para falhas de rede. */
internal fun describeNetworkError(error: IOException): String = when (error) {
    is UnknownHostException -> "Sem conexão com a internet."
    is SocketTimeoutException -> "O servidor da IA demorou demais para responder."
    is ConnectException -> "Não foi possível conectar ao servidor da IA."
    is SSLException -> "Falha de conexão segura com o servidor da IA."
    else -> "Falha de rede ao falar com a IA."
}
