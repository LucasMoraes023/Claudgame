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
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import com.lucasmoraes.claudgame.ai.ScreenContextAnalyzer

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TROLL = "troll"
        const val ACTION_SCREEN_CONTEXT = "com.lucasmoraes.claudgame.SCREEN_CONTEXT"
        private const val CHANNEL = "claudgame_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trollEnabled = false
    private var analyzer: ScreenContextAnalyzer? = null
    private var lastAnalysisAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        analyzer = ScreenContextAnalyzer { context, frameJpeg ->
            val framePath = if (frameJpeg != null) {
                runCatching {
                    java.io.File(cacheDir, "latest_game_frame.jpg").apply { writeBytes(frameJpeg) }.absolutePath
                }.getOrNull()
            } else null
            sendBroadcast(
                Intent(ACTION_SCREEN_CONTEXT).apply {
                    setPackage(packageName)
                    putExtra("context", context)
                    putExtra("frame_path", framePath)
                }
            )
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

            // Preserve the real screen aspect ratio. The old fixed minimum height
            // distorted landscape games (for example 768x640 instead of 1536x672),
            // making small HUD/chat text unreadable to ML Kit OCR.
            val maxDimension = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = minOf(1f, 1920f / maxDimension.toFloat())
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)

            reader = ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 2
            )
            reader?.setOnImageAvailableListener({ imageReader ->
                val now = System.currentTimeMillis()
                if (now - lastAnalysisAt < 1800L) {
                    imageReader.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                lastAnalysisAt = now

                val rotation = when (wm.defaultDisplay.rotation) {
                    Surface.ROTATION_90 -> 90
                    Surface.ROTATION_180 -> 180
                    Surface.ROTATION_270 -> 270
                    else -> 0
                }

                analyzer?.analyze(image, rotation)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("ClaudGameCapture", "Game Hub removido; encerrando captura e companheiro")
        stopService(Intent(this, com.lucasmoraes.claudgame.overlay.ClaudGameOverlayService::class.java))
        stopSelf()
        super.onTaskRemoved(rootIntent)
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
        analyzer?.close()
        analyzer = null
        releaseCapture()
        stopService(Intent(this, com.lucasmoraes.claudgame.overlay.ClaudGameOverlayService::class.java))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "ClaudGame", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
