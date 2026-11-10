package com.lucasmoraes.claudgame.overlay

import android.Manifest
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lucasmoraes.claudgame.ai.NativeVoiceConfig
import com.lucasmoraes.claudgame.ai.GameAiClient
import com.lucasmoraes.claudgame.BuildConfig
import kotlin.math.abs

class ClaudGameOverlayService : Service() {
    companion object {
        const val EXTRA_TROLL = "troll"
        private const val CHANNEL = "claudgame_overlay"
        private const val NOTIFICATION_ID = 1002
    }

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognitionInProgress = false
    private var recognitionRetryCount = 0
    private var serviceDestroyed = false
    private var receiverRegistered = false
    private var trollEnabled = false
    private var latestScreenContext = ""
    private var latestScreenFramePath: String? = null
    private lateinit var statusText: TextView
    private lateinit var talkButton: Button
    private val aiClient by lazy { GameAiClient(BuildConfig.AI_BACKEND_URL) }

    private val screenContextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (serviceDestroyed || !::statusText.isInitialized) return
            val value = intent?.getStringExtra("context").orEmpty()
            if (value.isNotBlank()) {
                latestScreenContext = value
                if (!recognitionInProgress) {
                    statusText.text = "● Tela analisada • pronto para conversar"
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(com.lucasmoraes.claudgame.capture.ScreenCaptureService.ACTION_SCREEN_CONTEXT)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenContextReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenContextReceiver, filter)
            }
            receiverRegistered = true
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Não foi possível registrar receptor", error)
        }

        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS && !serviceDestroyed) {
                    ttsReady = true
                    val selectedVoice = NativeVoiceConfig.configure(tts!!)
                    android.util.Log.i("ClaudGameVoice", "Voz Android selecionada: $selectedVoice")
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Falha ao inicializar voz", error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                NOTIFICATION_ID,
                Notification.Builder(this, CHANNEL)
                    .setContentTitle("ClaudGame")
                    .setContentText("Companheiro IA ativo")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build()
            )
            trollEnabled = intent?.getBooleanExtra(EXTRA_TROLL, false) ?: trollEnabled
            if (panel == null) showOverlay()
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Falha ao iniciar companheiro", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Não ressuscitar a bolha automaticamente depois que o usuário fecha o Game Hub.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("ClaudGameOverlay", "Game Hub removido; encerrando bolha e captura")
        stopService(Intent(this, com.lucasmoraes.claudgame.capture.ScreenCaptureService::class.java))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun showOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 16, 22, 16)
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 15, 18, 28))
                cornerRadius = 28f
                setStroke(2, Color.argb(180, 100, 80, 255))
            }
        }

        val title = TextView(this).apply {
            text = "CLAUDGAME • IA"
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        statusText = TextView(this).apply {
            text = "● Observando • pronto para conversar"
            textSize = 12f
            setTextColor(Color.rgb(100, 255, 180))
            setPadding(0, 8, 0, 8)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        talkButton = Button(this).apply {
            text = "🎙 FALAR"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(82, 64, 150))
                cornerRadius = 22f
            }
            setOnClickListener {
                if (recognitionInProgress) stopListening() else startListening()
            }
        }
        val troll = TextView(this).apply {
            text = if (trollEnabled) "😈 Trolagem: ATIVA" else "😈 Trolagem: DESLIGADA"
            textSize = 11f
            setTextColor(Color.rgb(255, 190, 90))
            setPadding(12, 0, 0, 0)
        }
        row.addView(talkButton, LinearLayout.LayoutParams(120, 48))
        row.addView(troll, LinearLayout.LayoutParams(0, 48, 1f))
        root.addView(title)
        root.addView(statusText)
        root.addView(row)

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

        title.setOnTouchListener(dragListener(root, params))
        statusText.setOnTouchListener(dragListener(root, params))

        try {
            windowManager.addView(root, params)
            panel = root
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Falha ao exibir sobreposição", error)
            panel = null
        }
    }

    private fun dragListener(root: View, params: WindowManager.LayoutParams) =
        View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 4 || abs(event.rawY - downY) > 4) {
                        params.x = startX + (downX - event.rawX).toInt()
                        params.y = startY + (downY - event.rawY).toInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (error: Exception) {
                            android.util.Log.w("ClaudGameOverlay", "Falha ao mover painel", error)
                        }
                    }
                    true
                }
                else -> true
            }
        }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Microfone não autorizado"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Reconhecimento de voz indisponível"
            return
        }

        recognitionRetryCount = 0
        beginRecognition()
    }

    private fun beginRecognition() {
        if (serviceDestroyed) return
        try {
            recreateSpeechRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(listener)

            recognitionInProgress = true
            statusText.text = "● Ouvindo você..."
            talkButton.text = "⏹ PARAR"

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            speechRecognizer?.startListening(intent)
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Falha ao iniciar reconhecimento", error)
            recognitionInProgress = false
            statusText.text = "Não foi possível iniciar o microfone"
            talkButton.text = "🎙 FALAR"
        }
    }

    private fun stopListening() {
        recognitionInProgress = false
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        statusText.text = "● Pronto para conversar"
        talkButton.text = "🎙 FALAR"
    }

    private fun recreateSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            if (!serviceDestroyed && ::statusText.isInitialized) statusText.text = "● Fale agora..."
        }

        override fun onBeginningOfSpeech() {
            if (!serviceDestroyed && ::statusText.isInitialized) statusText.text = "● Ouvindo..."
        }

        override fun onEndOfSpeech() {
            if (!serviceDestroyed && ::statusText.isInitialized) statusText.text = "● Processando..."
        }

        override fun onError(error: Int) {
            if (serviceDestroyed) return
            recognitionInProgress = false
            if (::talkButton.isInitialized) talkButton.text = "🎙 FALAR"
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_CLIENT) && recognitionRetryCount < 1) {
                recognitionRetryCount++
                if (::statusText.isInitialized) statusText.text = "● Não consegui captar. Tentando novamente..."
                recreateSpeechRecognizer()
                android.os.Handler(mainLooper).postDelayed({
                    if (!serviceDestroyed) beginRecognition()
                }, 350L)
                return
            }
            if (::statusText.isInitialized) {
                statusText.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não entendi • fale um pouco mais perto do microfone"
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sem conexão para reconhecimento de voz"
                    SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone • verifique se outro app está usando o áudio"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecimento ocupado • tente novamente"
                    else -> "Não consegui ouvir • tente novamente"
                }
            }
        }

        override fun onResults(results: android.os.Bundle) {
            if (serviceDestroyed) return
            recognitionInProgress = false
            recognitionRetryCount = 0
            if (::talkButton.isInitialized) talkButton.text = "🎙 FALAR"
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                if (::statusText.isInitialized) statusText.text = "Não entendi • fale novamente"
                return
            }
            if (::statusText.isInitialized) statusText.text = "Você: $text"
            statusText.text = "● Consultando a inteligência artificial..."
            aiClient.ask(text, latestScreenContext, trollEnabled, latestScreenFramePath) { result ->
                if (serviceDestroyed) return@ask
                result.onSuccess { response -> speak(response) }
                    .onFailure { error ->
                        statusText.text = "IA indisponível: ${error.message ?: "tente novamente"}"
                    }
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (serviceDestroyed || !::statusText.isInitialized) return
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) statusText.text = "● Ouvindo: $text"
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
    }

    private fun speak(text: String) {
        if (!ttsReady || serviceDestroyed) {
            if (::statusText.isInitialized) statusText.text = "Voz ainda inicializando..."
            return
        }
        if (::statusText.isInitialized) statusText.text = "● ClaudGame: $text"
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "claudgame-reply")
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameOverlay", "Falha ao falar resposta", error)
        }
    }

    override fun onDestroy() {
        serviceDestroyed = true
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenContextReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
        aiClient.close()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        panel?.let {
            try {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        // Captura e bolha são uma única sessão: não deixar a captura continuar
        // escondida depois que o companheiro for encerrado.
        stopService(Intent(this, com.lucasmoraes.claudgame.capture.ScreenCaptureService::class.java))
        super.onDestroy()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ClaudGame Companion", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
