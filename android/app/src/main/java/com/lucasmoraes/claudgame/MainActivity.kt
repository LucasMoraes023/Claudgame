package com.lucasmoraes.claudgame

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.lucasmoraes.claudgame.capture.CaptureBridge
import com.lucasmoraes.claudgame.capture.ScreenCaptureService
import com.lucasmoraes.claudgame.overlay.ClaudGameOverlayService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class GameApp(val label: String, val packageName: String)

class MainActivity : Activity() {
    private companion object {
        const val TAG = "ClaudGameHub"
        const val REQUEST_PERMISSIONS = 1001
        const val REQUEST_CAPTURE = 1002
        const val STATE_PENDING_GAME = "pending_game_package"
        const val STATE_AWAITING_OVERLAY = "awaiting_overlay_permission"
        const val GAME_LAUNCH_DELAY_MS = 350L
        const val SESSION_STATUS = "IA ativa • observando e pronta para conversar"
    }

    private lateinit var statusView: TextView
    private lateinit var gameSpinner: Spinner
    private lateinit var aiButton: Button
    private lateinit var trollButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()

    private var games: List<GameApp> = emptyList()
    private var gamesLoadedOnce = false
    private var trollEnabled = false
    private var preferredPackage: String? = null
    private var pendingGamePackage: String? = null
    private var awaitingOverlayPermission = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findView(R.id.status)
        gameSpinner = findView(R.id.gameSpinner)
        aiButton = findView(R.id.aiButton)
        trollButton = findView(R.id.trollButton)
        findView<Button>(R.id.playButton).setOnClickListener { launchSelectedGame() }
        aiButton.setOnClickListener { onAiButtonClicked() }
        trollButton.setOnClickListener { toggleTroll() }
        gameSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                games.getOrNull(position)?.let {
                    preferredPackage = it.packageName
                    ClaudGamePrefs.setLastGamePackage(this@MainActivity, it.packageName)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        pendingGamePackage = savedInstanceState?.getString(STATE_PENDING_GAME)
        awaitingOverlayPermission = savedInstanceState?.getBoolean(STATE_AWAITING_OVERLAY, false) ?: false
        trollEnabled = ClaudGamePrefs.isTrollEnabled(this)
        preferredPackage = ClaudGamePrefs.lastGamePackage(this)

        refreshTrollButton()
        refreshAiButton()
        if (CaptureBridge.sessionActive) statusView.text = SESSION_STATUS
        loadGames()
    }

    override fun onResume() {
        super.onResume()
        trollEnabled = ClaudGamePrefs.isTrollEnabled(this)
        refreshTrollButton()
        refreshAiButton()
        loadGames()
        if (awaitingOverlayPermission) {
            awaitingOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                startAiFlow()
            } else {
                statusView.text = "Sobreposição não autorizada • o painel flutuante precisa dela"
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_GAME, pendingGamePackage)
        outState.putBoolean(STATE_AWAITING_OVERLAY, awaitingOverlayPermission)
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, ClaudGameOverlayService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        super.onDestroy()
    }

    private fun <T : View> findView(id: Int): T =
        findViewById<T>(id) ?: throw IllegalStateException("View não encontrada no layout: $id")

