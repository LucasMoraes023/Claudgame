package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class SseReaderTest {
    private fun read(text: String, stopAfter: Int = Int.MAX_VALUE): List<String> {
        val got = ArrayList<String>()
        SseReader.read(BufferedReader(StringReader(text))) { payload ->
            got.add(payload)
            got.size < stopAfter
        }
        return got
    }

    @Test
    fun deliversOnlyDataLines() {
        val text = ": comentário\n\nevent: ping\ndata: {\"t\":\"oi\"}\n\ndata:{\"done\":true}\n\n"
        assertEquals(listOf("{\"t\":\"oi\"}", "{\"done\":true}"), read(text))
    }

    @Test
    fun toleratesCrLfAndEmptyData() {
        assertEquals(listOf("a", "b"), read("data: a\r\n\r\ndata:\r\ndata: b\r\n"))
    }

    @Test
    fun stopsWhenTheCallbackSaysSo() {
        assertEquals(listOf("1", "2"), read("data: 1\ndata: 2\ndata: 3\n", stopAfter = 2))
    }

    @Test
    fun endsAtEndOfStream() {
        assertEquals(emptyList<String>(), read(""))
    }
}
