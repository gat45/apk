package com.op15.philoharness.rag

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * Magasin RAG local : indexe la conversation et les documents importes dans une table
 * virtuelle SQLite FTS5 (recherche lexicale BM25, 100% offline, aucune dependance externe).
 *
 * Porte depuis F:/oneplus/opencode-root/app/src/main/java/com/op15/toolkit/RagStore.kt
 * (meme auteur, meme device cible, deja valide en conditions reelles -- demande utilisateur
 * "creuse tous [les dossiers]" sur les projets OP15 existants). Implementation retenue car
 * autonome (zero dependance Gradle nouvelle, donc zero risque de resolution offline comme
 * rencontre plusieurs fois cette session), et parce que le SDK GenieX (qairt/llama_cpp)
 * n'expose aucune API d'embedding semantique cote Android -- FTS5 + bm25() etait la solution
 * visee, mais verifiee FAUSSE en conditions reelles cette session : sur le OnePlus 15
 * (OxygenOS 16, API 36 -- tres au-dessus du seuil "minSdk 29" suppose dans la premiere
 * version de ce fichier), `CREATE VIRTUAL TABLE ... USING fts5` echoue reellement avec
 * "no such module: fts5" (confirme par log reel, pas suppose). FTS5 n'est donc pas une
 * question de version Android : certains vendeurs (dont OnePlus/OxygenOS ici) ne compilent
 * tout simplement pas le module FTS5 dans leur SQLite systeme. Repli automatique implemente :
 * `open()` tente FTS5, et si ca echoue, bascule sur une table normale + recherche `LIKE`
 * (moins bon que BM25, mais fonctionne partout sans dependance externe).
 *
 * Objectif : soulager le contexte envoye au modele. Au lieu d'accumuler tout l'historique de
 * conversation (qui finirait par depasser la fenetre de contexte), chaque echange est indexe
 * ici, et seuls les passages les plus pertinents pour la question en cours sont retrouves et
 * injectes dans le prompt (cf. ReasoningController, a cabler).
 */
object RagStore {

    private const val DB_NAME = "philo_rag.db"

    // Kinds indexes
    const val KIND_CHAT = "chat"
    const val KIND_DOC = "doc"

    // Decoupage en passages : ~700 caracteres avec chevauchement pour ne pas couper une idee
    // en deux au milieu d'un passage retrouve.
    private const val CHUNK_SIZE = 700
    private const val CHUNK_OVERLAP = 120

    // Limite de retention pour l'historique de conversation indexe (evite une DB qui grossit
    // indefiniment sur un usage prolonge). Les documents importes sont purges par quota global.
    private const val MAX_CHAT_CHUNKS = 4000

    // Quota global de passages (chat + documents) : borne dure de la taille de la base pour
    // eviter une croissance incontrolee sur un usage prolonge. Au-dela, on purge les plus
    // anciens passages chat en priorite (les documents restent proteges tant que le quota
    // chat suffit).
    private const val MAX_TOTAL_CHUNKS = 20000

    // Version du schema (incluant la colonne doc_hash ajoutee en v2).
    private const val SCHEMA_VERSION = 2

    @Volatile private var db: SQLiteDatabase? = null
    @Volatile private var usesFts5: Boolean = true
    private val initLock = Any()

