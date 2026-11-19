package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyStreamParserTest {
    @Test
    fun deliversSentencesAsSoonAsTheyAreComplete() {
        val parser = ReplyStreamParser()
        assertEquals(emptyList<String>(), parser.feed("Opa, "))
        assertEquals(listOf("Opa, tudo bem?"), parser.feed("tudo bem? Bora jo"))
        // Pontuação no fim do buffer é ambígua (pode vir "..." ou um número): espera o próximo pedaço.
        assertEquals(emptyList<String>(), parser.feed("gar!"))
        assertEquals(listOf("Bora jogar!"), parser.finish())
        assertEquals("Opa, tudo bem? Bora jogar!", parser.spokenText)
    }

    @Test
    fun silenceTokenArrivingInPiecesProducesNothing() {
        val parser = ReplyStreamParser()
        assertEquals(emptyList<String>(), parser.feed("[SILEN"))
        assertFalse(parser.isSilent)
        assertEquals(emptyList<String>(), parser.feed("CIO]"))
        assertTrue(parser.isSilent)
        assertEquals(emptyList<String>(), parser.finish())
        assertEquals("", parser.spokenText)
    }

    @Test
    fun silenceTokenVariantsAreAccepted() {
        for (token in listOf("[silencio]", "[SILÊNCIO]", "  [Silencio]  ")) {
            val parser = ReplyStreamParser()
            parser.feed(token)
            parser.finish()
            assertTrue(token, parser.isSilent)
        }
        val plain = ReplyStreamParser()
        plain.feed("SILENCIO")
        plain.finish()
        assertTrue(plain.isSilent)
    }

    @Test
    fun metadataLinesAreNeverSpokenAndAreParsed() {
        val parser = ReplyStreamParser()
        val spoken = parser.feed("Beleza!\n###OUVI: oi zuleide\n###CALAR: 5") + parser.finish()
        assertEquals(listOf("Beleza!"), spoken)
        assertEquals("oi zuleide", parser.heard)
        assertEquals(5, parser.quietMinutes)
        assertEquals("Beleza!", parser.spokenText)
    }

    @Test
    fun partialDelimiterAtTheEndIsHeldBack() {
        val parser = ReplyStreamParser()
        assertEquals(listOf("Fala aí."), parser.feed("Fala aí. #"))
        assertEquals(emptyList<String>(), parser.feed("##OUVI: oi"))
        assertEquals(emptyList<String>(), parser.finish())
        assertEquals("oi", parser.heard)
    }

    @Test
    fun metadataOnTheSameLineIsHandled() {
        val parser = ReplyStreamParser()
        val spoken = parser.feed("Tá bom, fico quieta.###OUVI: cala a boca###CALAR: 10") + parser.finish()
        assertEquals(listOf("Tá bom, fico quieta."), spoken)
        assertEquals("cala a boca", parser.heard)
        assertEquals(10, parser.quietMinutes)
    }

    @Test
    fun quietRequestCanBeCancelledWithZero() {
        val parser = ReplyStreamParser()
        parser.feed("Voltei!\n###CALAR: 0")
        parser.finish()
        assertEquals(0, parser.quietMinutes)
    }

    @Test
    fun longSentencesAreSplitAtACommaToStartSpeakingEarlier() {
        val parser = ReplyStreamParser()
        val text = "Esse é um comentário bem comprido que vai passando dos sessenta caracteres, " +
            "depois continua falando mais um monte de coisa sem parar"
        val early = parser.feed(text)
        assertEquals(1, early.size)
        assertTrue(early[0], early[0].endsWith("caracteres,"))
        assertEquals(listOf("depois continua falando mais um monte de coisa sem parar"), parser.finish())
    }

    @Test
    fun shortSentenceWithCommaIsNotSplit() {
        val parser = ReplyStreamParser()
        assertEquals(emptyList<String>(), parser.feed("Olha, calma"))
        assertEquals(listOf("Olha, calma"), parser.finish())
    }

    @Test
    fun speechIsCleanedBeforeDelivery() {
        val parser = ReplyStreamParser()
        val spoken = parser.feed("Boa! 😂 **Mandou bem** kkkkk") + parser.finish()
        assertEquals(listOf("Boa!", "Mandou bem ha ha ha"), spoken)
    }

    @Test
    fun bracketedTextThatIsNotSilenceIsSpoken() {
        val parser = ReplyStreamParser()
        val spoken = parser.feed("[Risos] Boa jogada! ") + parser.finish()
        assertFalse(parser.isSilent)
        assertEquals(listOf("[Risos] Boa jogada!"), spoken)
    }

    @Test
    fun numbersAndEllipsisDoNotBreakSentences() {
        val parser = ReplyStreamParser()
        val spoken = parser.feed("Faltam 3.5 minutos... Corre! ") + parser.finish()
        assertEquals(listOf("Faltam 3.5 minutos...", "Corre!"), spoken)
    }

    @Test
    fun emptyAndWhitespaceOnlyReplyDeliversNothing() {
        val parser = ReplyStreamParser()
        assertEquals(emptyList<String>(), parser.feed("   \n "))
        assertEquals(emptyList<String>(), parser.finish())
        assertNull(parser.heard)
        assertNull(parser.quietMinutes)
    }

    @Test
    fun streamingInTinyChunksGivesTheSameResultAsOneChunk() {
        val full = "Opa! Vamos lá, campeão. Cuidado com o chefe, ele é forte!\n###OUVI: bora"
        val oneShot = ReplyStreamParser()
        val expected = oneShot.feed(full) + oneShot.finish()
        val tiny = ReplyStreamParser()
        val actual = ArrayList<String>()
        for (char in full) actual.addAll(tiny.feed(char.toString()))
        actual.addAll(tiny.finish())
        assertEquals(expected, actual)
        assertEquals("bora", tiny.heard)
    }
}
