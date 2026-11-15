package com.lucasmoraes.claudgame

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferências locais do ClaudGame: modo trolagem e último jogo escolhido.
 *
 * O Game Hub grava, o companheiro flutuante lê na hora de perguntar à IA, então a troca do modo
 * (no Hub ou direto no painel durante a partida) vale imediatamente, sem reiniciar a sessão.
 */
object ClaudGamePrefs {
    private const val FILE = "claudgame_prefs"
    private const val KEY_TROLL = "troll_enabled"
    private const val KEY_LAST_GAME = "last_game_package"

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
}