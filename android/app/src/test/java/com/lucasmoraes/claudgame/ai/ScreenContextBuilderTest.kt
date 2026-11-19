package com.lucasmoraes.claudgame.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextBuilderTest {
    @Test
    fun blankTextProducesNoContext() {
        assertEquals("", ScreenContextBuilder.build(""))
        assertEquals("", ScreenContextBuilder.build("   \n\t  "))
    }

    @Test
    fun plainTextIsReportedAsVisibleText() {
        assertEquals("Texto visível na tela: Vida 100 Munição 30", ScreenContextBuilder.build("Vida 100\nMunição   30"))
    }

    @Test
    fun detectsDefeat() {
        val context = ScreenContextBuilder.build("GAME OVER - tente novamente")
        assertTrue(context, context.contains("partida terminou em derrota"))
    }

    @Test
    fun detectsVictoryWithAndWithoutAccent() {
        assertTrue(ScreenContextBuilder.build("VITÓRIA!").contains("houve vitória"))
        assertTrue(ScreenContextBuilder.build("vitoria").contains("houve vitória"))
        assertTrue(ScreenContextBuilder.build("Victory").contains("houve vitória"))
    }

    @Test
    fun detectsLoadingMenuAndObjective() {
        assertTrue(ScreenContextBuilder.build("Carregando...").contains("carregando"))
        assertTrue(ScreenContextBuilder.build("PAUSADO").contains("menu ou pausa"))
        assertTrue(ScreenContextBuilder.build("Missão: entregar o pacote").contains("objetivo ou missão"))
    }

    @Test
    fun combinesSeveralEvents() {
        val context = ScreenContextBuilder.build("Game over. Loading")
        assertTrue(context.contains("derrota"))
        assertTrue(context.contains("carregando"))
        assertTrue(context.contains("; "))
    }

    @Test
    fun visibleTextIsTruncated() {
        val long = "a".repeat(1000)
        val context = ScreenContextBuilder.build(long)
        assertEquals("Texto visível na tela: " + "a".repeat(600), context)
        assertFalse(context.contains("a".repeat(601)))
    }
}
