package com.op15.philoharness.arguments

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArgumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArgument(argument: ArgumentEntity)

    @Update
    suspend fun updateArgument(argument: ArgumentEntity)

    @Query("SELECT * FROM arguments WHERE id = :id")
    suspend fun getArgument(id: String): ArgumentEntity?

    @Query("SELECT * FROM arguments WHERE invalidatedAt IS NULL ORDER BY validFrom DESC")
    fun observeActiveArguments(): Flow<List<ArgumentEntity>>

    @Query("SELECT * FROM arguments WHERE statut = :status")
    suspend fun getByStatus(status: EpistemicStatus): List<ArgumentEntity>

    @Insert
    suspend fun insertObjection(objection: ObjectionEntity)

    @Query("SELECT * FROM objections WHERE targetArgumentId = :argumentId")
    suspend fun getObjectionsFor(argumentId: String): List<ObjectionEntity>

    @Insert
    suspend fun insertEvidence(evidence: EvidenceEntity)

    @Query("SELECT * FROM evidence WHERE argumentId = :argumentId")
    suspend fun getEvidenceFor(argumentId: String): List<EvidenceEntity>

    @Insert
    suspend fun insertRevision(revision: RevisionEntity)

    /**
     * Invalidation bitemporelle (Livrable 4 / 5 tache 6) : ne supprime jamais l'ancien
     * enregistrement. Un fait contredit plus tard reste trace, marque invalide, et lie
     * a l'argument qui l'a contredit -- jamais de suppression silencieuse (Angle mort
     * "Memoire sans revision temporelle de croyances").
     */
    @Transaction
    suspend fun invalidate(argumentId: String, invalidatedByArgumentId: String, atEpochMs: Long) {
        val current = getArgument(argumentId) ?: return
        if (current.invalidatedAt != null) return // deja invalide : on ne reecrit pas l'histoire
        val updated = current.copy(
            invalidatedAt = atEpochMs,
            invalidatedByArgumentId = invalidatedByArgumentId,
            statut = EpistemicStatus.CONTESTE,
        )
        updateArgument(updated)
        insertRevision(
            RevisionEntity(
                id = "${argumentId}_inv_$atEpochMs",
                argumentId = argumentId,
                champModifie = "invalidatedAt/statut",
                ancienneValeur = "null/${current.statut}",
                nouvelleValeur = "$atEpochMs/CONTESTE",
                raison = "Invalide par argument $invalidatedByArgumentId",
                atEpochMs = atEpochMs,
            ),
        )
    }

    @Query("UPDATE arguments SET statut = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: EpistemicStatus)

    @Query("UPDATE arguments SET lastAbstentionReason = :reason WHERE id = :id")
    suspend fun updateAbstentionReason(id: String, reason: String?)

    @Query("UPDATE arguments SET lastToolOutput = :output WHERE id = :id")
    suspend fun updateToolOutput(id: String, output: String?)

    /**
     * Suppression depuis l'UI : jamais un DELETE SQL (meme discipline que [invalidate],
     * rien n'est jamais detruit -- juste sorti de observeActiveArguments). Pas
     * d'invalidatedByArgumentId ici : contrairement a [invalidate], ce n'est pas un
     * autre argument qui contredit celui-ci, c'est un retrait volontaire de l'utilisateur.
     */
    @Query("UPDATE arguments SET invalidatedAt = :atEpochMs WHERE id = :id AND invalidatedAt IS NULL")
    suspend fun softDelete(id: String, atEpochMs: Long)

    /** Trace de la derniere verification independante -- pour la vue detail (TODO.md). */
    @Query(
        "UPDATE arguments SET lastSmtLib2 = :smtLib2, lastSolverVerdict = :verdict, " +
            "lastSolverDetail = :detail, lastSolverUsed = :solverUsed WHERE id = :id",
    )
    suspend fun updateVerificationTrace(id: String, smtLib2: String?, verdict: String?, detail: String?, solverUsed: String?)

    /** Enregistre une demande d'outil IRREVERSIBLE en attente de validation humaine (jamais auto-confirmee). */
    @Query(
        "UPDATE arguments SET pendingTool = :tool, pendingToolArgsJson = :argsJson, " +
            "pendingToolConfirmed = 0, pendingToolRefused = 0 WHERE id = :id",
    )
    suspend fun setPendingTool(id: String, tool: String, argsJson: String)

    @Query("UPDATE arguments SET pendingToolConfirmed = 1 WHERE id = :id")
    suspend fun confirmPendingTool(id: String)

    @Query("UPDATE arguments SET pendingToolRefused = 1 WHERE id = :id")
    suspend fun refusePendingTool(id: String)

    @Query(
        "UPDATE arguments SET pendingTool = NULL, pendingToolArgsJson = NULL, " +
            "pendingToolConfirmed = 0, pendingToolRefused = 0 WHERE id = :id",
    )
    suspend fun clearPendingTool(id: String)
}