    private fun open(context: Context): SQLiteDatabase {
        db?.let { return it }
        synchronized(initLock) {
            db?.let { return it }
            val path = context.getDatabasePath(DB_NAME).absolutePath
            val database = SQLiteDatabase.openOrCreateDatabase(path, null)
            // Bug reel trouve en testant sur device : "CREATE VIRTUAL TABLE IF NOT EXISTS"
            // ne leve AUCUNE exception si une table du meme nom existe deja -- meme si
            // c'est la table de repli (non-FTS5) creee par un lancement precedent de l'app.
            // Sans verifier le schema EXISTANT d'abord, usesFts5 redevenait "true" a tort au
            // deuxieme lancement, et `rag_chunks MATCH ?` echouait silencieusement sur une
            // table normale (avale par le catch generique de search(), hits=0 sans log).
            val existingSql = database.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type IN ('table','view') AND name = 'rag_chunks'", null,
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            when {
                existingSql == null -> {
                    try {
                        database.execSQL(
                            "CREATE VIRTUAL TABLE rag_chunks USING fts5(" +
                                "content, source UNINDEXED, kind UNINDEXED, ts UNINDEXED, " +
                                "doc_hash UNINDEXED)"
                        )
                        usesFts5 = true
                    } catch (t: Throwable) {
                        // Confirme en conditions reelles sur OnePlus 15/OxygenOS 16 (API 36) :
                        // "no such module: fts5" -- le vendeur ne compile pas FTS5,
                        // independamment de la version Android.
                        android.util.Log.w("RagStore", "FTS5 indisponible (${t.message}) -- repli table normale + LIKE")
                        usesFts5 = false
                        database.execSQL(
                            "CREATE TABLE rag_chunks(content TEXT, source TEXT, kind TEXT, ts INTEGER, doc_hash TEXT)"
                        )
                        database.execSQL("CREATE INDEX IF NOT EXISTS idx_rag_chunks_kind ON rag_chunks(kind)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc_hash ON rag_chunks(doc_hash)")
                    }
                }
                existingSql.contains("fts5", ignoreCase = true) -> usesFts5 = true
                else -> usesFts5 = false
            }
            // Diagnostic : ne JAMAIS recreer/detruire silencieusement. Si le schema existant
            // est plus ancien (ex. v1 sans colonne doc_hash), on l'aligne en ajoutant la
            // colonne manquante -- perte de donnees nulle, contrairement a une migration
            // destructive. On ne migre que si la colonne est reellement absente (bug source
            // deja corrige dans le fichier d'origine : un ALTER inconditionnel faisait
            // echouer open() silencieusement a chaque appel sur une base deja migree).
            try {
                val hasDocHash = database.rawQuery("PRAGMA table_info(rag_chunks)", null).use { c ->
                    var found = false
                    while (c.moveToNext()) {
                        if (c.getString(1) == "doc_hash") { found = true; break }
                    }
                    found
                }
                if (!hasDocHash) {
                    database.execSQL("ALTER TABLE rag_chunks ADD COLUMN doc_hash TEXT")
                }
            } catch (t: Throwable) {
                android.util.Log.w("RagStore", "migration doc_hash ignoree : ${t.message}")
            }
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS rag_meta(key TEXT PRIMARY KEY, value TEXT)"
            )
            ensureSchemaVersion(database)
            db = database
            return database
        }
    }

    /** Enregistre la version de schema effective et journalise un diagnostic si elle evolue. */
    private fun ensureSchemaVersion(database: SQLiteDatabase) {
        try {
            val previous = database.rawQuery(
                "SELECT value FROM rag_meta WHERE key = 'schema_version'", null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (previous == null) {
                database.execSQL(
                    "INSERT OR REPLACE INTO rag_meta(key, value) VALUES('schema_version', ?)",
                    arrayOf(SCHEMA_VERSION.toString())
                )
            } else if (previous.toIntOrNull() ?: 0 < SCHEMA_VERSION) {
                database.execSQL(
                    "UPDATE rag_meta SET value = ? WHERE key = 'schema_version'",
                    arrayOf(SCHEMA_VERSION.toString())
                )
            }
        } catch (t: Throwable) {
            // Non bloquant : la version de schema est un diagnostic, pas un prerequis.
        }
    }

    /** Ferme proprement la base et la marque pour reouverture au prochain acces. */
    @Synchronized
    fun close() {
        synchronized(initLock) {
            db?.close()
            db = null
        }
    }

    /** Etat de sante/diagnostic de la base (taille, nombre de passages, version de schema). */
    @Synchronized
    fun diagnostics(context: Context): Map<String, Any> {
        return try {
            val database = open(context)
            val dbPath = context.getDatabasePath(DB_NAME)
            mapOf(
                "db_bytes" to (dbPath.length().coerceAtLeast(0)),
                "schema_version" to SCHEMA_VERSION,
                "total_chunks" to database.rawQuery("SELECT COUNT(*) FROM rag_chunks", null)
                    .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L },
                "path" to dbPath.absolutePath
            )
        } catch (t: Throwable) {
            mapOf("error" to (t.message ?: "unknown"))
        }
    }

    private fun sha256Hex(input: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun chunkText(text: String): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= CHUNK_SIZE) return listOf(clean)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            val end = (start + CHUNK_SIZE).coerceAtMost(clean.length)
            // Essaie de couper sur une fin de phrase/paragraphe proche de la limite plutot
            // qu'en plein milieu d'un mot.
            var cut = end
            if (end < clean.length) {
                val lastBreak = clean.lastIndexOf('\n', end).let { if (it > start) it else -1 }
                    .let { nl -> if (nl >= 0) nl else clean.lastIndexOf('.', end) }
                if (lastBreak > start + CHUNK_SIZE / 2) cut = lastBreak + 1
            }
            chunks += clean.substring(start, cut).trim()
            if (cut >= clean.length) break
            start = (cut - CHUNK_OVERLAP).coerceAtLeast(cut - CHUNK_SIZE).coerceAtLeast(0)
            if (start >= cut) start = cut
        }
        return chunks.filter { it.isNotBlank() }
    }

    /** Indexe un tour de conversation (message utilisateur ou reponse assistant). */
    @Synchronized
    fun indexChat(context: Context, convId: String, sender: String, text: String) {
        if (text.isBlank()) return
        try {
            val database = open(context)
            val ts = System.currentTimeMillis()
            database.beginTransaction()
            try {
                chunkText(text).forEach { chunk ->
                    val cv = ContentValues().apply {
                        put("content", chunk)
                        put("source", "$convId:$sender")
                        put("kind", KIND_CHAT)
                        put("ts", ts)
                    }
                    database.insert("rag_chunks", null, cv)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            pruneOldChat(database)
            pruneTotal(database)
        } catch (t: Throwable) {
            // Best-effort : l'indexation ne doit jamais faire planter le chat.
        }
    }

    /** Indexe un document importe (texte brut). Retourne le nombre de passages crees. */
    @Synchronized
    fun indexDocument(context: Context, name: String, text: String): Int {
        if (text.isBlank()) return 0
        return try {
            val database = open(context)
            val ts = System.currentTimeMillis()
            val chunks = chunkText(text)

            // Hash du document complet (contenu normalise) : permet de detecter une
            // re-importation du meme document et d'eviter les doublons. On saute l'insertion
            // si un document identique a deja ete indexe.
            val docHash = sha256Hex(text.trim())
            val exists = database.rawQuery(
                "SELECT COUNT(*) FROM rag_chunks WHERE kind = ? AND doc_hash = ? LIMIT 1",
                arrayOf(KIND_DOC, docHash)
            ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
            if (exists) return 0

            database.beginTransaction()
            try {
                chunks.forEach { chunk ->
                    val cv = ContentValues().apply {
                        put("content", chunk)
                        put("source", name)
                        put("kind", KIND_DOC)
                        put("ts", ts)
                        put("doc_hash", docHash)
                    }
                    database.insert("rag_chunks", null, cv)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            pruneTotal(database)
            chunks.size
        } catch (t: Throwable) {
            0
        }
    }

    private fun pruneTotal(database: SQLiteDatabase) {
        try {
            val total = database.rawQuery("SELECT COUNT(*) FROM rag_chunks", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (total <= MAX_TOTAL_CHUNKS) return
            var remaining = total - MAX_TOTAL_CHUNKS
            // Purge les passages chat les plus anciens d'abord (protege les documents).
            val chatDrop = database.rawQuery(
                "SELECT COUNT(*) FROM rag_chunks WHERE kind = ?", arrayOf(KIND_CHAT)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }.coerceAtMost(remaining)
            if (chatDrop > 0) {
                database.execSQL(
                    "DELETE FROM rag_chunks WHERE rowid IN (" +
                        "SELECT rowid FROM rag_chunks WHERE kind = '$KIND_CHAT' " +
                        "ORDER BY ts ASC LIMIT $chatDrop)"
                )
                remaining -= chatDrop
            }
            // Si le quota n'est toujours pas atteint, on purge alors les plus anciens
            // documents : c'est une borne dure (quota), pas une purge silencieuse de contenu.
            if (remaining > 0) {
                database.execSQL(
                    "DELETE FROM rag_chunks WHERE rowid IN (" +
                        "SELECT rowid FROM rag_chunks WHERE kind = '$KIND_DOC' " +
                        "ORDER BY ts ASC LIMIT $remaining)"
                )
            }
        } catch (t: Throwable) {
            // Non bloquant : au pire la DB continue de grossir un peu.
        }
    }

    private fun pruneOldChat(database: SQLiteDatabase) {
        try {
            val count = database.rawQuery(
                "SELECT COUNT(*) FROM rag_chunks WHERE kind = ?", arrayOf(KIND_CHAT)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (count <= MAX_CHAT_CHUNKS) return
            val toDrop = count - MAX_CHAT_CHUNKS
            database.execSQL(
                "DELETE FROM rag_chunks WHERE rowid IN (" +
                    "SELECT rowid FROM rag_chunks WHERE kind = '$KIND_CHAT' " +
                    "ORDER BY ts ASC LIMIT $toDrop)"
            )
        } catch (t: Throwable) {
            // Non bloquant : au pire la DB continue de grossir un peu.
        }
    }

    /** Construit une requete FTS5 sure a partir d'un texte libre (echappe la syntaxe MATCH). */
    private fun buildMatchQuery(freeText: String): String? {
        val terms = Regex("[\\p{L}\\p{Nd}]+")
            .findAll(freeText)
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()
        if (terms.isEmpty()) return null
        // Recherche par prefixe sur chaque terme, combinee en OR : tolerante aux variations
        // de formulation entre la question posee et le passage indexe.
        return terms.take(24).joinToString(" OR ") { "\"${it.replace("\"", "")}\"*" }
    }

    data class RagHit(val content: String, val source: String, val kind: String, val score: Double)

    /**
     * Recherche les [topK] passages les plus pertinents pour [query] (BM25, tous types
     * confondus par defaut ; [kind] pour restreindre a KIND_CHAT ou KIND_DOC).
     */
    @Synchronized
    fun search(
        context: Context,
        query: String,
        topK: Int = 8,
        kind: String? = null,
        offset: Int = 0
    ): List<RagHit> {
        // topK <= 0 ferait traiter "LIMIT 0"/"LIMIT -N" comme illimite par SQLite -- borne
        // explicitement (bug source deja corrige dans le fichier d'origine).
        val safeTopK = topK.coerceIn(1, 200)
        val safeOffset = offset.coerceAtLeast(0)
        return try {
            val database = open(context)
            if (usesFts5) {
                val match = buildMatchQuery(query) ?: return emptyList()
                val where = if (kind != null) "rag_chunks MATCH ? AND kind = ?" else "rag_chunks MATCH ?"
                val args = if (kind != null) arrayOf(match, kind) else arrayOf(match)
                database.rawQuery(
                    "SELECT content, source, kind, bm25(rag_chunks) AS score FROM rag_chunks " +
                        "WHERE $where ORDER BY score LIMIT ? OFFSET ?",
                    args + arrayOf(safeTopK.toString(), safeOffset.toString())
                ).use { c ->
                    val hits = mutableListOf<RagHit>()
                    while (c.moveToNext()) {
                        hits += RagHit(content = c.getString(0), source = c.getString(1), kind = c.getString(2), score = c.getDouble(3))
                    }
                    hits
                }
            } else {
                likeSearch(database, query, safeTopK, kind, safeOffset)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Repli sans FTS5 : chaque terme de la requete (>=2 caracteres) doit apparaitre
     * (LIKE %terme%, insensible a la casse via COLLATE NOCASE) dans le contenu. Score = nombre
     * de termes distincts trouves (plus c'est eleve, plus c'est pertinent) -- bien plus
     * grossier que BM25, mais fonctionne sans extension SQLite.
     */
    private fun likeSearch(database: SQLiteDatabase, query: String, topK: Int, kind: String?, offset: Int): List<RagHit> {
        val terms = Regex("[\\p{L}\\p{Nd}]+").findAll(query).map { it.value }.filter { it.length >= 2 }.take(24).toList()
        if (terms.isEmpty()) return emptyList()
        val scoreExpr = terms.joinToString(" + ") { "(CASE WHEN content LIKE ? COLLATE NOCASE THEN 1 ELSE 0 END)" }
        val likeArgs = terms.map { "%$it%" }
        val whereKind = if (kind != null) " WHERE kind = ?" else ""
        // Sous-requete plutot que HAVING sans GROUP BY : SQLite rejette "HAVING" sur une
        // requete non agregee ("HAVING clause on a non-aggregate query") -- erreur reelle
        // trouvee en reproduisant sur le fichier .db tire du device (la requete originale
        // echouait silencieusement, avalee par le catch generique de search()).
        val sql = "SELECT content, source, kind, score FROM (" +
            "SELECT content, source, kind, ($scoreExpr) AS score FROM rag_chunks$whereKind" +
            ") WHERE score > 0 ORDER BY score DESC LIMIT ? OFFSET ?"
        val args = likeArgs.toTypedArray() + (if (kind != null) arrayOf(kind) else emptyArray()) +
            arrayOf(topK.toString(), offset.toString())
        android.util.Log.i("RagStore", "likeSearch sql=$sql args=${args.toList()}")
        return database.rawQuery(sql, args).use { c ->
            val hits = mutableListOf<RagHit>()
            while (c.moveToNext()) {
                hits += RagHit(content = c.getString(0), source = c.getString(1), kind = c.getString(2), score = c.getDouble(3))
            }
            hits
        }
    }

    data class RagStats(val docCount: Int, val docChunks: Int, val chatChunks: Int)

    @Synchronized
    fun stats(context: Context): RagStats {
        return try {
            val database = open(context)
            val docChunks = database.rawQuery(
                "SELECT COUNT(*) FROM rag_chunks WHERE kind = ?", arrayOf(KIND_DOC)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val chatChunks = database.rawQuery(
                "SELECT COUNT(*) FROM rag_chunks WHERE kind = ?", arrayOf(KIND_CHAT)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val docCount = database.rawQuery(
                "SELECT COUNT(DISTINCT source) FROM rag_chunks WHERE kind = ?", arrayOf(KIND_DOC)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            RagStats(docCount, docChunks, chatChunks)
        } catch (t: Throwable) {
            RagStats(0, 0, 0)
        }
    }

    @Synchronized
    fun clearDocs(context: Context) {
        try {
            open(context).delete("rag_chunks", "kind = ?", arrayOf(KIND_DOC))
        } catch (t: Throwable) {
            // no-op
        }
    }
}
