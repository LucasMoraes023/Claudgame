package com.lucasmoraes.claudgame

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências locais do ClaudGame: modo trolagem, fala espontânea, escuta automática, voz e último jogo.
 *
 * O Game Hub grava, o companheiro flutuante lê na hora de perguntar à IA, então a troca do modo
 * (no Hub ou direto no painel durante a partida) vale imediatamente, sem reiniciar a sessão.
 */
object ClaudGamePrefs {
    private const val FILE = "claudgame_prefs"
    private const val KEY_TROLL = "troll_enabled"
    private const val KEY_LAST_GAME = "last_game_package"
    private const val KEY_PROACTIVE = "proactive_enabled"
    private const val KEY_LISTENING = "listening_enabled"
    private const val KEY_VOICE = "voice_name"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isTrollEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TROLL, false)

    fun setTrollEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TROLL, enabled).apply()
    }

    fun lastGamePackage(context: Context): String? = prefs(context).getString(KEY_LAST_GAME, null)

    fun setLastGamePackage(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_LAST_GAME, packageName).apply()
    }

    /** A Zuleide comenta a partida por conta própria (padrão: ligado). */
    fun isProactiveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PROACTIVE, true)

    fun setProactiveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PROACTIVE, enabled).apply()
    }

    /** Escuta automática do microfone (padrão: ligada). Desligada = "mic mudo". */
    fun isListeningEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LISTENING, true)

    fun setListeningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LISTENING, enabled).apply()
    }

    /** Voz escolhida pelo jogador no Game Hub (nome da voz do TTS); null = a melhor voz pt-BR automática. */
    fun voiceName(context: Context): String? = prefs(context).getString(KEY_VOICE, null)

    fun setVoiceName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_VOICE, name).apply()
    }
}
