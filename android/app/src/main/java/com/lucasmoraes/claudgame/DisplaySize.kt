package com.lucasmoraes.claudgame

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/** Tamanho real da tela (inclui barras do sistema) na orientação ATUAL, em pixels. */
object DisplaySize {
    fun current(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }
}
