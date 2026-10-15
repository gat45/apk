package com.op15.philoharness.memory

import android.content.Context
import java.io.File

data class MemoryEntry(val slug: String, val title: String, val hook: String)

/**
 * Memoire durable en fichiers Markdown, distincte de Room (etat operationnel
 * des taches) et de RagStore (recherche lexicale sur l'historique de chat).
 * Concept inspire du modele publiquement decrit par jaredrhod/ai-memory-vault
 * (CC BY-SA 4.0) -- notes par sujet + un index MEMORY.md qui pointe vers
 * chacune, pour que le contexte charge dans un prompt reste petit (une ligne
 * d'index par note, pas tout le contenu). Implementation ORIGINALE : aucun
 * fichier/template de ce depot n'a ete copie (le concept -- separer
 * connaissance durable et historique de conversation -- n'est pas
 * protegeable par le droit d'auteur, seule l'expression litterale l'est).
 *
 * Perimetre volontairement minimal pour cette premiere version : pas de
 * journaux quotidiens, pas de liens inter-notes -- juste des notes nommees
 * (slug) avec un titre et un contenu, plus un index genere automatiquement.
 * Pas encore cable a ReasoningController (cf. TODO.md) : l'agent ne lit/n'ecrit
 * encore aucune memoire durable automatiquement.
 */
class MarkdownMemoryStore internal constructor(dirOverride: File) {

    constructor(context: Context) : this(File(context.filesDir, "memory"))

    private val dir: File = dirOverride.apply { mkdirs() }
    private val indexFile: File = File(dir, "MEMORY.md")

    /**
     * Nom de fichier sur : lettres/chiffres/tirets uniquement, jamais de traversee de
     * repertoire. Le controle ".."/"/" doit porter sur l'entree BRUTE, avant
     * assainissement -- verifier apres coup est un bug reel trouve en ecrivant le test :
     * le remplacement regex efface deja les points et les slashes, donc un controle
     * posterieur sur la chaine nettoyee ne peut jamais declencher (code mort qui donne
     * une fausse impression de securite).
     */
    private fun safeSlug(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.contains("..") || trimmed.contains('/') || trimmed.contains('\\')) return null
        val slug = trimmed.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-')
        return slug.takeIf { it.isNotBlank() }
    }

    /** Ecrit (ou remplace) une note. Met a jour l'index apres coup -- jamais desynchronise. */
    fun write(slug: String, title: String, content: String, hook: String): Boolean {
        val safe = safeSlug(slug) ?: return false
        val file = File(dir, "$safe.md")
        file.writeText("# $title\n\n$content\n")
        rebuildIndex()
        return true
    }

    fun read(slug: String): String? {
        val safe = safeSlug(slug) ?: return null
        val file = File(dir, "$safe.md")
        return if (file.exists()) file.readText() else null
    }

    fun delete(slug: String): Boolean {
        val safe = safeSlug(slug) ?: return false
        val file = File(dir, "$safe.md")
        val deleted = file.delete()
        if (deleted) rebuildIndex()
        return deleted
    }

    fun list(): List<MemoryEntry> {
        return (dir.listFiles { f -> f.extension == "md" && f.name != "MEMORY.md" } ?: emptyArray())
            .sortedBy { it.name }
            .map { file ->
                val slug = file.nameWithoutExtension
                val lines = file.readLines()
                val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: slug
                val hook = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(120) ?: ""
                MemoryEntry(slug, title, hook)
            }
    }

    /** Index compact : une ligne par note, jamais le contenu complet -- garde le prompt petit. */
    private fun rebuildIndex() {
        val entries = list()
        val body = buildString {
            appendLine("# Index memoire")
            appendLine()
            if (entries.isEmpty()) {
                appendLine("(aucune note)")
            } else {
                entries.forEach { e -> appendLine("- [${e.title}](${e.slug}.md) -- ${e.hook}") }
            }
        }
        indexFile.writeText(body)
    }

    /** Contenu de l'index, pour injection dans un prompt (jamais le detail de chaque note d'un coup). */
    fun indexContent(): String = if (indexFile.exists()) indexFile.readText() else "(memoire vide)"
}
