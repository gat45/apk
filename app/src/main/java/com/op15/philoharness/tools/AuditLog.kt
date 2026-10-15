package com.op15.philoharness.tools

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Journal JSONL append-only, chaine par hash, avec redaction automatique des
 * secrets -- porte depuis E:/harnais/nxus-agent-harness/src/nxus_harness/audit.py
 * (meme schema : previous_hash + event_hash en SHA-256 sur un JSON canonique
 * a cles triees). Comble un vrai manque : ShellToolExecutor.log n'etait
 * jusqu'ici qu'en memoire, perdu a la fin du process, sans preuve
 * d'alteration posterieure.
 */
data class AuditEvent(
    val eventId: String,
    val timestampIso: String,
    val component: String,
    val action: String,
    val decision: String,
    val details: Map<String, String>,
    val previousHash: String,
    val eventHash: String,
)

class AuditLog(private val path: File) {

    private var previousHash: String = tailHash()

    private fun tailHash(): String {
        if (!path.exists()) return "GENESIS"
        val lines = path.readText(Charsets.UTF_8).trim()
        if (lines.isEmpty()) return "GENESIS"
        val lastLine = lines.lines().last()
        return extractField(lastLine, "event_hash") ?: "GENESIS"
    }

    /**
     * Journalise CHAQUE invocation d'outil, qu'elle soit acceptee, refusee ou
     * en attente de confirmation -- la journalisation fait partie du contrat,
     * pas une option (meme discipline que ShellToolExecutor).
     */
    @Synchronized
    fun append(component: String, action: String, decision: String, details: Map<String, String> = emptyMap()) {
        val safeDetails = details.mapValues { (_, v) -> redact(v) }
        val eventId = UUID.randomUUID().toString()
        val timestamp = Instant.now().toString()
        // JSON canonique a cles triees (meme contrat que canonical_json cote Python) :
        // le hash doit etre reproductible, donc l'ordre des cles ne peut jamais varier.
        val canonicalPayload = buildCanonicalJson(eventId, timestamp, component, action, decision, safeDetails, previousHash)
        val eventHash = sha256(canonicalPayload)

        path.parentFile?.mkdirs()
        path.appendText(
            buildCanonicalJson(eventId, timestamp, component, action, decision, safeDetails, previousHash, eventHash) + "\n",
            Charsets.UTF_8,
        )
        previousHash = eventHash
    }

    private fun buildCanonicalJson(
        eventId: String,
        timestamp: String,
        component: String,
        action: String,
        decision: String,
        details: Map<String, String>,
        previousHash: String,
        eventHash: String? = null,
    ): String {
        // Cles triees alphabetiquement, manuellement (pas de lib JSON generaliste
        // necessaire pour une structure aussi simple -- evite une dependance de plus).
        val detailsJson = details.entries.sortedBy { it.key }
            .joinToString(",", prefix = "{", postfix = "}") { (k, v) -> "${jsonString(k)}:${jsonString(v)}" }
        val fields = mutableListOf(
            "\"action\":${jsonString(action)}",
            "\"component\":${jsonString(component)}",
            "\"decision\":${jsonString(decision)}",
            "\"details\":$detailsJson",
            "\"event_id\":${jsonString(eventId)}",
        )
        if (eventHash != null) fields.add("\"event_hash\":${jsonString(eventHash)}")
        fields.add("\"previous_hash\":${jsonString(previousHash)}")
        fields.add("\"timestamp\":${jsonString(timestamp)}")
        return fields.joinToString(",", prefix = "{", postfix = "}")
    }

    private fun jsonString(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }

    private fun extractField(jsonLine: String, field: String): String? {
        val regex = Regex("\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.find(jsonLine)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Masque les secrets evidents avant persistance -- jamais de cle/API/token en clair dans le journal. */
    private fun redact(value: String): String {
        var clean = value
        clean = Regex("(?i)(api[_-]?key|token|secret|password)\\s*([:=])\\s*(\\S+)").replace(clean) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}[REDACTED]"
        }
        clean = Regex("sk-[A-Za-z0-9_-]{12,}").replace(clean, "[REDACTED]")
        clean = Regex("(?i)Bearer\\s+[A-Za-z0-9._-]+").replace(clean, "[REDACTED]")
        return clean
    }

    companion object {
        /**
         * Reparcourt le fichier et revalide la chaine de hash -- detecte une
         * alteration posterieure (ligne modifiee/supprimee/inseree).
         */
        fun verify(path: File): Pair<Boolean, String> {
            if (!path.exists()) return false to "journal absent"
            var previous = "GENESIS"
            path.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val eventHash = extractFieldStatic(line, "event_hash") ?: return false to "ligne ${index + 1} invalide"
                val prevInLine = extractFieldStatic(line, "previous_hash") ?: return false to "ligne ${index + 1} invalide"
                if (prevInLine != previous) return false to "rupture de chaine ligne ${index + 1}"
                val withoutHash = line.replace(Regex(",?\"event_hash\":\"[^\"]*\""), "")
                val recomputed = MessageDigest.getInstance("SHA-256").digest(withoutHash.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (recomputed != eventHash) return false to "hash invalide ligne ${index + 1}"
                previous = eventHash
            }
            return true to "chaine valide"
        }

        private fun extractFieldStatic(jsonLine: String, field: String): String? {
            val regex = Regex("\"$field\":\"((?:[^\"\\\\]|\\\\.)*)\"")
            return regex.find(jsonLine)?.groupValues?.get(1)
        }
    }
}
