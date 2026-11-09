package com.lucasmoraes.claudgame.overlay

import android.Manifest
import android.app.Service
import android.content.Intent
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
import com.lucasmoraes.claudgame.ai.AssistantEngine
import java.util.Locale
import kotlin.math.abs

class ClaudGameOverlayService : Service() {
    companion object {
        const val EXTRA_TROLL = "troll"
    }

    private lateinit var windowManager: WindowManager
    private var panel: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speechInitializing = false
    private var trollEnabled = false
    private lateinit var statusText: TextView
    private lateinit var talkButton: Button
    private val assistant = AssistantEngine()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setLanguage(Locale("pt", "BR"))
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.05f)
            }
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        trollEnabled = intent?.getBooleanExtra(EXTRA_TROLL, false) ?: trollEnabled
        if (panel == null) showOverlay()
        return START_STICKY
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
            setOnClickListener { startListening() }
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
        } catch (e: Exception) {
            statusText.text = "Sobreposição não disponível"
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
                        params.y = startY + (event.rawY - downY).toInt()
                        windowManager.updateViewLayout(root, params)
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
        if (speechRecognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                statusText.text = "Reconhecimento de voz indisponível"
                return
            }
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(listener)
            } catch (e: Exception) {
                statusText.text = "Não foi possível iniciar o microfone"
                talkButton.text = "🎙 FALAR"
                return
            }
        }

        val recognizer = speechRecognizer ?: return
        statusText.text = "● Ouvindo você..."
        talkButton.text = "⏹ PARAR"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer.startListening(intent)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            statusText.text = "● Fale agora..."
        }

        override fun onBeginningOfSpeech() {
            statusText.text = "● Ouvindo..."
        }

        override fun onEndOfSpeech() {
            statusText.text = "● Processando..."
            talkButton.text = "🎙 FALAR"
        }

        override fun onError(error: Int) {
            statusText.text = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi • tente falar novamente"
                SpeechRecognizer.ERROR_NETWORK -> "Sem conexão para reconhecimento de voz"
                SpeechRecognizer.ERROR_AUDIO -> "Erro no microfone"
                else -> "Não consegui ouvir • tente novamente"
            }
            talkButton.text = "🎙 FALAR"
        }

        override fun onResults(results: android.os.Bundle) {
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) {
                statusText.text = "Não entendi"
                return
            }
            statusText.text = "Você: $text"
            val response = assistant.reply(text, trollEnabled)
            speak(response)
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            statusText.text = "Voz ainda inicializando..."
            return
        }
        statusText.text = "● ClaudGame: $text"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "claudgame-reply")
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        panel?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
