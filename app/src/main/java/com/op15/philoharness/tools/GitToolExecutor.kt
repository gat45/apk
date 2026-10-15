package com.op15.philoharness.tools

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Premiere implementation reelle derriere GatedToolLoop (remplace le stub ""
 * code en dur dans PhiloReasoningWorker). Volontairement lecture seule :
 * `git status`/`git diff` uniquement -- pas de write (add/commit/reset) tant
 * que le reste de la chaine (confirmation utilisateur, journalisation) n'est
 * pas construit (cf. TODO.md, ordre d'implementation des outils).
 *
 * Point de vigilance explicite (plan d'integration) : sur Android, un
 * ProcessBuilder n'a PAS automatiquement acces a un environnement Linux
 * complet -- le binaire `git` n'est present que si l'utilisateur l'a installe
 * (ex. Termux) et expose dans le PATH du processus de l'app, ce qui n'est pas
 * garanti sur un appareil non prepare. Cette classe echoue proprement
 * (GitToolResult.Failure) plutot que de supposer que `git` existe.
 */
sealed interface GitToolResult {
    data class Success(val output: String) : GitToolResult
    data class Failure(val reason: String) : GitToolResult
}

class GitToolExecutor(
    private val repoDir: File,
    private val gitBinary: String = "git",
    private val timeoutSeconds: Long = 15,
) {
    fun status(): GitToolResult = run("status", "--short", "--branch")

    fun diff(): GitToolResult = run("diff", "--no-color")

    /**
     * Seule operation d'ecriture de GitToolExecutor -- classee IRREVERSIBLE
     * par PhiloReasoningWorker (jamais juste PARTIALLY_REVERSIBLE comme une
     * ecriture de fichier locale) : un commit peut etre pousse/partage avant
     * qu'on s'en rende compte, contrairement a un fichier modifie localement.
     * N'accepte aucun message vide (git refuserait de toute facon, mais on le
     * verifie explicitement pour un message d'erreur clair).
     */
    fun commit(message: String): GitToolResult {
        if (message.isBlank()) {
            return GitToolResult.Failure("Message de commit vide -- refuse avant meme d'appeler git")
        }
        return run("commit", "-m", message)
    }

    private fun run(vararg args: String): GitToolResult {
        if (!repoDir.isDirectory) {
            return GitToolResult.Failure("Repertoire de depot introuvable : ${repoDir.absolutePath}")
        }
        return try {
            val process = ProcessBuilder(listOf(gitBinary) + args)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return GitToolResult.Failure("Commande git interrompue (timeout ${timeoutSeconds}s)")
            }

            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() != 0) {
                GitToolResult.Failure("git ${args.joinToString(" ")} a echoue (code ${process.exitValue()}) : $output")
            } else {
                GitToolResult.Success(output)
            }
        } catch (e: java.io.IOException) {
            // Cas attendu sur un appareil sans binaire git accessible (pas de Termux, pas
            // de busybox git) -- pas une exception a laisser remonter, c'est un etat normal
            // a signaler au modele/au RiskGate, pas un crash de l'app.
            GitToolResult.Failure("Binaire '$gitBinary' introuvable ou non executable : ${e.message}")
        }
    }
}
