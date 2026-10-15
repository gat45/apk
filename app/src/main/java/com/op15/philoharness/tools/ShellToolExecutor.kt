package com.op15.philoharness.tools

import java.io.File
import java.util.concurrent.TimeUnit

sealed interface ShellToolResult {
    data class Success(val output: String) : ShellToolResult
    data class Failure(val output: String, val exitCode: Int) : ShellToolResult
    object TimedOut : ShellToolResult
    data class Rejected(val reason: String) : ShellToolResult
    data class Error(val reason: String) : ShellToolResult
}

data class ShellLogEntry(
    val atEpochMs: Long,
    val command: String,
    val outcome: String,
)

/**
 * Dernier outil de la liste convenue, et le plus dangereux : "shell
 * arbitraire : restrictions fortes, repertoire de travail limite et
 * journalisation." Contrairement a GitToolExecutor/BuildTestExecutor (qui
 * executent une commande deja fixee par le controleur), celui-ci recoit une
 * ligne de commande arbitraire -- donc TROIS defenses, pas une :
 *
 *  1. Liste blanche d'executables autorises (pas de liste noire, qui oublie
 *     toujours un cas -- cf. discipline "jamais de certitude fabriquee" du
 *     reste du projet, meme logique appliquee a la securite).
 *  2. Aucun shell interprete (`sh -c`, `cmd /c`, pipes, `&&`, redirections)
 *     -- ProcessBuilder recoit la liste d'arguments deja decoupee, jamais
 *     une chaine passee a un interpreteur.
 *  3. Repertoire de travail fige a la construction, jamais fourni par
 *     l'appelant.
 *
 * Chaque invocation (acceptee ou rejetee) est journalisee en memoire
 * (`log`), lisible par l'appelant -- la journalisation fait partie du
 * contrat, pas une option.
 */
class ShellToolExecutor(
    private val workingDir: File,
    private val allowedExecutables: Set<String> = setOf("git", "ls", "cat", "pwd", "echo"),
    private val maxDurationSeconds: Long = 30,
) {
    private val mutableLog = mutableListOf<ShellLogEntry>()
    val log: List<ShellLogEntry> get() = mutableLog.toList()

    fun run(command: List<String>): ShellToolResult {
        val commandText = command.joinToString(" ")
        val result = runInternal(command)
        mutableLog.add(ShellLogEntry(System.currentTimeMillis(), commandText, describe(result)))
        return result
    }

    private fun runInternal(command: List<String>): ShellToolResult {
        if (command.isEmpty()) {
            return ShellToolResult.Rejected("Commande vide")
        }
        val executable = command.first()
        if (executable !in allowedExecutables) {
            return ShellToolResult.Rejected(
                "Executable '$executable' non autorise (liste blanche : ${allowedExecutables.sorted()})",
            )
        }
        if (!workingDir.isDirectory) {
            return ShellToolResult.Error("Repertoire de travail introuvable : ${workingDir.absolutePath}")
        }

        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(maxDurationSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return ShellToolResult.TimedOut
            }

            val output = process.inputStream.bufferedReader().readText()
            if (process.exitValue() == 0) {
                ShellToolResult.Success(output)
            } else {
                ShellToolResult.Failure(output, process.exitValue())
            }
        } catch (e: java.io.IOException) {
            ShellToolResult.Error("Commande introuvable ou non executable : ${e.message}")
        }
    }

    private fun describe(result: ShellToolResult): String = when (result) {
        is ShellToolResult.Success -> "success"
        is ShellToolResult.Failure -> "failure(code=${result.exitCode})"
        ShellToolResult.TimedOut -> "timed_out"
        is ShellToolResult.Rejected -> "rejected(${result.reason})"
        is ShellToolResult.Error -> "error(${result.reason})"
    }
}
