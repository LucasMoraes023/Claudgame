package com.lucasmoraes.claudgame

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lucasmoraes.claudgame.capture.ScreenCaptureService
import com.lucasmoraes.claudgame.overlay.ClaudGameOverlayService

data class GameApp(val label: String, val packageName: String)

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var gameSpinner: Spinner
    private var games = emptyList<GameApp>()
    private var trollEnabled = false
    private var pendingCapture = false
    private var pendingGamePackage: String? = null

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingCapture) {
                pendingCapture = false
                requestAiCapture()
            } else if (!granted) {
                pendingCapture = false
                Toast.makeText(this, "Permita o microfone para conversar com o ClaudGame.", Toast.LENGTH_LONG).show()
            }
        }

    private val capturePermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startForegroundService(Intent(this, ClaudGameOverlayService::class.java).apply {
                putExtra(ClaudGameOverlayService.EXTRA_TROLL, trollEnabled)
            })

            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                    putExtra(ScreenCaptureService.EXTRA_TROLL, trollEnabled)
                }
                startForegroundService(serviceIntent)
                status.text = "IA ativa • observando e pronta para conversar"

                // A autorização de captura abre uma tela do sistema. Depois que ela termina,
                // iniciamos o jogo escolhido e deixamos o ClaudGame trabalhando em segundo plano.
                pendingGamePackage?.let { packageNameToLaunch ->
                    pendingGamePackage = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        packageManager.getLaunchIntentForPackage(packageNameToLaunch)?.let { launchIntent ->
                            startActivity(launchIntent)
                        }
                    }, 350)
                }
            } else {
                status.text = "Captura de tela não autorizada"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        gameSpinner = findViewById(R.id.gameSpinner)
        loadGames()

        findViewById<Button>(R.id.playButton).setOnClickListener { launchSelectedGame() }
        findViewById<Button>(R.id.aiButton).setOnClickListener { ensureVoiceAndCapture() }
        findViewById<Button>(R.id.trollButton).setOnClickListener { view ->
            trollEnabled = !trollEnabled
            (view as Button).text =
                if (trollEnabled) "MODO TROLAGEM: ATIVADO" else "MODO TROLAGEM: DESLIGADO"
        }
    }

    override fun onResume() {
        super.onResume()
        loadGames()
    }

    override fun onDestroy() {
        // Fechar o Game Hub encerra a sessão; abrir o jogo apenas o coloca em segundo plano.
        if (isFinishing && !isChangingConfigurations) {
            stopService(Intent(this, ClaudGameOverlayService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        super.onDestroy()
    }

    private fun ensureVoiceAndCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestAiCapture()
    }

    private fun loadGames() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.packageName != packageName }

        var found = installed
            .filter {
                it.category == ApplicationInfo.CATEGORY_GAME &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { GameApp(pm.getApplicationLabel(it).toString(), it.packageName) }

        if (found.isEmpty()) {
            found = installed
                .filter {
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pm.getLaunchIntentForPackage(it.packageName) != null
                }
                .map { GameApp(pm.getApplicationLabel(it).toString(), it.packageName) }
        }

        games = found.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }

        val labels = if (games.isEmpty()) listOf("Nenhum jogo/app instalável encontrado") else games.map { it.label }
        gameSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        status.text = when {
            games.isEmpty() -> "Nenhum jogo instalável encontrado"
            games.size == 1 -> "1 jogo/app encontrado"
            else -> games.size.toString() + " jogos/apps encontrados"
        }
    }

    private fun launchSelectedGame() {
        if (games.isEmpty()) {
            Toast.makeText(this, "Nenhum jogo instalável encontrado.", Toast.LENGTH_LONG).show()
            return
        }
        val position = gameSpinner.selectedItemPosition
        if (position !in games.indices) return
        val game = games[position]
        packageManager.getLaunchIntentForPackage(game.packageName)?.let {
            status.text = "Abrindo " + game.label + "..."
            startActivity(it)
        }
    }

    private fun requestAiCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorize a sobreposição para o assistente flutuante.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:" + packageName)
            })
            return
        }

        val position = gameSpinner.selectedItemPosition
        if (position !in games.indices) {
            Toast.makeText(this, "Escolha um jogo antes de ativar o companheiro.", Toast.LENGTH_LONG).show()
            return
        }

        // Guardamos o jogo escolhido para abri-lo automaticamente depois da autorização.
        pendingGamePackage = games[position].packageName

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }
}
