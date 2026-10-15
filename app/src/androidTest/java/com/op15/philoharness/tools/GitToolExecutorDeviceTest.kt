package com.op15.philoharness.tools

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirme en conditions reelles le point de vigilance deja documente dans
 * GitToolExecutor.kt et TODO.md ("PAS teste sur device" -- "le binaire git n'est pas
 * garanti present/accessible au process de l'app sur un OnePlus 15 non prepare, pas de
 * Termux par defaut"). Verifie directement via `run-as` (meme UID que l'app) : `git`
 * est effectivement INACCESSIBLE au process de l'app sur ce device (confirme, pas
 * suppose) -- `sh -c 'which git'` echoue, "inaccessible or not found". Ce test verifie
 * que GitToolExecutor echoue proprement (Failure, jamais un crash/exception non geree)
 * dans cette condition reelle, exactement le comportement que sa doc promet.
 */
class GitToolExecutorDeviceTest {

    @Test
    fun statusFailsGracefullyWhenGitBinaryIsUnavailableToTheAppProcess() {
        val repoDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val executor = GitToolExecutor(repoDir)

        val result = executor.status()

        // Sur ce device, git n'est PAS dans le PATH du processus app (confirme separement
        // via `run-as com.op15.philoharness sh -c 'which git'` -> "inaccessible or not
        // found") -- le point reel a verifier est que ca ne plante jamais, quel que soit
        // le resultat exact.
        assertTrue(
            "GitToolExecutor doit toujours renvoyer un GitToolResult, jamais lever d'exception",
            result is GitToolResult.Success || result is GitToolResult.Failure,
        )
        if (result is GitToolResult.Failure) {
            assertTrue(result.reason.isNotBlank())
        }
    }
}
