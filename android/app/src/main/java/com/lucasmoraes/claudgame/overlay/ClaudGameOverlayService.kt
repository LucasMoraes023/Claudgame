package com.lucasmoraes.claudgame.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lucasmoraes.claudgame.BuildConfig
import com.lucasmoraes.claudgame.ClaudGamePrefs
import com.lucasmoraes.claudgame.DisplaySize
import com.lucasmoraes.claudgame.MainActivity
import com.lucasmoraes.claudgame.R
import com.lucasmoraes.claudgame.capture.CaptureBridge
import com.lucasmoraes.claudgame.capture.ScreenCaptureService
import com.lucasmoraes.claudgame.companion.Persona
import com.lucasmoraes.claudgame.companion.ZuleideBrain
import kotlin.math.abs

/**
 * Painel flutuante da Zuleide: uma pílula pequena (bolinha de estado, nome e três botões) que fica sobre o jogo.
 * Não mostra o texto do que é falado. Toda a conversa acontece por voz, via [ZuleideBrain]:
 *  - toque no nome = "falar agora" (interrompe a fala e escuta);  arrastar = mover;
 *  - 🎙 liga/desliga a escuta automática (🔇 = mic mudo);  😈 liga/desliga o modo zueira;  ✕ encerra.
 * Roda como serviço em primeiro plano (tipo microfone), exigência do Android para manter o microfone aberto.
 */
class ClaudGameOverlayService : Service(), ZuleideBrain.Ui {
    companion object {
        const val ACTION_STOP = "com.lucasmoraes.claudgame.action.STOP_SESSION"
        const val ACTION_TOGGLE_MIC = "com.lucasmoraes.claudgame.action.TOGGLE_MIC"
        private const val TAG = "ClaudGameOverlay"
        private const val CHANNEL = "claudgame_overlay"
        private const val NOTIFICATION_ID = 1002
        private const val FLASH_MS = 4_000L
        private const val COLOR_FLASH = 0xFFFB923C.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var dot: GradientDrawable? = null
    private var stateText: TextView? = null
    private var micButton: TextView? = null
    private var trollButton: TextView? = null

    private var brain: ZuleideBrain? = null
    private var currentState = ZuleideBrain.State.STARTING
    private var flashText: String? = null
    private var lastCaptureStatus: String? = null
    private var serviceDestroyed = false

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false

    private val snapshotListener = CaptureBridge.Listener { snapshot -> onSnapshot(snapshot) }
    private val clearFlashRunnable = Runnable {
        flashText = null
        renderState()
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate() {
        super.onCreate()
        createChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        CaptureBridge.addListener(snapshotListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MIC -> {
                if (panel == null) stopSelf() else toggleMic()
                return START_NOT_STICKY
            }
        }
        try {
            startInForeground()
        } catch (error: Exception) {
            Log.e(TAG, "Não foi possível entrar em primeiro plano", error)
            Toast.makeText(
                this,
                "Não foi possível iniciar a ${Persona.NAME} (${error.javaClass.simpleName}).",
                Toast.LENGTH_LONG
            ).show()
            stopSession()
            return START_NOT_STICKY
        }
        if (panel == null && !showOverlay()) {
            Toast.makeText(
                this,
                "Não foi possível exibir o painel flutuante. Confira a permissão de sobreposição.",
                Toast.LENGTH_LONG
            ).show()
            stopSession()
            return START_NOT_STICKY
        }
        if (brain == null) {
            brain = ZuleideBrain(this, BuildConfig.AI_BACKEND_URL, this).also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Build.VERSION.SDK_INT >= 29 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Encerra a sessão inteira (Zuleide + painel + captura). */
    private fun stopSession() {
        CaptureBridge.sessionActive = false
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.post { clampPanel() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Game Hub removido; encerrando a sessão")
        stopSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceDestroyed = true
        CaptureBridge.removeListener(snapshotListener)
        mainHandler.removeCallbacksAndMessages(null)
        brain?.stop()
        brain = null
        panel?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao remover o painel", error)
            }
        }
        panel = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ painel

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpF(value: Int): Float = value * resources.displayMetrics.density

    private fun showOverlay(): Boolean {
        val manager = windowManager ?: return false
        val muted = Color.rgb(155, 164, 184)

        val statusDot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(156, 163, 175))
        }
        val dotView = View(this).apply { background = statusDot }
        val name = TextView(this).apply {
            text = Persona.NAME
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        val state = TextView(this).apply {
            textSize = 10f
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(96)
            minWidth = dp(54)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(name)
        texts.addView(state)

        // Bloco da esquerda: arrastar move o painel; tocar = "falar agora".
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(6), 0)
            contentDescription = "Falar agora com a ${Persona.NAME}. Arraste para mover."
        }
        identity.addView(dotView, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        identity.addView(texts)

        fun button(label: String, description: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
            setOnClickListener { onClick() }
        }
        val mic = button("🎙", "Ligar ou desligar a escuta automática") { toggleMic() }
        val troll = button("😈", "Ligar ou desligar o modo zueira") { toggleTroll() }
        val close = button("✕", "Encerrar a ${Persona.NAME}") { stopSession() }.apply { setTextColor(muted) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(6), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(236, 15, 18, 28))
                cornerRadius = dpF(22)
                setStroke(dp(1), Color.argb(180, 100, 80, 255))
            }
        }
        root.addView(identity)
        root.addView(mic, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(troll, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))

        dot = statusDot
        stateText = state
        micButton = mic
        trollButton = troll

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(10)
            y = dp(88)
        }
        identity.setOnTouchListener(dragListener(params))

        updateButtons()
        renderState()
        return try {
            manager.addView(root, params)
            panel = root
            panelParams = params
            true
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao exibir a sobreposição", error)
            dot = null
            stateText = null
            micButton = null
            trollButton = null
            false
        }
    }

