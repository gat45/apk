package com.op15.philoharness.arguments

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Statuts epistemiques distincts d'un booleen vrai/faux (voir audit, Livrable 4).
 */
enum class EpistemicStatus {
    SOUTENU,
    REFUTE,
    CONTESTE,
    INDETERMINE,
    OBSOLETE,
    EN_ATTENTE_DE_VERIFICATION,
}

enum class ReasoningType {
    DEDUCTIF,
    INDUCTIF,
    ABDUCTIF,
    ANALOGIQUE,
    MORAL,
}

/**
 * Enregistrement du "registre d'arguments structure" demande par l'audit (Livrable 4).
 * Chaque champ correspond a un point explicitement requis : premisses, hypotheses,
 * objections, sources, limites de formalisation, historique des revisions.
 */
@Entity(
    tableName = "arguments",
    indices = [Index("statut"), Index("validFrom"), Index("invalidatedAt")],
)
data class ArgumentEntity(
    @PrimaryKey val id: String,
    val questionOrigine: String,
    val texteOriginal: String,
    /** JSON-encoded map concept -> definition. */
    val definitionsJson: String,
    /** JSON-encoded list of explicit premises. */
    val premissesExplicitesJson: String,
    /** JSON-encoded list of implicit premises inferred by the system. */
    val premissesImplicitesJson: String,
    val conclusion: String,
    val typeRaisonnement: ReasoningType,
    /** JSON-encoded list of assumptions the conclusion depends on. */
    val hypothesesJson: String,
    val limitesFormalisation: String?,
    val statut: EpistemicStatus,
    val niveauConfianceJustification: String,
    val createdAtEpochMs: Long,
    /**
     * Modele bitemporel leger (inspire de Graphiti, sans dependance graphe externe,
     * voir audit section "Strategie de memoire") : periode de validite d'un fait/argument.
     */
    val validFrom: Long,
    val invalidatedAt: Long? = null,
    val invalidatedByArgumentId: String? = null,
    /**
     * Nom de com.op15.philoharness.control.ReasoningPhase (stocke en String pour
     * eviter une dependance Room du module `arguments` vers `control`). Persiste
     * ici -- pas en champ d'instance du Worker -- pour qu'un worker tue et
     * relance par WorkManager reprenne a la bonne phase plutot que de repartir
     * de NEEDS_REASONING (limite notee dans le README, desormais corrigee).
     */
    val reasoningPhase: String = "NEEDS_REASONING",
    /**
     * Nom de com.op15.philoharness.control.AbstentionReason (meme motivation
     * String que reasoningPhase) : derniere raison d'abstention rencontree,
     * pour que l'UI puisse expliquer un statut non tranche au lieu de laisser
     * "(pas encore de conclusion)" sans justification (angle mort note dans
     * TODO.md, section "Etats d'erreur visibles").
     */
    val lastAbstentionReason: String? = null,
    /**
     * Sortie brute de la derniere invocation d'outil (git status/diff pour
     * l'instant, lecture seule -- cf. GitToolExecutor). Meme motivation que
     * lastAbstentionReason : rendre visible ce qui s'est reellement passe
     * plutot que de laisser un resultat d'outil invisible une fois execute.
     */
    val lastToolOutput: String? = null,
    /**
     * Validation humaine obligatoire pour les outils IRREVERSIBLE (git_commit,
     * screen_tap, screen_input -- cf. PhiloReasoningWorker.irreversibleTools) :
     * remplace le palier RiskGate "Block" (qui ne laissait jamais ces outils
     * s'executer, meme avec l'accord de l'utilisateur) par un vrai clic de
     * confirmation dans l'UI (MainActivity). Tant que pendingTool != null et
     * ni confirme ni refuse, le Worker s'arrete et attend -- jamais de
     * contournement silencieux de ce palier.
     */
    val pendingTool: String? = null,
    /** Args de l'outil en attente, encodes "cle=valeur,cle2=valeur2" (meme format que ReasoningController.parseToolRequest). */
    val pendingToolArgsJson: String? = null,
    val pendingToolConfirmed: Boolean = false,
    val pendingToolRefused: Boolean = false,
    /**
     * Formalisation SMT-LIB2 + verdict brut de la derniere verification independante
     * (PhiloReasoningWorker.verifyIndependently) -- calcules mais jamais persistes avant
     * (TODO.md "Pas de clic sur un item de la liste" : aucune vue detail n'existait pour
     * les exposer). null tant qu'aucune verification n'a eu lieu, ou si la formalisation
     * a echoue (jamais une valeur inventee).
     */
    val lastSmtLib2: String? = null,
    val lastSolverVerdict: String? = null,
    val lastSolverDetail: String? = null,
    val lastSolverUsed: String? = null,
)

@Entity(
    tableName = "objections",
    foreignKeys = [
        ForeignKey(
            entity = ArgumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetArgumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("targetArgumentId")],
)
data class ObjectionEntity(
    @PrimaryKey val id: String,
    /** Chaque objection est reliee a l'argument exact qu'elle conteste. */
    val targetArgumentId: String,
    val texte: String,
    val contreExemple: String?,
    val createdAtEpochMs: Long,
)

enum class EvidenceKind {
    FICHIER,
    GIT_DIFF,
    COMMANDE,
    MESURE,
    SOLVEUR_FORMEL,
    SOURCE_EXTERNE,
}

/**
 * Preuve deterministe zero-LLM (meme contrat que jarvix-memory `verify_auto`).
 */
@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = ArgumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["argumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("argumentId")],
)
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val argumentId: String,
    val kind: EvidenceKind,
    val detail: String,
    @ColumnInfo(name = "verified") val verified: Boolean,
    val sourceUrl: String?,
    val recordedAtEpochMs: Long,
)

@Entity(tableName = "revisions")
data class RevisionEntity(
    @PrimaryKey val id: String,
    val argumentId: String,
    val champModifie: String,
    val ancienneValeur: String,
    val nouvelleValeur: String,
    val raison: String,
    val atEpochMs: Long,
)
