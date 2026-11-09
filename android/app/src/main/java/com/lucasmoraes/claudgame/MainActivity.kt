package com.lucasmoraes.claudgame

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lucasmoraes.claudgame.capture.ScreenCaptureService

data class GameApp(val label: String, val packageName: String)

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var gameSpinner: Spinner
    private var games = emptyList<GameApp>()
    private var trollEnabled = false

    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_TROLL, trollEnabled)
            }
            startForegroundService(serviceIntent)
            status.text = "IA ativa • observando a partida"
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
        findViewById<Button>(R.id.aiButton).setOnClickListener { requestAiCapture() }
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

    private fun loadGames() {
        val pm = packageManager
        val installed = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            .filter { it.packageName != packageName }

        // Primeiro usa a classificação oficial de JOGO do Android.
        var found = installed
            .filter {
                it.category == ApplicationInfo.CATEGORY_GAME &&
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { GameApp(pm.getApplicationLabel(it).toString(), it.packageName) }

        // Alguns jogos não declaram CATEGORY_GAME. Como o ClaudGame é
        // um hub/launcher, usa como fallback os apps instalados pelo usuário
        // que possuem uma tela de lançamento, evitando apps de sistema.
        if (found.isEmpty()) {
            found = installed
                .filter {
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pm.getLaunchIntentForPackage(it.packageName) != null
                }
                .map { GameApp(pm.getApplicationLabel(it).toString(), it.packageName) }
        }

        games = found
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        val labels = if (games.isEmpty()) {
            listOf("Nenhum jogo/app instalável encontrado")
        } else {
            games.map { it.label }
        }

        gameSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        status.text = when {
            games.isEmpty() -> "Nenhum jogo instalável encontrado"
            games.size == 1 -> "1 jogo/app encontrado"
            else -> games.size.toString() + " jogos/apps encontrados"
        }
    }

    private fun launchSelectedGame() {
        if (games.isEmpty()) {
            Toast.makeText(
                this,
                "Nenhum jogo instalável encontrado.",
                Toast.LENGTH_LONG
            ).show()
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
            Toast.makeText(
                this,
                "Autorize a sobreposição para o assistente flutuante.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = android.net.Uri.parse("package:" + packageName)
            })
            return
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capturePermission.launch(manager.createScreenCaptureIntent())
    }
}
