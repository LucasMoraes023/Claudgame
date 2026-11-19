package com.lucasmoraes.claudgame.companion

import com.lucasmoraes.claudgame.companion.ConversationMemory.Who
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TurnJsonTest {
    @Test
    fun buildsTheFullUserTurn() {
        val wav = byteArrayOf(1, 2, 3, 4)
        val jpeg = byteArrayOf(9, 8, 7)
        val json = JSONObject(
            TurnJson.build(
                ZuleideClient.Turn(
                    kind = ZuleideClient.Turn.USER, troll = true, wav = wav, jpeg = jpeg,
                    screenContext = "Texto visível na tela: Menu",
                    history = listOf(
                        ConversationMemory.Turn(Who.PLAYER, "oi"),
                        ConversationMemory.Turn(Who.ZULEIDE, "e aí!")
                    ),
                    idleSeconds = 12, screenStaticSeconds = 30
                )
            )
        )
        assertEquals("user", json.getString("kind"))
        assertTrue(json.getBoolean("troll"))
        assertEquals("audio/wav", json.getString("audio_mime_type"))
        assertArrayEquals(wav, Base64.getDecoder().decode(json.getString("audio_base64")))
        assertEquals("image/jpeg", json.getString("mime_type"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(json.getString("image_base64")))
        assertEquals("Texto visível na tela: Menu", json.getString("screen_context"))
        val history = json.getJSONArray("history")
        assertEquals("player", history.getJSONObject(0).getString("who"))
        assertEquals("zuleide", history.getJSONObject(1).getString("who"))
        assertEquals("e aí!", history.getJSONObject(1).getString("text"))
        assertEquals(12, json.getJSONObject("hints").getInt("idle_seconds"))
        assertEquals(30, json.getJSONObject("hints").getInt("screen_static_seconds"))
    }

    @Test
    fun proactiveTurnWithoutAudioOrHints() {
        val json = JSONObject(TurnJson.build(ZuleideClient.Turn(kind = ZuleideClient.Turn.PROACTIVE, troll = false)))
        assertEquals("proactive", json.getString("kind"))
        assertFalse(json.has("audio_base64"))
        assertFalse(json.has("image_base64"))
        assertFalse(json.has("message"))
        assertEquals(0, json.getJSONArray("history").length())
        assertEquals(0, json.getJSONObject("hints").length())
    }

    @Test
    fun escapesTextSafely() {
        val tricky = "aspas \" e \\ barra\nquebra"
        val json = JSONObject(
            TurnJson.build(
                ZuleideClient.Turn(
                    kind = ZuleideClient.Turn.USER, troll = false, message = tricky, screenContext = tricky,
                    history = listOf(ConversationMemory.Turn(Who.PLAYER, tricky))
                )
            )
        )
        assertEquals(tricky, json.getString("message"))
        assertEquals(tricky, json.getString("screen_context"))
        assertEquals(tricky, json.getJSONArray("history").getJSONObject(0).getString("text"))
    }

    @Test
    fun emptyBlobsAreOmitted() {
        val json = JSONObject(
            TurnJson.build(ZuleideClient.Turn(kind = ZuleideClient.Turn.USER, troll = false, wav = ByteArray(0), jpeg = ByteArray(0)))
        )
        assertFalse(json.has("audio_base64"))
        assertFalse(json.has("image_base64"))
    }
}
