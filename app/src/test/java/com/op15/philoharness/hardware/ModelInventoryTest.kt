package com.op15.philoharness.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ModelInventoryTest {

    private fun le32(v: Int, out: ByteArrayOutputStream) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    private fun le64(v: Long, out: ByteArrayOutputStream) {
        for (i in 0 until 8) out.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    private fun str(s: String, out: ByteArrayOutputStream) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        le64(bytes.size.toLong(), out)
        out.write(bytes)
    }

    /** Construit un GGUF synthetique : magic/version/compteurs + 2 cles KV (architecture, context_length). */
    private fun buildGguf(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('G'.code); out.write('G'.code); out.write('U'.code); out.write('F'.code)
        le32(3, out) // version
        le64(5, out) // tensor_count (arbitraire)
        le64(2, out) // kv_count

        // kv 1 : general.architecture (string, type 8) = "qwen2"
        str("general.architecture", out)
        le32(8, out)
        str("qwen2", out)

        // kv 2 : qwen2.context_length (uint32, type 4) = 4096
        str("qwen2.context_length", out)
        le32(4, out)
        le32(4096, out)

        return out.toByteArray()
    }

    @Test
    fun `parses magic, counts and known keys from a synthetic GGUF header`() {
        val info = ModelInventory.parseGgufHeader(buildGguf())
        require(info != null)
        assertEquals(5L, info.tensorCount)
        assertEquals(2L, info.kvCount)
        assertEquals("qwen2", info.architecture)
        assertEquals(4096L, info.contextLength)
    }

    @Test
    fun `a non-GGUF file is rejected, never guessed`() {
        val junk = "not a gguf file at all, just some random bytes here".toByteArray()
        assertNull(ModelInventory.parseGgufHeader(junk))
    }

    @Test
    fun `truncated buffer stops gracefully instead of crashing`() {
        val full = buildGguf()
        // Coupe en plein milieu de la 2e cle -- doit renvoyer ce qui a ete lu avant, pas planter.
        val truncated = full.copyOfRange(0, full.size - 10)
        val info = ModelInventory.parseGgufHeader(truncated)
        require(info != null)
        assertEquals("qwen2", info.architecture)
    }
}
