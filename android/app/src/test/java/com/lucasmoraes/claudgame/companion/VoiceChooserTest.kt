package com.lucasmoraes.claudgame.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceChooserTest {
    private fun voice(
        name: String,
        quality: Int = 300,
        network: Boolean = false,
        country: String = "BR",
        language: String = "pt",
        features: Set<String> = emptySet()
    ) = VoiceInfo(name, language, country, quality, 200, network, features)

    @Test
    fun onlyInstalledBrazilianVoicesAreConsidered() {
        val all = listOf(
            voice("pt-br-x-a-local"),
            voice("pt-pt-x-b-local", country = "PT"),
            voice("en-us-x-c-local", country = "US", language = "en"),
            voice("pt-br-x-d-local", features = setOf("notInstalled"))
        )
        assertEquals(listOf("pt-br-x-a-local"), VoiceChooser.brazilian(all).map { it.name })
    }

    @Test
    fun prefersTheHighestQuality() {
        val voices = listOf(voice("local", quality = 300), voice("network", quality = 500, network = true), voice("high", quality = 400))
        assertEquals(listOf("network", "high", "local"), VoiceChooser.ranked(voices).map { it.name })
        assertEquals("network", VoiceChooser.choose(voices, null)?.name)
    }

    @Test
    fun explicitFemaleMarkersBeatHigherQualityMaleVoices() {
        val voices = listOf(
            voice("pt-br-male-1", quality = 500),
            voice("pt-br-female-1", quality = 300),
            voice("pt-br-x-neutral", quality = 400)
        )
        assertEquals("pt-br-female-1", VoiceChooser.choose(voices, null)?.name)
        // "female" não pode ser confundido com "male".
        assertEquals("pt-br-male-1", VoiceChooser.ranked(voices).last().name)
    }

    @Test
    fun marksInFeaturesCountToo() {
        val voices = listOf(voice("a", quality = 400), voice("b", quality = 300, features = setOf("female")))
        assertEquals("b", VoiceChooser.choose(voices, null)?.name)
    }

    @Test
    fun theVoicePickedByThePlayerWinsWhenInstalled() {
        val voices = listOf(voice("best", quality = 500), voice("mine", quality = 300))
        assertEquals("mine", VoiceChooser.choose(voices, "mine")?.name)
        assertEquals("best", VoiceChooser.choose(voices, "uninstalled")?.name)
    }

    @Test
    fun nextCyclesThroughTheRankedListAndWraps() {
        val voices = listOf(voice("c", quality = 100), voice("a", quality = 500), voice("b", quality = 300))
        assertEquals("b", VoiceChooser.next(voices, "a")?.name)
        assertEquals("c", VoiceChooser.next(voices, "b")?.name)
        assertEquals("a", VoiceChooser.next(voices, "c")?.name)
        assertEquals("a", VoiceChooser.next(voices, null)?.name) // sem voz atual, começa pela melhor? próxima da "-1"
    }

    @Test
    fun noVoicesMeansNoChoice() {
        assertNull(VoiceChooser.choose(emptyList(), null))
        assertNull(VoiceChooser.next(emptyList(), null))
        assertTrue(VoiceChooser.ranked(emptyList()).isEmpty())
    }

    @Test
    fun tiesAreBrokenByNameForAStableOrder() {
        val voices = listOf(voice("z"), voice("a"), voice("m"))
        assertEquals(listOf("a", "m", "z"), VoiceChooser.ranked(voices).map { it.name })
    }
}
