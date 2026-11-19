package com.lucasmoraes.claudgame.companion

import com.lucasmoraes.claudgame.companion.ProactivePlanner.Decision
import com.lucasmoraes.claudgame.companion.ProactivePlanner.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePlannerTest {
    private val ready = Input(
        nowMs = 100_000, troll = false, enabled = true, busy = false, quietUntilMs = 0, backoffUntilMs = 0,
        lastActivityMs = 0, screenReady = true, screenStaticMs = 0, commentedOnThisScreen = false
    )

    private fun waitReason(decision: Decision) = (decision as Decision.Wait).reason

    @Test
    fun firesWhenEverythingIsReady() {
        assertEquals(Decision.Fire, ProactivePlanner.decide(ready))
    }

    @Test
    fun staysQuietWhenDisabled() {
        assertEquals("desligado", waitReason(ProactivePlanner.decide(ready.copy(enabled = false))))
    }

    @Test
    fun respectsTheQuietRequestFromThePlayer() {
        val decision = ProactivePlanner.decide(ready.copy(quietUntilMs = 130_000))
        assertEquals("quieta a pedido", waitReason(decision))
        assertEquals(15_000L, (decision as Decision.Wait).retryInMs) // limitado a 15 s por checagem
    }

    @Test
    fun backsOffAfterQuotaOrNetworkErrors() {
        assertEquals("aguardando cota", waitReason(ProactivePlanner.decide(ready.copy(backoffUntilMs = 160_000))))
    }

    @Test
    fun neverTalksOverItselfOrThePlayer() {
        assertEquals("ocupada", waitReason(ProactivePlanner.decide(ready.copy(busy = true))))
    }

    @Test
    fun needsAValidScreen() {
        assertEquals("sem tela", waitReason(ProactivePlanner.decide(ready.copy(screenReady = false))))
    }

    @Test
    fun keepsAMinimumGapAfterAnySpeech() {
        val normal = ProactivePlanner.decide(ready.copy(lastActivityMs = 95_000))
        assertEquals("fala recente", waitReason(normal))
        assertEquals(13_000L, (normal as Decision.Wait).retryInMs) // 18 s - 5 s
        // O modo zueira aceita pausas bem menores.
        assertEquals(Decision.Fire, ProactivePlanner.decide(ready.copy(troll = true, lastActivityMs = 93_000)))
        assertEquals("fala recente", waitReason(ProactivePlanner.decide(ready.copy(troll = true, lastActivityMs = 97_000))))
    }

    @Test
    fun doesNotRepeatCommentsOnAStaticScreen() {
        val decision = ProactivePlanner.decide(ready.copy(screenStaticMs = 20_000, commentedOnThisScreen = true))
        assertEquals("tela parada, já comentei", waitReason(decision))
        // Mas comenta uma tela parada que ainda não foi comentada.
        assertEquals(Decision.Fire, ProactivePlanner.decide(ready.copy(screenStaticMs = 20_000, commentedOnThisScreen = false)))
    }

    @Test
    fun trollModeStillPesterThePlayerWhenTheScreenIsStuck() {
        val stuck = ready.copy(troll = true, screenStaticMs = 60_000, commentedOnThisScreen = true, lastActivityMs = 50_000)
        assertEquals(Decision.Fire, ProactivePlanner.decide(stuck))
        // Normal não: continua respeitando "já comentei".
        assertEquals("tela parada, já comentei", waitReason(ProactivePlanner.decide(stuck.copy(troll = false))))
    }

    @Test
    fun delaysAreShorterInTrollMode() {
        for (r in listOf(0.0, 0.25, 0.5, 0.99, 1.0)) {
            assertTrue(ProactivePlanner.nextDelayMs(false, r) in 28_000L..52_000L)
            assertTrue(ProactivePlanner.nextDelayMs(true, r) in 9_000L..19_000L)
        }
        assertTrue(ProactivePlanner.nextDelayMs(true, 1.0) < ProactivePlanner.nextDelayMs(false, 0.0))
    }
}
