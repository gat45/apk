package com.op15.philoharness.hardware

/**
 * Inventaire des modeles GGUF presents sur le device -- comble le manque "choix du modele"
 * signale par l'utilisateur en comparant a ses anciens projets (op15-unified/HardwareGovernor.kt
 * modelsJson()). Volontairement MINIMAL par rapport a la source d'inspiration : liste
 * seulement (nom + taille), AUCUNE action (charger/quantifier/basculer) -- changer le modele
 * utilise par llama-server reste hors perimetre (cf. TODO.md "Gestion du serveur LLM depuis
 * l'app"), ce module ne fait que rendre visible ce qui existe deja sur disque.
 *
 * `/data/local/tmp` n'est pas listable depuis le contexte app (SELinux bloque untrusted_app,
 * confirme dans la source d'inspiration) -- passe par RootHelper.suCmdReadOnly("ls"), deja
 * dans l'allowlist read-only du projet.
 */
object ModelInventory {

    data class ModelFile(val name: String, val sizeMb: Long, val path: String)

    private val SCAN_DIRS = listOf("/data/local/tmp", "/data/local/tmp/models", "/data/local/tmp/npu")

    /** Scanne les dossiers connus pour des fichiers .gguf. Retourne liste vide si pas root/aucun trouve. */
    fun scan(): List<ModelFile> {
        if (!RootHelper.isRooted()) return emptyList()
        val found = mutableListOf<ModelFile>()
        for (dir in SCAN_DIRS) {
            val out = RootHelper.suCmdReadOnly("ls -la $dir 2>/dev/null")
            for (line in out.lines()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 8) continue
                val name = parts[7]
                if (!name.endsWith(".gguf", ignoreCase = true)) continue
                val bytes = parts[4].toLongOrNull() ?: continue
                found.add(ModelFile(name, bytes / (1024 * 1024), "$dir/$name"))
            }
        }
        // Deduplique par nom (le meme modele peut apparaitre sous plusieurs dossiers scannes).
        return found.distinctBy { it.name }.sortedByDescending { it.sizeMb }
    }

    data class GgufInfo(
        val architecture: String?,
        val name: String?,
        val contextLength: Long?,
        val quantizationVersion: Long?,
        val tensorCount: Long,
        val kvCount: Long,
    )

    /**
     * Lit l'en-tete GGUF (magic/version/compteurs + quelques cles KV usuelles) d'un fichier
     * deja repere par scan(). Lecture seule : jamais `dd` (bloque par RootHelper.WRITE_TOKENS,
     * delibere -- cf. commentaire de classe de RootHelper) -- `xxd -p` capture les premiers
     * octets en HEX TEXTE via l'allowlist read-only existante, decode cote Kotlin. Fenetre
     * volontairement bornee (64 Ko) : suffisant pour les cles generales/architecture qu'un
     * ecrivain GGUF standard place en tete, mais PAS garanti si un fichier atypique place une
     * cle recherchee apres un gros tableau (ex. tokenizer) -- retourne alors ce qui a pu etre
     * lu avant d'epuiser la fenetre, jamais une exception.
     */
    fun readGgufInfo(path: String, windowBytes: Int = 65536): GgufInfo? {
        if (!RootHelper.isRooted()) return null
        val hex = RootHelper.suCmdReadOnly("xxd -l $windowBytes -p $path").replace("\n", "").replace("\r", "")
        if (hex.startsWith("REFUSED")) return null
        val data = try { hexToBytes(hex) } catch (e: Exception) { return null }
        return parseGgufHeader(data)
    }

    /** Coeur pur du parsing (pas de root/IO) -- separe pour etre teste en JVM sans device. */
    fun parseGgufHeader(data: ByteArray): GgufInfo? {
        if (data.size < 24) return null
        if (!(data[0] == 'G'.code.toByte() && data[1] == 'G'.code.toByte() &&
                data[2] == 'U'.code.toByte() && data[3] == 'F'.code.toByte())) {
            return null
        }
        var off = 8 // apres magic(4) + version(4)
        fun u64(): Long? {
            if (off + 8 > data.size) return null
            var v = 0L
            for (i in 0 until 8) v = v or ((data[off + i].toLong() and 0xFF) shl (8 * i))
            off += 8
            return v
        }
        fun u32(): Int? {
            if (off + 4 > data.size) return null
            var v = 0
            for (i in 0 until 4) v = v or ((data[off + i].toInt() and 0xFF) shl (8 * i))
            off += 4
            return v
        }
        fun str(): String? {
            val len = u64() ?: return null
            if (len < 0 || off + len > data.size) return null
            val s = String(data, off, len.toInt(), Charsets.UTF_8)
            off += len.toInt()
            return s
        }
        // Tailles fixes par code de type (0..7) -- cf. spec GGUF.
        val fixedSizes = intArrayOf(1, 1, 2, 2, 4, 4, 4, 1)
        fun skipValue(type: Int): Boolean {
            when {
                type in 0..7 -> { if (off + fixedSizes[type] > data.size) return false; off += fixedSizes[type] }
                type == 8 -> { if (str() == null) return false }
                type == 9 -> {
                    val elemType = u32() ?: return false
                    val count = u64() ?: return false
                    for (i in 0 until count) if (!skipValue(elemType)) return false
                }
                type == 10 || type == 11 || type == 12 -> { if (off + 8 > data.size) return false; off += 8 }
                else -> return false
            }
            return true
        }

        val tensorCount = u64() ?: return null
        val kvCount = u64() ?: return null
        var architecture: String? = null
        var name: String? = null
        var contextLength: Long? = null
        var quantVersion: Long? = null
        var i = 0L
        while (i < kvCount && off < data.size) {
            val key = str() ?: break
            val type = u32() ?: break
            when {
                key == "general.architecture" && type == 8 -> architecture = str()
                key == "general.name" && type == 8 -> name = str()
                key.endsWith(".context_length") && type in 4..5 -> contextLength = u32()?.toLong()
                key == "general.quantization_version" && type in 4..5 -> quantVersion = u32()?.toLong()
                else -> if (!skipValue(type)) break
            }
            i++
        }
        return GgufInfo(architecture, name, contextLength, quantVersion, tensorCount, kvCount)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val h = clean.substring(i * 2, i * 2 + 2)
            out[i] = h.toInt(16).toByte()
        }
        return out
    }
}
