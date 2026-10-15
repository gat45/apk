package com.op15.philoharness.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarkdownMemoryStoreTest {

    private fun newStore(): MarkdownMemoryStore =
        MarkdownMemoryStore(File("build/tmp/memory_test_${System.nanoTime()}"))

    @Test
    fun `write then read returns the note content`() {
        val store = newStore()

        store.write("op15-htp", "Backend HTP", "Marco-Nano tourne sur HTP0, valide le 12 oct.", "Backend valide")
        val content = store.read("op15-htp")

        assertTrue(content.orEmpty().contains("Backend HTP"))
        assertTrue(content.orEmpty().contains("Marco-Nano"))
    }

    @Test
    fun `reading a nonexistent note returns null, not an exception`() {
        val store = newStore()

        assertNull(store.read("does-not-exist"))
    }

    @Test
    fun `slugs are sanitized -- spaces and accents become dashes`() {
        val store = newStore()

        val ok = store.write("Backend HTP v81", "Titre", "contenu", "hook")

        assertTrue(ok)
        assertTrue(store.read("backend-htp-v81") != null)
    }

    @Test
    fun `directory traversal in a slug is rejected`() {
        val store = newStore()

        val ok = store.write("../../etc/passwd", "titre", "contenu", "hook")

        assertFalse(ok)
    }

    @Test
    fun `list returns all notes with title and hook extracted`() {
        val store = newStore()
        store.write("a", "Note A", "Premiere ligne de A.", "hook A")
        store.write("b", "Note B", "Premiere ligne de B.", "hook B")

        val entries = store.list()

        assertEquals(2, entries.size)
        assertTrue(entries.any { it.slug == "a" && it.title == "Note A" })
        assertTrue(entries.any { it.slug == "b" && it.title == "Note B" })
    }

    @Test
    fun `index is rebuilt after write and lists every note`() {
        val store = newStore()
        store.write("a", "Note A", "contenu A", "hook A")
        store.write("b", "Note B", "contenu B", "hook B")

        val index = store.indexContent()

        assertTrue(index.contains("Note A"))
        assertTrue(index.contains("Note B"))
    }

    @Test
    fun `index is rebuilt after delete and no longer lists the removed note`() {
        val store = newStore()
        store.write("a", "Note A", "contenu A", "hook A")
        store.write("b", "Note B", "contenu B", "hook B")

        store.delete("a")
        val index = store.indexContent()

        assertFalse(index.contains("Note A"))
        assertTrue(index.contains("Note B"))
    }

    @Test
    fun `empty vault reports an empty index instead of crashing`() {
        val store = newStore()

        val index = store.indexContent()

        assertTrue(index.contains("vide") || index.contains("aucune"))
    }

    @Test
    fun `overwriting an existing slug replaces its content`() {
        val store = newStore()
        store.write("x", "Titre 1", "ancien contenu", "hook")

        store.write("x", "Titre 2", "nouveau contenu", "hook")
        val content = store.read("x")

        assertTrue(content.orEmpty().contains("Titre 2"))
        assertFalse(content.orEmpty().contains("ancien contenu"))
    }
}