    private fun dragListener(params: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    if (moved) {
                        // Gravidade TOP|RIGHT: x = distância até a borda direita (dedo para a direita = x menor);
                        // y = distância até o topo (dedo para baixo = y maior).
                        params.x = startX + (downX - event.rawX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        clampPanel()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) brain?.talkNow()
                    true
                }
                else -> true
            }
        }
    }

    /** Mantém o painel dentro da tela e aplica a posição. */
    private fun clampPanel() {
        val view = panel ?: return
        val params = panelParams ?: return
        val manager = windowManager ?: return
        val (screenWidth, screenHeight) = DisplaySize.current(this)
        params.x = params.x.coerceIn(0, (screenWidth - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screenHeight - view.height).coerceAtLeast(0))
        try {
            manager.updateViewLayout(view, params)
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao mover o painel", error)
        }
    }

    // ------------------------------------------------------------------ botões

    private fun toggleMic() {
        val enable = !ClaudGamePrefs.isListeningEnabled(this)
        val current = brain
        if (current != null) current.setListeningEnabled(enable) else ClaudGamePrefs.setListeningEnabled(this, enable)
        updateButtons()
        refreshNotification()
    }

    private fun toggleTroll() {
        val enable = !ClaudGamePrefs.isTrollEnabled(this)
        ClaudGamePrefs.setTrollEnabled(this, enable)
        updateButtons()
        brain?.onPreferencesChanged()
        onFlash(if (enable) "zueira ligada" else "zueira desligada")
    }

    private fun updateButtons() {
        val listening = ClaudGamePrefs.isListeningEnabled(this)
        micButton?.apply {
            text = if (listening) "🎙" else "🔇"
            alpha = if (listening) 1f else 0.6f
        }
        trollButton?.alpha = if (ClaudGamePrefs.isTrollEnabled(this)) 1f else 0.35f
    }

    // ------------------------------------------------------------------ estado exibido (ZuleideBrain.Ui)

    override fun onState(state: ZuleideBrain.State) {
        if (serviceDestroyed) return
        currentState = state
        renderState()
    }

    override fun onFlash(text: String) {
        if (serviceDestroyed) return
        flashText = text
        mainHandler.removeCallbacks(clearFlashRunnable)
        mainHandler.postDelayed(clearFlashRunnable, FLASH_MS)
        renderState()
    }

    private fun renderState() {
        val flash = flashText
        val (label, color) = when {
            flash != null -> flash to COLOR_FLASH
            else -> when (currentState) {
                ZuleideBrain.State.LISTENING -> "ouvindo" to 0xFF4ADE80.toInt()
                ZuleideBrain.State.HEARING -> "te ouvindo…" to 0xFFFACC15.toInt()
                ZuleideBrain.State.THINKING -> "pensando…" to 0xFF60A5FA.toInt()
                ZuleideBrain.State.SPEAKING -> "falando" to 0xFFA78BFA.toInt()
                ZuleideBrain.State.MUTED -> "mic mudo" to 0xFF9CA3AF.toInt()
                ZuleideBrain.State.NO_MIC -> "sem mic" to 0xFFF87171.toInt()
                ZuleideBrain.State.STARTING -> "iniciando" to 0xFF9CA3AF.toInt()
            }
        }
        stateText?.text = label
        dot?.setColor(color)
    }

    private fun onSnapshot(snapshot: CaptureBridge.Snapshot) {
        if (serviceDestroyed) return
        brain?.onSnapshot(snapshot)
        if (snapshot.status != lastCaptureStatus) {
            lastCaptureStatus = snapshot.status
            when (snapshot.status) {
                CaptureBridge.STATUS_BLACK -> onFlash("tela preta")
                CaptureBridge.STATUS_UNAVAILABLE -> onFlash("sem tela")
                CaptureBridge.STATUS_STOPPED -> onFlash("captura encerrada")
            }
        }
    }

    // ------------------------------------------------------------------ notificação

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openHub = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val toggleMic = PendingIntent.getService(
            this,
            2,
            Intent(this, ClaudGameOverlayService::class.java).setAction(ACTION_TOGGLE_MIC),
            flags
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ClaudGameOverlayService::class.java).setAction(ACTION_STOP),
            flags
        )
        val listening = ClaudGamePrefs.isListeningEnabled(this)
        val icon = Icon.createWithResource(this, R.drawable.ic_stat_claudgame)
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("${Persona.NAME} está com você")
            .setContentText(
                if (listening) "Ouvindo o microfone e comentando a partida" else "Microfone silenciado • ela segue comentando"
            )
            .setSmallIcon(R.drawable.ic_stat_claudgame)
            .setContentIntent(openHub)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(icon, if (listening) "Silenciar mic" else "Ativar mic", toggleMic).build())
            .addAction(Notification.Action.Builder(icon, "Encerrar", stop).build())
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun refreshNotification() {
        try {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao atualizar a notificação", error)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "ClaudGame • ${Persona.NAME}", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
