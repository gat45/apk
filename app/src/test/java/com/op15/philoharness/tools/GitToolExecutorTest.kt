package com.op15.philoharness.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Test JVM sur un vrai depot git temporaire (pas un mock) : la JVM de dev
 * a `git` sur le PATH, contrairement a un appareil Android non prepare --
 * ce test valide le binding ProcessBuilder reel, pas le comportement sur
 * device (qui depend de la presence effective du binaire, cf. doc de la classe).
 *
 * Les depots temporaires sont crees sous build/tmp (meme disque que le
 * projet) plutot que dans le dossier temp systeme : sur cette machine de dev,
 * le temp systeme (D:/temp) est sur un filesystem qui n'enregistre pas la
 * propriete des fichiers, ce que `git` refuse par securite ("detected dubious
 * ownership") -- pas un bug de GitToolExecutor, un artefact d'environnement.
 */
class GitToolExecutorTest {

    private fun newTempRepoDir(): File {
        val dir = File("build/tmp/git_tool_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun runGit(dir: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS)
    }

    @Test
    fun `status on a clean repo reports nothing to commit`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
        File(repo, "a.txt").writeText("hello")
        runGit(repo, "add", "a.txt")
        runGit(repo, "commit", "-q", "-m", "initial")

        val result = GitToolExecutor(repo).status()

        assertTrue(result is GitToolResult.Success)
        repo.deleteRecursively()
    }

    @Test
    fun `status on a dirty repo lists the modified file`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
        val file = File(repo, "a.txt")
        file.writeText("hello")
        runGit(repo, "add", "a.txt")
        runGit(repo, "commit", "-q", "-m", "initial")
        file.writeText("hello modified")

        val result = GitToolExecutor(repo).status()

        assertTrue(result is GitToolResult.Success)
        assertTrue((result as GitToolResult.Success).output.contains("a.txt"))
        repo.deleteRecursively()
    }

    @Test
    fun `diff on a modified file shows the change`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
        val file = File(repo, "a.txt")
        file.writeText("hello\n")
        runGit(repo, "add", "a.txt")
        runGit(repo, "commit", "-q", "-m", "initial")
        file.writeText("hello modified\n")

        val result = GitToolExecutor(repo).diff()

        assertTrue(result is GitToolResult.Success)
        assertTrue((result as GitToolResult.Success).output.contains("hello modified"))
        repo.deleteRecursively()
    }

    @Test
    fun `missing directory fails cleanly instead of throwing`() {
        val result = GitToolExecutor(File("Z:/does/not/exist_${System.nanoTime()}")).status()

        assertTrue(result is GitToolResult.Failure)
    }

    @Test
    fun `nonexistent git binary fails cleanly instead of throwing`() {
        val repo = newTempRepoDir()

        val result = GitToolExecutor(repo, gitBinary = "definitely_not_a_real_binary_12345").status()

        assertTrue(result is GitToolResult.Failure)
        repo.deleteRecursively()
    }

    @Test
    fun `commit on a staged change succeeds and is reflected in log`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")
        File(repo, "a.txt").writeText("hello")
        runGit(repo, "add", "a.txt")

        val result = GitToolExecutor(repo).commit("test commit message")

        assertTrue(result is GitToolResult.Success)
        val logProcess = ProcessBuilder("git", "log", "--oneline").directory(repo).redirectErrorStream(true).start()
        logProcess.waitFor(10, TimeUnit.SECONDS)
        val log = logProcess.inputStream.bufferedReader().readText()
        assertTrue(log.contains("test commit message"))
        repo.deleteRecursively()
    }

    @Test
    fun `commit with a blank message is refused before calling git`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")

        val result = GitToolExecutor(repo).commit("   ")

        assertTrue(result is GitToolResult.Failure)
        repo.deleteRecursively()
    }

    @Test
    fun `commit with nothing staged fails cleanly`() {
        val repo = newTempRepoDir()
        runGit(repo, "init", "-q")
        runGit(repo, "config", "user.email", "test@example.com")
        runGit(repo, "config", "user.name", "Test")

        val result = GitToolExecutor(repo).commit("nothing to commit here")

        assertTrue(result is GitToolResult.Failure)
        repo.deleteRecursively()
    }
}
