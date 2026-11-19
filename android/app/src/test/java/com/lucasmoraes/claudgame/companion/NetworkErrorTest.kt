package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkErrorTest {
    @Test
    fun mapsKnownNetworkFailuresToReadableMessages() {
        assertEquals("Sem conexão com a internet.", describeNetworkError(UnknownHostException("x")))
        assertEquals("O servidor da IA demorou demais para responder.", describeNetworkError(SocketTimeoutException()))
        assertEquals("Não foi possível conectar ao servidor da IA.", describeNetworkError(ConnectException()))
        assertEquals("Falha de conexão segura com o servidor da IA.", describeNetworkError(SSLHandshakeException("x")))
    }

    @Test
    fun unknownIoErrorsGetGenericMessage() {
        assertEquals("Falha de rede ao falar com a IA.", describeNetworkError(IOException("boom")))
    }
}
