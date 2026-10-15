package com.op15.philoharness.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Deuxieme outil reel derriere GatedToolLoop (apres GitToolExecutor), dans
 * l'ordre convenu : "compilation et tests : execution controlee avec limite
 * de duree". Generique (une commande arbitraire, pas figee sur Gradle) parce
 * que le projet cible depend du depot configure -- aucune commande par
 * defaut n'est fournie tant qu'aucun vrai depot/outil de build n'est cable
 * (meme discipline que GitToolExecutor : pas d'acces large par defaut).
 *
 * Point de vigilance (plan d'integration) : sur Android, le process de l'app
 * n'a pas necessairement acces a un toolchain de compilation complet (pas de
 * JDK/Gradle/NDK garanti dans le PATH du process) -- cette classe echoue
 * proprement si la commande n'existe pas, elle ne suppose jamais qu'un
 * environnement de build est disponible.
 */
sealed interface BuildToolResult {
    data class Success(val output: String, val exitCode: Int) : BuildToolResult
    data class Failure(val output: String, val exitCode: Int) : BuildToolResult
    data class TimedOut(val partialOutput: String) : BuildToolResult
    data class Error(val reason: String) : BuildToolResult
}

class BuildTestExecutor(
    private val workingDir: File,
    private val maxDurationSeconds: Long = 300,
) {
    /**
     * `command` est fournie par l'appelant (ex. ["./gradlew", "assembleDebug"]
     * ou ["./gradlew", "testDebugUnitTest"]) -- cette classe n'en connait
     * aucune par defaut. Le controleur (PhiloReasoningWorker) reste
     * responsable de decider QUELLE commande est autorisee avant d'appeler
     * ceci ; cette classe se contente de l'executer de maniere bornee.
     */
    fun run(command: List<String>): BuildToolResult {
        if (command.isEmpty()) {
            return BuildToolResult.Error("Commande vide")
        }
        if (!workingDir.isDirectory) {
            return BuildToolResult.Error("Repertoire de travail introuvable : ${workingDir.absolutePath}")
        }

        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(maxDurationSeconds, TimeUnit.SECONDS)
            if (!finished) {
                // Tuer AVANT de lire le flux : tant que le process vit, inputStream ne
                // recoit jamais d'EOF et readText() bloquerait indefiniment (bug reel
                // trouve en ecrivant le test du timeout, pas seulement theorique).
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                val partial = process.inputStream.bufferedReader().readText()
                return BuildToolResult.TimedOut(partial.takeLast(MAX_OUTPUT_CHARS))
            }

            val output = process.inputStream.bufferedReader().readText().takeLast(MAX_OUTPUT_CHARS)
            if (process.exitValue() == 0) {
                BuildToolResult.Success(output, process.exitValue())
            } else {
                BuildToolResult.Failure(output, process.exitValue())
            }
        } catch (e: java.io.IOException) {
            // Binaire/commande introuvable -- etat normal a signaler, pas un crash.
            BuildToolResult.Error("Commande introuvable ou non executable : ${e.message}")
        }
    }

    companion object {
        // Les logs de build/test peuvent etre enormes (cf. verbosite du log llama-server
        // rencontree cette session) -- on tronque pour ce qui est persiste/affiche,
        // jamais de troncature cote execution (le process va bien au bout ou au timeout).
        private const val MAX_OUTPUT_CHARS = 8_000
    }
}
