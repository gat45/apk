package com.op15.philoharness.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellToolExecutorTest {

    private fun newWorkDir(): File {
        val dir = File("build/tmp/shell_tool_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `allowed executable runs successfully`() {
        val executor = ShellToolExecutor(newWorkDir(), allowedExecutables = setOf("git"))

        val result = executor.run(listOf("git", "--version"))

        assertTrue(result is ShellToolResult.Success)
    }

    @Test
    fun `executable not on the whitelist is rejected without running`() {
        val executor = ShellToolExecutor(newWorkDir(), allowedExecutables = setOf("git"))

        val result = executor.run(listOf("rm", "-rf", "/"))

        assertTrue(result is ShellToolResult.Rejected)
    }

    @Test
    fun `shell metacharacters are passed as literal arguments, not interpreted`() {
        // "git" est autorise, mais l'argument contenant "&&" doit etre traite comme un
        // simple nom de sous-commande invalide pour git -- jamais interprete par un shell
        // (pas de `sh -c` sous-jacent dans l'implementation).
        val executor = ShellToolExecutor(newWorkDir(), allowedExecutables = setOf("git"))

        val result = executor.run(listOf("git", "status && rm -rf /"))

        // git refuse "status && rm -rf /" comme un unique nom de sous-commande invalide :
        // la preuve que ce n'est pas passe a un shell interpretant "&&" comme separateur.
        assertTrue(result is ShellToolResult.Failure)
    }

    @Test
    fun `a command exceeding the duration limit is killed and reported as TimedOut`() {
        val executor = ShellToolExecutor(newWorkDir(), allowedExecutables = setOf("sleep"), maxDurationSeconds = 1)

        val result = executor.run(listOf("sleep", "10"))

        assertTrue(result is ShellToolResult.TimedOut)
    }

    @Test
    fun `empty command is rejected`() {
        val executor = ShellToolExecutor(newWorkDir())

        val result = executor.run(emptyList())

        assertTrue(result is ShellToolResult.Rejected)
    }

    @Test
    fun `every invocation -- accepted or rejected -- is logged`() {
        val executor = ShellToolExecutor(newWorkDir(), allowedExecutables = setOf("git"))

        executor.run(listOf("git", "--version"))
        executor.run(listOf("rm", "-rf", "/"))

        assertEquals(2, executor.log.size)
        assertTrue(executor.log[0].outcome == "success")
        assertTrue(executor.log[1].outcome.startsWith("rejected"))
        assertTrue(executor.log[1].command.contains("rm"))
    }
}
