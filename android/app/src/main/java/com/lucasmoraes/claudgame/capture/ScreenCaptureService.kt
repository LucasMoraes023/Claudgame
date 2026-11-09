package com.lucasmoraes.claudgame.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TROLL = "troll"
        private const val CHANNEL = "claudgame_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trollEnabled = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val result = tts?.setLanguage(Locale("pt", "BR"))
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.05f)

                if (result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    mainHandler.postDelayed({
                        speak("ClaudGame ativado. Bora jogar.", "claudgame-start")
                    }, 800)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, CHANNEL)
                .setContentTitle("ClaudGame")
                .setContentText("Assistente de jogo ativo")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        )

        trollEnabled = intent?.getBooleanExtra(EXTRA_TROLL, false) ?: false

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY

        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
            ?: return START_NOT_STICKY

        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection indisponível")

            // Android 14+ exige um callback registrado antes de iniciar a captura.
            projection?.registerCallback(projectionCallback, mainHandler)

            val metrics = DisplayMetrics()
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)

            val width = (metrics.widthPixels / 2).coerceAtLeast(360)
            val height = (metrics.heightPixels / 2).coerceAtLeast(640)

            reader = ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 2
            )
            reader?.setOnImageAvailableListener({ imageReader ->
                imageReader.acquireLatestImage()?.close()
            }, mainHandler)

            virtualDisplay = projection?.createVirtualDisplay(
                "ClaudGameCapture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                null
            )
        } catch (e: Exception) {
            // Nunca deixar uma falha de captura derrubar o processo do ClaudGame.
            releaseCapture()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun speak(text: String, utteranceId: String) {
        if (!ttsReady) return

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) return

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        releaseCapture()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ClaudGame", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
