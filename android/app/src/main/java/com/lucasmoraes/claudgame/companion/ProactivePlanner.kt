package com.lucasmoraes.claudgame.companion

/** Build Android atualizado para acompanhar o Worker ZAYRO publicado. */
/**
 * Decide QUANDO a Zuleide comenta por conta própria.
 *
 * Modo normal: análise a cada ~8–14 s, antecipada quando a tela muda.
 * Modo zueira: análise a cada ~8–16 s, sem repetir observações em tela parada.
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

    private const val MIN_GAP_NORMAL_MS = 8_000L
    private const val MIN_GAP_TROLL_MS = 6_000L

    fun minGapMs(troll: Boolean): Long = if (troll) MIN_GAP_TROLL_MS else MIN_GAP_NORMAL_MS

    /** Espera até a próxima tentativa; [random] em 0.0..1.0. */
    fun nextDelayMs(troll: Boolean, random: Double): Long {
        val (min, max) = if (troll) 8_000L to 16_000L else 8_000L to 14_000L
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
        if (staticScreen && input.commentedOnThisScreen && gap < 18_000L) {
            return Decision.Wait("evitando repetição em tela estática", 18_000L - gap)
        }
        return Decision.Fire
    }
}
