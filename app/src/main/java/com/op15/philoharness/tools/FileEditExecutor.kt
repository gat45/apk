package com.op15.philoharness.tools

import java.io.File

sealed interface FileEditResult {
    data class Success(val bytesWritten: Int) : FileEditResult
    data class Failure(val reason: String) : FileEditResult
}

/**
 * Troisieme outil reel derriere GatedToolLoop, ordre convenu : "modifications
 * de fichiers : confirmation selon le niveau de risque". L'ecriture elle-meme
 * ne decide RIEN sur le risque -- c'est PhiloReasoningWorker qui classe cet
 * outil en Reversibility.PARTIALLY_REVERSIBLE avant de l'autoriser via
 * RiskGate (meme contrat que git_status/compile : cette classe execute,
 * le controleur decide).
 *
 * Garde-fou structurel (pas seulement une confirmation applicative) : tout
 * chemin cible DOIT rester sous `rootDir` apres resolution canonique --
 * rejette toute tentative de sortir du perimetre autorise via `..` ou un
 * chemin absolu, meme si le modele la propose "par erreur".
 */
class FileEditExecutor(private val rootDir: File) {

    fun write(relativePath: String, content: String): FileEditResult {
        val target = resolveWithinRoot(relativePath)
            ?: return FileEditResult.Failure("Chemin refuse (hors de $rootDir ou invalide) : $relativePath")

        return try {
            target.parentFile?.mkdirs()
            target.writeText(content)
            FileEditResult.Success(content.toByteArray().size)
        } catch (e: java.io.IOException) {
            FileEditResult.Failure("Ecriture impossible : ${e.message}")
        }
    }

    private fun resolveWithinRoot(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return try {
            val canonicalRoot = rootDir.canonicalFile
            val candidate = File(canonicalRoot, relativePath).canonicalFile
            if (candidate.path.startsWith(canonicalRoot.path + File.separator) || candidate == canonicalRoot) {
                candidate
            } else {
                null // tentative de sortir de rootDir (ex. "../../etc/passwd") -- refuse, pas seulement loggue
            }
        } catch (e: java.io.IOException) {
            // canonicalFile() peut lever IOException sur un chemin pointant vers un
            // lecteur/volume inexistant (ex. "Z:/..." sur Windows) -- un chemin qu'on ne
            // peut pas resoudre n'est jamais a l'interieur de rootDir, donc refuse plutot
            // que de laisser l'exception remonter (trouve en testant ce cas precis).
            null
        }
    }
}
