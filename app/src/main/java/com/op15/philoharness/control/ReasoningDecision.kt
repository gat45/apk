package com.op15.philoharness.control

/**
 * Espace de decision du controleur, directement traduit des invariants P0
 * d'op15-adaptive-harness (`ARCHITECTURE.md`, `src/action.rs`) : le LLM ne
 * produit jamais ANSWER seul, une preuve independante est requise avant.
 */
sealed interface ReasoningDecision {
    data class Retrieve(val query: String) : ReasoningDecision
    data class Reason(val step: String) : ReasoningDecision
    data class UseTool(val toolName: String, val args: Map<String, String>) : ReasoningDecision
    data class Verify(val argumentId: String) : ReasoningDecision
    data class Answer(val argumentId: String, val independentProofRef: String) : ReasoningDecision
    data class Abstain(val reason: AbstentionReason) : ReasoningDecision
    object Stop : ReasoningDecision
}

/**
 * Le controleur doit pouvoir refuser de conclure (audit Livrable 4, section
 * "Boucle de controle OP15") -- ces raisons sont exhaustives et explicites,
 * jamais un simple null/false.
 */
enum class AbstentionReason {
    DEFINITIONS_INSUFFISANTES,
    PREMISSES_INCOMPATIBLES,
    SOURCES_INSUFFISANTES,
    SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT,
    TRADUCTION_LOGIQUE_NON_FIABLE,
    ARGUMENTS_CONCURRENTS_RAISONNABLES,
    DEPEND_D_UN_CHOIX_DE_VALEURS_NON_SPECIFIE,
    BUDGET_EPUISE,
}

data class ReasoningBudget(
    val maxSteps: Int,
    val maxWallMs: Long,
    var stepsUsed: Int = 0,
    var wallMsUsed: Long = 0,
) {
    fun exhausted(): Boolean = stepsUsed >= maxSteps || wallMsUsed >= maxWallMs
}

/** Trace minimale, calquee sur le format JSON d'op15-adaptive-harness (`ARCHITECTURE.md`). */
data class DecisionTrace(
    val taskId: String,
    val strategy: String,
    val decision: String,
    val verified: Boolean,
    val backend: String,
    val wallMs: Long,
    val tokens: Int,
    val toolCalls: Int,
    val outcome: String, // "success" | "failure" | "timeout" | "abstain"
)
