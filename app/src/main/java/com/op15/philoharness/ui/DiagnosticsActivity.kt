package com.op15.philoharness.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.op15.philoharness.R
import com.op15.philoharness.automation.ScreenAutomationService
import com.op15.philoharness.hardware.HardwareTelemetry
import com.op15.philoharness.hardware.LlmServerController
import com.op15.philoharness.hardware.ModelInventory
import com.op15.philoharness.telegram.TelegramPollingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ecran Diagnostics -- comble un angle mort reel (HardwareTelemetry portee depuis le debut
 * de la session mais jamais affichee nulle part, cf. TODO.md "Pas encore cable", retour
 * utilisateur "pas d'onglet avec les infos materiel/temperature/choix du modele comme les
 * .kt que je t'ai donnes"). Materiel/RAM/CPU/liste de modeles = lecture seule, aucun risque.
 *
 * Demarrer/Arreter llama-server (ajoute sur demande explicite "go et oublie pas le design
 * de l'ui") est la SEULE action d'ecriture de cet ecran : LlmServerController est une
 * capacite isolee, deux commandes fixes seulement, jamais melee a RootHelper (qui reste
 * 100% lecture seule). Toujours derriere une boite de confirmation -- l'utilisateur tapant
 * le bouton EST deja la validation humaine (meme principe que GatedToolLoop/RiskGate pour
 * les actions proposees par le LLM, mais ici l'action est initiee directement par l'utilisateur,
 * pas par l'agent).
 */
class DiagnosticsActivity : ComponentActivity() {

    private var selectedModelPath: String? = null
    private var selectedModelName: String? = null
    private val modelRowViews = mutableMapOf<String, TextView>()
    /** null = jamais observe encore (pas d'alerte au premier affichage). */
    private var wasAccessibilityConnected: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        val serverStatusText = findViewById<TextView>(R.id.serverStatusText)
        val selectedModelText = findViewById<TextView>(R.id.selectedModelText)
        val hardwareText = findViewById<TextView>(R.id.hardwareText)
        val ramText = findViewById<TextView>(R.id.ramText)
        val cpuText = findViewById<TextView>(R.id.cpuText)
        val gpuText = findViewById<TextView>(R.id.gpuText)
        val npuText = findViewById<TextView>(R.id.npuText)
        val modelsContainer = findViewById<LinearLayout>(R.id.modelsContainer)
        val modelDetailText = findViewById<TextView>(R.id.modelDetailText)
        val startButton = findViewById<Button>(R.id.startServerButton)
        val stopButton = findViewById<Button>(R.id.stopServerButton)
        val accessibilityStatusText = findViewById<TextView>(R.id.accessibilityStatusText)
        val openAccessibilitySettingsButton = findViewById<Button>(R.id.openAccessibilitySettingsButton)
        val telegramStatusText = findViewById<TextView>(R.id.telegramStatusText)
        val telegramTokenInput = findViewById<EditText>(R.id.telegramTokenInput)
        val startTelegramButton = findViewById<Button>(R.id.startTelegramButton)

        // Comble "Pas de UI pour activer ScreenAutomationService" (TODO.md) : ouvre
        // l'ecran systeme, ne force rien -- l'activation reste un choix explicite de
        // l'utilisateur dans l'UI Android elle-meme (jamais contournee par l'app, cf.
        // la restriction OEM confirmee cette session : une activation programmatique
        // n'est de toute facon pas possible sur ce device/ROM).
        openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        startTelegramButton.setOnClickListener {
            val token = telegramTokenInput.text?.toString()?.trim().orEmpty()
            if (token.isBlank()) {
                android.widget.Toast.makeText(this, "Token vide.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Demarrer le bot Telegram ?")
                .setMessage("Le bot pourra recevoir des messages et declencher une analyse via ce harnais.")
                .setPositiveButton("Demarrer") { _, _ ->
                    TelegramPollingService.start(this, token)
                    telegramTokenInput.text?.clear() // jamais garde en memoire plus longtemps que necessaire
                    telegramStatusText.text = "Demarre (token non reaffiche)."
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        startButton.setOnClickListener {
            val path = selectedModelPath ?: return@setOnClickListener
            val name = selectedModelName ?: path.substringAfterLast('/')
            android.app.AlertDialog.Builder(this)
                .setTitle("Demarrer le serveur ?")
                .setMessage("Lancer llama-server avec le modele :\n\n$name\n\n(-dev HTP0, port 8080)")
                .setPositiveButton("Demarrer") { _, _ ->
                    lifecycleScope.launch {
                        val msg = withContext(Dispatchers.IO) { LlmServerController.start(path) }
                        android.widget.Toast.makeText(this@DiagnosticsActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        stopButton.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle("Arreter le serveur ?")
                .setMessage("Le modele en cours de chargement/utilisation sera dechargee.")
                .setPositiveButton("Arreter") { _, _ ->
                    lifecycleScope.launch {
                        val msg = withContext(Dispatchers.IO) { LlmServerController.stop() }
                        android.widget.Toast.makeText(this@DiagnosticsActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }

        // Boucle rapide : materiel/RAM/CPU/statut serveur, toutes les 2s.
        lifecycleScope.launch {
            while (true) {
                val hardware = withContext(Dispatchers.IO) { HardwareTelemetry.summary() }
                hardwareText.text = hardware.ifBlank { "Aucune lecture disponible (sysfs inaccessible ?)" }

                val ram = withContext(Dispatchers.IO) { HardwareTelemetry.ramInfo() }
                ramText.text = ram?.let {
                    "${it.usedMb} / ${it.totalMb} Mo (${it.pctUsed}%)\n${it.availableMb} Mo libres"
                } ?: "Indisponible"

                val cpu = withContext(Dispatchers.IO) { HardwareTelemetry.cpuFrequencies() }
                cpuText.text = if (cpu.isEmpty()) "Indisponible" else
                    cpu.joinToString("\n") { "C${it.cpu} ${it.mhz}MHz (max ${it.maxMhz})" }

                val gpu = withContext(Dispatchers.IO) { HardwareTelemetry.gpuUsage() }
                gpuText.text = buildString {
                    append(gpu.clockMhz?.let { "$it" } ?: "?")
                    append(" / ")
                    append(gpu.maxClockMhz?.let { "$it" } ?: "?")
                    append(" MHz\n")
                    append(gpu.busyPercent?.let { "%.0f%%".format(it) } ?: "? %")
                    append(" charge\n")
                    append(gpu.memMb?.let { "$it Mo" } ?: "RAM : ?")
                }

                val npu = withContext(Dispatchers.IO) { HardwareTelemetry.npuMemory() }
                npuText.text = "Frequence : non exposee\npar ce device.\n" +
                    (npu.sharedBuffersMb?.let { "ION partage : $it Mo" } ?: "ION : ?") +
                    "\n(buffers GPU+NPU+camera,\npas exclusif au NPU)"

                val server = withContext(Dispatchers.IO) { probeServer() }
                serverStatusText.text = server.first
                stopButton.isEnabled = server.second
                startButton.isEnabled = !server.second && selectedModelPath != null

                val accessibilityConnected = ScreenAutomationService.instance != null
                accessibilityStatusText.text = if (accessibilityConnected) {
                    "Actif."
                } else {
                    "Inactif -- a activer manuellement ci-dessous."
                }
                // Comble "Service tue silencieusement" (TODO.md, audit externe) : alerte
                // UNE SEULE FOIS au moment ou le service tombe alors qu'il tournait, pas a
                // chaque passage de boucle -- jamais au tout premier affichage (il n'a
                // jamais ete actif, ce n'est pas une chute).
                if (wasAccessibilityConnected == true && !accessibilityConnected) {
                    android.widget.Toast.makeText(
                        this@DiagnosticsActivity,
                        "Le service d'accessibilite s'est arrete -- reactive-le si besoin.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                wasAccessibilityConnected = accessibilityConnected

                delay(2000)
            }
        }

        // Boucle lente : scan des modeles (plusieurs appels root), toutes les 10s.
        lifecycleScope.launch {
            while (true) {
                val models = withContext(Dispatchers.IO) { ModelInventory.scan() }
                renderModelRows(modelsContainer, models, selectedModelText, startButton)

                val detail = models.firstOrNull { it.path == selectedModelPath }
                    ?: models.firstOrNull()
                val info = detail?.let { withContext(Dispatchers.IO) { ModelInventory.readGgufInfo(it.path) } }
                modelDetailText.text = info?.let {
                    "Architecture : ${it.architecture ?: "?"}  --  Contexte : ${it.contextLength ?: "?"}\n" +
                        "${it.tensorCount} tenseurs, ${it.kvCount} cles KV"
                } ?: if (models.isEmpty() && !com.op15.philoharness.hardware.RootHelper.isRooted())
                    "Root indisponible : impossible de lister /data/local/tmp."
                else ""

                delay(10_000)
            }
        }
    }

    /** Reconstruit les lignes de modele cliquables. Simple -- pas de RecyclerView pour une poignee d'items. */
    private fun renderModelRows(
        container: LinearLayout,
        models: List<ModelInventory.ModelFile>,
        selectedModelText: TextView,
        startButton: Button,
    ) {
        if (models.isEmpty()) {
            if (container.childCount != 1 || modelRowViews.isNotEmpty()) {
                container.removeAllViews()
                modelRowViews.clear()
                val empty = TextView(this).apply {
                    text = "Aucun .gguf trouve dans /data/local/tmp (ou /models, /npu)."
                    setTextColor(getColor(R.color.philo_text_secondary))
                    textSize = 13f
                }
                container.addView(empty)
            }
            return
        }
        // Reconstruction complete seulement si la liste de noms a change (evite un flicker
        // de selection a chaque rafraichissement 10s si rien n'a bouge).
        val currentNames = models.map { it.name }.toSet()
        if (modelRowViews.keys != currentNames) {
            container.removeAllViews()
            modelRowViews.clear()
            for (model in models) {
                val row = TextView(this).apply {
                    text = "${model.name}  --  ${model.sizeMb} Mo"
                    setTextColor(getColor(R.color.philo_text_primary))
                    textSize = 13f
                    setPadding(12, 10, 12, 10)
                    background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.bg_model_row)
                    isSelected = model.path == selectedModelPath
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = 8 }
                    setOnClickListener {
                        selectedModelPath = model.path
                        selectedModelName = model.name
                        modelRowViews.values.forEach { v -> v.isSelected = false }
                        this.isSelected = true
                        selectedModelText.text = "Selectionne : ${model.name}"
                        startButton.isEnabled = true
                    }
                }
                container.addView(row)
                modelRowViews[model.name] = row
            }
        }
    }

    /**
     * Sonde directe en lecture seule (jamais via LlmClient -- ce dernier porte l'invariant
     * "le modele ne produit jamais ANSWER seul", non pertinent ici) : /health pour la
     * joignabilite, /v1/models pour le chemin du modele charge si le serveur repond.
     * @return (texte affiche, server joignable)
     */
    private fun probeServer(): Pair<String, Boolean> {
        val health = httpGet("http://127.0.0.1:8080/health")
        if (health == null) {
            return "Injoignable -- llama-server n'est pas lance sur le device." to false
        }
        val models = httpGet("http://127.0.0.1:8080/v1/models")
        val modelPath = models?.let { Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        return "Joignable (127.0.0.1:8080)\nModele : ${modelPath ?: "non rapporte"}" to true
    }

    private fun httpGet(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 1500
            connection.readTimeout = 1500
            connection.requestMethod = "GET"
            val body = if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else null
            connection.disconnect()
            body
        } catch (e: Exception) {
            null
        }
    }
}
