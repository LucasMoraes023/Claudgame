package com.lucasmoraes.claudgame.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class ClaudGameOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: View? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    private fun showOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 15, 18, 28))
                cornerRadius = 28f
                setStroke(2, Color.argb(180, 100, 80, 255))
            }
        }

        val title = TextView(this).apply {
            text = "CLAUDGAME  •  IA"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        val status = TextView(this).apply {
            text = "● Observando a partida"
            textSize = 12f
            setTextColor(Color.rgb(100, 255, 180))
            setPadding(0, 8, 0, 8)
        }
        val troll = TextView(this).apply {
            text = "😈 Trolagem: ATIVA"
            textSize = 12f
            setTextColor(Color.rgb(255, 190, 90))
        }
        root.addView(title)
        root.addView(status)
        root.addView(troll)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 110
        }

        root.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - downX) > 4 || abs(event.rawY - downY) > 4) {
                            params.x = startX + (downX - event.rawX).toInt()
                            params.y = startY + (event.rawY - downY).toInt()
                            windowManager.updateViewLayout(root, params)
                        }
                        return true
                    }
                }
                return true
            }
        })

        windowManager.addView(root, params)
        panel = root
    }

    override fun onDestroy() {
        panel?.let { windowManager.removeView(it) }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
