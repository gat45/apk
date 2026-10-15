package com.op15.philoharness.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildTestExecutorTest {

    private fun newWorkDir(): File {
        val dir = File("build/tmp/build_tool_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `successful command returns Success with exit code 0`() {
        val executor = BuildTestExecutor(newWorkDir())

        val result = executor.run(listOf("git", "--version"))

        assertTrue(result is BuildToolResult.Success)
        assertEquals(0, (result as BuildToolResult.Success).exitCode)
        assertTrue(result.output.contains("git version"))
    }

    @Test
    fun `failing command returns Failure with nonzero exit code`() {
        val executor = BuildTestExecutor(newWorkDir())

        val result = executor.run(listOf("git", "this-is-not-a-real-git-subcommand"))

        assertTrue(result is BuildToolResult.Failure)
        assertTrue((result as BuildToolResult.Failure).exitCode != 0)
    }

    @Test
    fun `a command exceeding the duration limit is killed and reported as TimedOut`() {
        // maxDurationSeconds=1 pour garder le test rapide ; la commande dort 10s,
        // donc si le timeout ne tuait pas vraiment le process, ce test pendrait 10s.
        val executor = BuildTestExecutor(newWorkDir(), maxDurationSeconds = 1)

        val result = executor.run(listOf("sleep", "10"))

        assertTrue(result is BuildToolResult.TimedOut)
    }

    @Test
    fun `nonexistent command fails cleanly instead of throwing`() {
        val executor = BuildTestExecutor(newWorkDir())

        val result = executor.run(listOf("definitely_not_a_real_binary_98765"))

        assertTrue(result is BuildToolResult.Error)
    }

    @Test
    fun `missing working directory fails cleanly`() {
        val executor = BuildTestExecutor(File("Z:/does/not/exist_${System.nanoTime()}"))

        val result = executor.run(listOf("git", "--version"))

        assertTrue(result is BuildToolResult.Error)
    }

    @Test
    fun `empty command fails cleanly`() {
        val executor = BuildTestExecutor(newWorkDir())

        val result = executor.run(emptyList())

        assertTrue(result is BuildToolResult.Error)
    }
}
