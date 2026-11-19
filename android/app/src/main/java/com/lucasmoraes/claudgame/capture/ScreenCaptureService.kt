package com.lucasmoraes.claudgame.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.lucasmoraes.claudgame.DisplaySize
import com.lucasmoraes.claudgame.MainActivity
import com.lucasmoraes.claudgame.R
import com.lucasmoraes.claudgame.ai.NoOcrEngine
import com.lucasmoraes.claudgame.ai.OcrEngine
import com.lucasmoraes.claudgame.ai.OcrEngines
import com.lucasmoraes.claudgame.ai.ScreenContextAnalyzer
import com.lucasmoraes.claudgame.overlay.ClaudGameOverlayService

/**
 * Captura a tela com MediaProjection (sempre após autorização do jogador) e entrega, no máximo a cada
 * ~1,8 s, o quadro atual + texto reconhecido ao companheiro flutuante via [CaptureBridge].
 *
 * Toda a mecânica de captura (callbacks do ImageReader/MediaProjection, redimensionamento, liberação)
 * roda numa única thread dedicada; a thread principal só inicia e encerra o serviço.
 */
class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "ClaudGameCapture"
        private const val CHANNEL = "claudgame_capture"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_DIMENSION = 1920
        private const val MIN_ANALYSIS_INTERVAL_MS = 1800L
        private const val BUSY_RETRY_MS = 400L
        private const val DISPLAY_CHECK_INTERVAL_MS = 750L
        private const val IMAGE_BUFFERS = 3
    }

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    // Estado abaixo: só acessar na thread de captura.
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var heldImage: Image? = null
    private var lastAnalysisAt = 0L
    private var lastDisplayCheckAt = 0L
    private var densityDpi = 0

    @Volatile
    private var analyzer: ScreenContextAnalyzer? = null

    @Volatile
    private var ocr: OcrEngine? = null

    @Volatile
    private var destroyed = false

    private val analysisRunnable = Runnable { analyzeHeldImage() }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection encerrada pelo sistema ou pelo jogador")
            CaptureBridge.publish(CaptureBridge.Snapshot("", null, CaptureBridge.STATUS_STOPPED))
            releaseCapture()
            stopSelf()
        }

        // Android 14+: avisa quando o conteúdo capturado muda de tamanho (ex.: o jogo gira para paisagem).
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0) resizeCaptureSurface(width, height)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        densityDpi = resources.displayMetrics.densityDpi
        val thread = HandlerThread("ClaudGameCapture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val engine: OcrEngine = try {
            OcrEngines.create()
        } catch (error: Throwable) {
            Log.w(TAG, "OCR indisponível; seguindo só com a imagem", error)
            NoOcrEngine
        }
        ocr = engine
        analyzer = ScreenContextAnalyzer(engine) { snapshot -> CaptureBridge.publish(snapshot) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val projectionData = intent?.let { readProjectionData(it) }

        // Todo startForegroundService() exige startForeground() logo em seguida.
        try {
            startInForeground()
        } catch (error: Exception) {
            Log.e(TAG, "Não foi possível entrar em primeiro plano", error)
            failAndStop("Não foi possível iniciar a captura: ${error.javaClass.simpleName}")
            return START_NOT_STICKY
        }

        if (resultCode != Activity.RESULT_OK || projectionData == null) {
            Log.w(TAG, "Serviço iniciado sem autorização de captura; encerrando")
            failAndStop("Captura de tela não autorizada")
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val newProjection = manager.getMediaProjection(resultCode, projectionData)
                ?: throw IllegalStateException("MediaProjection indisponível")
            val handler = captureHandler ?: throw IllegalStateException("Thread de captura indisponível")
            handler.post { startCapture(newProjection) }
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao obter MediaProjection", error)
            failAndStop(error.javaClass.simpleName + ": " + (error.message ?: "sem detalhes"))
        }
        return START_NOT_STICKY
    }

    private fun failAndStop(detail: String) {
        CaptureBridge.publish(CaptureBridge.Snapshot("", null, CaptureBridge.STATUS_UNAVAILABLE, detail = detail))
        // A sessão inteira cai junto (o painel é encerrado): sem este aviso o jogador ficaria sem saber por quê.
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, "ClaudGame: não foi possível capturar a tela ($detail)", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- captura (thread de captura)

    private fun startCapture(newProjection: MediaProjection) {
        if (destroyed) {
            try {
                newProjection.stop()
            } catch (_: Exception) {
            }
            return
        }
        releaseCapture() // sessão anterior (o jogador pode ativar o companheiro de novo)
        try {
            projection = newProjection
            // Android 14+ exige o callback registrado ANTES de criar o VirtualDisplay.
            newProjection.registerCallback(projectionCallback, captureHandler)

            val (screenWidth, screenHeight) = DisplaySize.current(this)
            val (width, height) = scaledSize(screenWidth, screenHeight)
            val newReader = createImageReader(width, height)
            reader = newReader
            virtualDisplay = newProjection.createVirtualDisplay(
                "ClaudGameCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface,
                null,
                null
            ) ?: throw IllegalStateException("VirtualDisplay não foi criado")
            CaptureBridge.sessionActive = true
            Log.i(TAG, "Captura iniciada em ${width}x$height")
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao iniciar MediaProjection/ImageReader", error)
            releaseCapture()
            failAndStop(error.javaClass.simpleName + ": " + (error.message ?: "sem detalhes"))
        }
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_BUFFERS).also { created ->
            created.setOnImageAvailableListener({ source -> handleImageAvailable(source) }, captureHandler)
        }

    private fun handleImageAvailable(source: ImageReader) {
        val image: Image? = try {
            source.acquireLatestImage()
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao adquirir quadro do ImageReader", error)
            null
        }
        if (image == null) return
        if (destroyed || source !== reader) {
            closeQuietly(image)
            return
        }
        // Guarda sempre o quadro MAIS RECENTE. Se a tela ficar parada depois de uma mudança, nenhum
        // quadro novo chega; sem reter o último, a análise ficaria presa num quadro velho (ou sem imagem).
        heldImage?.let { closeQuietly(it) }
        heldImage = image
        scheduleAnalysis()
    }

    private fun scheduleAnalysis() {
        val handler = captureHandler ?: return
        val wait = lastAnalysisAt + MIN_ANALYSIS_INTERVAL_MS - SystemClock.elapsedRealtime()
        handler.removeCallbacks(analysisRunnable)
        if (wait <= 0L) analyzeHeldImage() else handler.postDelayed(analysisRunnable, wait)
    }

    private fun analyzeHeldImage() {
        if (destroyed) return
        followDisplaySize(force = false)
        val image = heldImage ?: return
        val currentAnalyzer = analyzer ?: return
        if (currentAnalyzer.tryAnalyze(image)) {
            heldImage = null
            lastAnalysisAt = SystemClock.elapsedRealtime()
        } else {
            // Analisador ocupado (OCR em andamento): mantém o quadro e tenta de novo em instantes.
            captureHandler?.removeCallbacks(analysisRunnable)
            captureHandler?.postDelayed(analysisRunnable, BUSY_RETRY_MS)
        }
    }

    /**
     * Em Android < 14 não há onCapturedContentResize: confere o tamanho do display de tempos em tempos
     * (no máximo a cada [DISPLAY_CHECK_INTERVAL_MS], para não consultar o WindowManager a cada quadro).
     */
    private fun followDisplaySize(force: Boolean) {
        if (virtualDisplay == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDisplayCheckAt < DISPLAY_CHECK_INTERVAL_MS) return
        lastDisplayCheckAt = now
        try {
            val (screenWidth, screenHeight) = DisplaySize.current(this)
            resizeCaptureSurface(screenWidth, screenHeight)
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível conferir o tamanho da tela", error)
        }
    }

    private fun resizeCaptureSurface(sourceWidth: Int, sourceHeight: Int) {
        val display = virtualDisplay ?: return
        val (width, height) = scaledSize(sourceWidth, sourceHeight)
        val current = reader
        if (current != null && current.width == width && current.height == height) return
        try {
            val newReader = createImageReader(width, height)
            display.resize(width, height, densityDpi)
            display.surface = newReader.surface
            // O quadro retido pertence ao leitor antigo: descartar antes de fechá-lo.
            discardHeldImage()
            current?.setOnImageAvailableListener(null, null)
            current?.close()
            reader = newReader
            Log.i(TAG, "Captura redimensionada para ${width}x$height")
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao redimensionar a captura", error)
        }
    }

    private fun discardHeldImage() {
        heldImage?.let { closeQuietly(it) }
        heldImage = null
    }

    private fun releaseCapture() {
        captureHandler?.removeCallbacks(analysisRunnable)
        discardHeldImage()
        try {
            virtualDisplay?.release()
        } catch (error: Exception) {
            Log.w(TAG, "Falha ao liberar VirtualDisplay", error)
        }
        virtualDisplay = null
        reader?.let { oldReader ->
            try {
                oldReader.setOnImageAvailableListener(null, null)
                oldReader.close()
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao fechar ImageReader", error)
            }
        }
        reader = null
        projection?.let { oldProjection ->
            try {
                oldProjection.unregisterCallback(projectionCallback)
                oldProjection.stop()
            } catch (error: Exception) {
                Log.w(TAG, "Falha ao encerrar MediaProjection", error)
            }
        }
        projection = null
        CaptureBridge.sessionActive = false
    }

    // ---------------------------------------------------------------- utilidades

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height).coerceAtLeast(1)
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / longest.toFloat())
        return Pair((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private fun closeQuietly(image: Image) {
        try {
            image.close()
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureHandler?.post { followDisplaySize(force = true) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Game Hub removido; encerrando captura e companheiro")
        stopService(Intent(this, ClaudGameOverlayService::class.java))
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        destroyed = true
        val handler = captureHandler
        val thread = captureThread
        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                releaseCapture()
                analyzer?.close()
                analyzer = null
                ocr?.close()
                ocr = null
            }
            thread.quitSafely()
        }
        captureHandler = null
        captureThread = null
        CaptureBridge.sessionActive = false
        CaptureBridge.clear()
        // Captura e bolha são uma única sessão: nada de bolha órfã sem captura.
        stopService(Intent(this, ClaudGameOverlayService::class.java))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openHub = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setContentTitle("ClaudGame")
            .setContentText("Assistente de jogo ativo • captura de tela autorizada")
            .setSmallIcon(R.drawable.ic_stat_claudgame)
            .setContentIntent(openHub)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "ClaudGame • Captura", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
