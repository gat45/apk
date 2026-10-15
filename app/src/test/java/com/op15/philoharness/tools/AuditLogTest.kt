package com.op15.philoharness.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AuditLogTest {

    private fun newLogFile(): File {
        val dir = File("build/tmp/audit_log_test_${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "audit.jsonl")
    }

    @Test
    fun `a fresh chain with several events verifies as valid`() {
        val file = newLogFile()
        val log = AuditLog(file)

        log.append("tools", "git_status", "allow", mapOf("tool" to "git_status"))
        log.append("tools", "git_commit", "block", mapOf("tool" to "git_commit"))
        log.append("tools", "shell", "reject", mapOf("reason" to "not whitelisted"))

        val (valid, message) = AuditLog.verify(file)
        assertTrue(message, valid)
    }

    @Test
    fun `tampering with a logged value breaks the chain`() {
        val file = newLogFile()
        val log = AuditLog(file)
        log.append("tools", "git_status", "allow")
        log.append("tools", "git_commit", "block")

        val lines = file.readLines().toMutableList()
        lines[0] = lines[0].replace("\"action\":\"git_status\"", "\"action\":\"git_diff\"")
        file.writeText(lines.joinToString("\n") + "\n")

        val (valid, _) = AuditLog.verify(file)
        assertFalse(valid)
    }

    @Test
    fun `deleting a line breaks the chain`() {
        val file = newLogFile()
        val log = AuditLog(file)
        log.append("tools", "event1", "allow")
        log.append("tools", "event2", "allow")
        log.append("tools", "event3", "allow")

        val lines = file.readLines().toMutableList()
        lines.removeAt(1)
        file.writeText(lines.joinToString("\n") + "\n")

        val (valid, _) = AuditLog.verify(file)
        assertFalse(valid)
    }

    @Test
    fun `missing file is reported as invalid, not thrown`() {
        val (valid, message) = AuditLog.verify(File("build/tmp/does_not_exist_${System.nanoTime()}.jsonl"))
        assertFalse(valid)
        assertEquals("journal absent", message)
    }

    @Test
    fun `api keys and tokens are redacted before persistence`() {
        val file = newLogFile()
        val log = AuditLog(file)

        log.append("tools", "shell", "allow", mapOf("command" to "curl -H 'api_key=sk-abcdefghijklmnop123456'"))

        val content = file.readText()
        assertFalse(content.contains("sk-abcdefghijklmnop123456"))
        assertTrue(content.contains("[REDACTED]"))
    }

    @Test
    fun `bearer tokens are redacted`() {
        val file = newLogFile()
        val log = AuditLog(file)

        log.append("tools", "shell", "allow", mapOf("header" to "Bearer abc123.def456-ghi"))

        val content = file.readText()
        assertFalse(content.contains("abc123.def456-ghi"))
    }

    @Test
    fun `appending across AuditLog instances continues the same chain`() {
        val file = newLogFile()
        AuditLog(file).append("tools", "event1", "allow")
        // Nouvelle instance -- doit relire le tailHash existant, pas repartir de GENESIS.
        AuditLog(file).append("tools", "event2", "allow")

        val (valid, message) = AuditLog.verify(file)
        assertTrue(message, valid)
        assertEquals(2, file.readLines().count { it.isNotBlank() })
    }
}
