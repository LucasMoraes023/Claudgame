package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test
    fun removesEmojisAndMarkdown() {
        assertEquals("Mandou bem", SpeechText.clean("🔥 **Mandou bem** 😎"))
        assertEquals("Item um item dois", SpeechText.clean("Item um\nitem dois"))
        assertEquals("código ok", SpeechText.clean("`código` _ok_"))
    }

    @Test
    fun removesLinks() {
        assertEquals("Veja aqui", SpeechText.clean("Veja https://exemplo.com/a?b=1 aqui"))
    }

    @Test
    fun laughterIsMadePronounceable() {
        assertEquals("ha ha ha", SpeechText.clean("kkkkkk"))
        assertEquals("Boa ha ha ha", SpeechText.clean("Boa KKKK"))
        assertEquals("ha ha ha", SpeechText.clean("hahaha"))
        assertEquals("ha ha", SpeechText.clean("rsrsrs"))
    }

    @Test
    fun normalWordsAreUntouched() {
        assertEquals("Karate kids vai", SpeechText.clean("Karate kids vai")) // "k" no meio da palavra não é risada
        assertEquals("Tá chovendo, né? Bora!", SpeechText.clean("Tá chovendo, né? Bora!"))
    }

    @Test
    fun collapsesWhitespace() {
        assertEquals("a b c", SpeechText.clean("  a \n  b\t c  "))
    }
}
