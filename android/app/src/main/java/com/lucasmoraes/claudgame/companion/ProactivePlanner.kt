package com.lucasmoraes.claudgame.companion

/**
 * Decide QUANDO a Zuleide comenta por conta própria. Lógica pura e testável.
 *
 * Modo normal: uma orientação tática a cada ~12–20 s, quando houver contexto útil.
 * Modo zueira: a cada ~9–19 s e, se a tela ficar parada, ainda cobra o jogador de tempos em tempos.
 */
object ProactivePlanner {
    data class Input(
        val nowMs: Long,
        val troll: Boolean,
        val enabled: Boolean,
        val busy: Boolean,
        val quietUntilMs: Long,
        val backoffUntilMs: Long,
        val lastActivityMs: Long,
        val screenReady: Boolean,
        val screenStaticMs: Long,
        val commentedOnThisScreen: Boolean
    )

    sealed class Decision {
        object Fire : Decision()
        class Wait(val reason: String, val retryInMs: Long) : Decision()
    }

    private const val MIN_GAP_NORMAL_MS = 10_000L
    private const val MIN_GAP_TROLL_MS = 6_000L
    private const val STATIC_TAUNT_AFTER_MS = 45_000L
    private const val STATIC_TAUNT_GAP_MS = 30_000L

    fun minGapMs(troll: Boolean): Long = if (troll) MIN_GAP_TROLL_MS else MIN_GAP_NORMAL_MS

    /** Espera até a próxima tentativa; [random] em 0.0..1.0. */
    fun nextDelayMs(troll: Boolean, random: Double): Long {
        val (min, max) = if (troll) 9_000L to 19_000L else 12_000L to 20_000L
        return min + ((max - min) * random.coerceIn(0.0, 1.0)).toLong()
    }

    fun decide(input: Input): Decision {
        val now = input.nowMs
        if (!input.enabled) return Decision.Wait("desligado", 5_000)
        if (now < input.quietUntilMs) return Decision.Wait("quieta a pedido", (input.quietUntilMs - now).coerceIn(1_000, 15_000))
        if (now < input.backoffUntilMs) return Decision.Wait("aguardando cota", (input.backoffUntilMs - now).coerceIn(1_000, 30_000))
        if (input.busy) return Decision.Wait("ocupada", 2_000)
        if (!input.screenReady) return Decision.Wait("sem tela", 5_000)

        val gap = now - input.lastActivityMs
        val minGap = minGapMs(input.troll)
        if (gap < minGap) return Decision.Wait("fala recente", minGap - gap)

        val staticScreen = input.screenStaticMs >= 12_000
        if (staticScreen && input.commentedOnThisScreen) {
            val canTaunt = input.troll && input.screenStaticMs >= STATIC_TAUNT_AFTER_MS && gap >= STATIC_TAUNT_GAP_MS
            if (!canTaunt) return Decision.Wait("tela parada, já comentei", if (input.troll) 10_000 else 20_000)
        }
        return Decision.Fire
    }
}
