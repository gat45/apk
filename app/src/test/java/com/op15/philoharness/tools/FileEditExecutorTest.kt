package com.op15.philoharness.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileEditExecutorTest {

    private fun newRootDir(): File {
        val dir = File("build/tmp/file_edit_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `writes a new file inside the root`() {
        val root = newRootDir()
        val executor = FileEditExecutor(root)

        val result = executor.write("notes.txt", "hello world")

        assertTrue(result is FileEditResult.Success)
        assertEquals("hello world", File(root, "notes.txt").readText())
        root.deleteRecursively()
    }

    @Test
    fun `creates intermediate directories as needed`() {
        val root = newRootDir()
        val executor = FileEditExecutor(root)

        val result = executor.write("sub/dir/notes.txt", "nested")

        assertTrue(result is FileEditResult.Success)
        assertEquals("nested", File(root, "sub/dir/notes.txt").readText())
        root.deleteRecursively()
    }

    @Test
    fun `overwrites an existing file`() {
        val root = newRootDir()
        val executor = FileEditExecutor(root)
        File(root, "notes.txt").writeText("old")

        val result = executor.write("notes.txt", "new")

        assertTrue(result is FileEditResult.Success)
        assertEquals("new", File(root, "notes.txt").readText())
        root.deleteRecursively()
    }

    @Test
    fun `rejects path traversal escaping the root via dot-dot`() {
        val root = newRootDir()
        val canary = File(root.parentFile, "canary_${System.nanoTime()}.txt")
        val executor = FileEditExecutor(root)

        val result = executor.write("../${canary.name}", "should never land here")

        assertTrue(result is FileEditResult.Failure)
        assertTrue(!canary.exists())
        root.deleteRecursively()
    }

    @Test
    fun `rejects an absolute path outside the root`() {
        val root = newRootDir()
        val executor = FileEditExecutor(root)

        val result = executor.write("Z:/outside_root.txt", "nope")

        assertTrue(result is FileEditResult.Failure)
        root.deleteRecursively()
    }

    @Test
    fun `rejects a blank path`() {
        val executor = FileEditExecutor(newRootDir())

        val result = executor.write("", "content")

        assertTrue(result is FileEditResult.Failure)
    }
}
