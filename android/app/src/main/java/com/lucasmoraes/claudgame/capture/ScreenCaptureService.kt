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
    private var densityDpi = 0
    private var displayWindowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var trollEnabled = false
    private var analyzer: ScreenContextAnalyzer? = null
    private var lastAnalysisAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            sendBroadcast(Intent(ACTION_SCREEN_CONTEXT).apply {
                setPackage(packageName)
                putExtra("context", "")
                putExtra("frame_status", "unavailable")
            })
            releaseCapture()
            stopSelf()
        }

        // O jogo normalmente gira para paisagem depois que a permissão é concedida.
        // Redimensionar a projeção evita manter um buffer em retrato para um jogo landscape.
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && virtualDisplay != null) {
                resizeCaptureSurface(width, height)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        analyzer = ScreenContextAnalyzer { context, frameJpeg, frameStatus ->
            val frameFile = java.io.File(cacheDir, "latest_game_frame.jpg")
            val framePath = if (frameStatus == "ok" && frameJpeg != null) {
                runCatching { frameFile.writeBytes(frameJpeg); frameFile.absolutePath }.getOrNull()
            } else {
                runCatching { frameFile.delete() }
                null
            }
            android.util.Log.i("ClaudGameVision", "Resultado da captura: status=$frameStatus, bytes=${frameJpeg?.size ?: 0}")
            sendBroadcast(Intent(ACTION_SCREEN_CONTEXT).apply {
                setPackage(packageName)
                putExtra("context", context)
                putExtra("frame_path", framePath)
                putExtra("frame_status", if (framePath != null) "ok" else if (frameStatus == "ok") "unavailable" else frameStatus)
            })
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
            displayWindowManager = wm
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            densityDpi = metrics.densityDpi

            val maxDimension = maxOf(metrics.widthPixels, metrics.heightPixels)
            val scale = minOf(1f, 1920f / maxDimension.toFloat())
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)

            reader = createImageReader(width, height)
            virtualDisplay = projection?.createVirtualDisplay(
                "ClaudGameCapture",
                width,
                height,
                densityDpi,
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


    private fun createImageReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ source -> handleImageAvailable(source) }, mainHandler)
        }
    }

    private fun handleImageAvailable(imageReader: ImageReader) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAnalysisAt < 1800L) {
                imageReader.acquireLatestImage()?.close()
                return
            }
            val image = imageReader.acquireLatestImage() ?: return
            lastAnalysisAt = now
            val rotation = when (displayWindowManager?.defaultDisplay?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            analyzer?.analyze(image, rotation) ?: image.close()
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameCapture", "Falha ao adquirir frame do ImageReader", error)
        }
    }

    private fun resizeCaptureSurface(sourceWidth: Int, sourceHeight: Int) {
        val maxDimension = maxOf(sourceWidth, sourceHeight)
        val scale = minOf(1f, 1920f / maxDimension.toFloat())
        val width = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight * scale).toInt().coerceAtLeast(1)
        if (reader?.width == width && reader?.height == height) return

        try {
            val newReader = createImageReader(width, height)
            val oldReader = reader
            virtualDisplay?.resize(width, height, densityDpi)
            virtualDisplay?.setSurface(newReader.surface)
            reader = newReader
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
            android.util.Log.i("ClaudGameCapture", "Captura redimensionada para ${width}x${height}")
        } catch (error: Exception) {
            android.util.Log.e("ClaudGameCapture", "Falha ao redimensionar captura", error)
        }
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        displayWindowManager = null
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