    private fun loadGames() {
        if (loader.isShutdown) return
        try {
            loader.execute {
                val found = queryGames()
                runOnUiThread { if (!destroyed) applyGames(found) }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun queryGames(): List<GameApp> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }
        val candidates = resolved
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
        val onlyGames = candidates.filter { isGame(it) }
        val chosen = if (onlyGames.isNotEmpty()) {
            onlyGames
        } else {
            candidates.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        }
        return chosen
            .map { GameApp(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun isGame(info: ApplicationInfo): Boolean =
        info.category == ApplicationInfo.CATEGORY_GAME || (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    private fun applyGames(found: List<GameApp>) {
        if (gamesLoadedOnce && found == games) return
        val firstLoad = !gamesLoadedOnce
        gamesLoadedOnce = true
        games = found

        val labels = if (found.isEmpty()) listOf("Nenhum jogo/app instalável encontrado") else found.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        gameSpinner.adapter = adapter
        val index = found.indexOfFirst { it.packageName == preferredPackage }
        if (index >= 0) gameSpinner.setSelection(index)

        if (firstLoad || !CaptureBridge.sessionActive) {
            statusView.text = when {
                CaptureBridge.sessionActive -> SESSION_STATUS
                found.isEmpty() -> "Nenhum jogo instalável encontrado"
                found.size == 1 -> "1 jogo/app encontrado"
                else -> found.size.toString() + " jogos/apps encontrados"
            }
        }
    }

    private fun selectedGame(): GameApp? = games.getOrNull(gameSpinner.selectedItemPosition)

    private fun launchSelectedGame() {
        val game = selectedGame()
        if (game == null) {
            toast("Nenhum jogo instalável encontrado.")
            return
        }
        launchPackage(game.packageName, game.label)
    }

    private fun launchPackage(packageName: String, label: String = packageName) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            toast("Não foi possível abrir $label.")
            return
        }
        try {
            statusView.text = "Abrindo $label..."
            startActivity(launch)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao abrir $packageName", error)
            toast("Não foi possível abrir $label.")
        }
    }

    private fun toggleTroll() {
        trollEnabled = !trollEnabled
        ClaudGamePrefs.setTrollEnabled(this, trollEnabled)
        refreshTrollButton()
    }

    private fun refreshTrollButton() {
        trollButton.setText(if (trollEnabled) R.string.hub_troll_on else R.string.hub_troll_off)
        trollButton.setTextColor(getColor(if (trollEnabled) R.color.cg_amber else R.color.cg_muted))
    }

    private fun refreshAiButton() {
        aiButton.setText(if (CaptureBridge.sessionActive) R.string.hub_ai_stop else R.string.hub_ai_start)
    }

    private fun onAiButtonClicked() {
        if (CaptureBridge.sessionActive) stopSession() else startAiFlow()
    }

    private fun startAiFlow() {
        val game = selectedGame()
        if (game == null) {
            toast("Escolha um jogo antes de ativar o companheiro.")
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            requestMicrophone()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            openOverlaySettings()
            return
        }
        pendingGamePackage = game.packageName
        requestScreenCapture()
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requestMicrophone() {
        val wanted = ArrayList<String>()
        wanted.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS || permissions.isEmpty()) return

        val micIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        val micGranted = micIndex >= 0 && grantResults.getOrNull(micIndex) == PackageManager.PERMISSION_GRANTED
        when {
            micGranted -> startAiFlow()
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> showMicrophoneSettingsDialog()
            else -> toast("Permita o microfone para conversar com o ClaudGame.")
        }
    }

    private fun showMicrophoneSettingsDialog() {
        if (destroyed || isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Microfone necessário")
            .setMessage(
                "O ClaudGame precisa do microfone para você conversar com a IA durante o jogo. " +
                    "Ative em Configurações > Permissões > Microfone."
            )
            .setPositiveButton("Abrir configurações") { _, _ -> openAppSettings() }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (error: Exception) {
            Log.w(TAG, "Não foi possível abrir as configurações do app", error)
            toast("Abra as configurações do Android e permita o microfone para o ClaudGame.")
        }
    }

    private fun openOverlaySettings() {
        awaitingOverlayPermission = true
        toast("Autorize a sobreposição para o assistente flutuante.")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    private fun requestScreenCapture() {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(intent, REQUEST_CAPTURE)
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao pedir a captura de tela", error)
            pendingGamePackage = null
            toast("Não foi possível pedir a captura de tela neste aparelho.")
        }
    }

    @Deprecated("Padrão da plataforma para Activity sem AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startSession(resultCode, data)
        } else {
            pendingGamePackage = null
            statusView.text = "Captura de tela não autorizada"
        }
    }

    private fun startSession(resultCode: Int, data: Intent) {
        try {
            startForegroundService(Intent(this, ClaudGameOverlayService::class.java))
            startForegroundService(
                Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            )
        } catch (error: Exception) {
            Log.e(TAG, "Falha ao iniciar os serviços do companheiro", error)
            stopService(Intent(this, ClaudGameOverlayService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
            pendingGamePackage = null
            toast("Não foi possível iniciar o assistente: ${error.javaClass.simpleName}")
            return
        }
        CaptureBridge.sessionActive = true
        refreshAiButton()
        statusView.text = SESSION_STATUS

        val gamePackage = pendingGamePackage
        pendingGamePackage = null
        if (gamePackage != null) {
            mainHandler.postDelayed({ if (!destroyed) launchPackage(gamePackage) }, GAME_LAUNCH_DELAY_MS)
        }
    }

    private fun stopSession() {
        stopService(Intent(this, ClaudGameOverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        CaptureBridge.sessionActive = false
        refreshAiButton()
        statusView.text = "Companheiro encerrado"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}