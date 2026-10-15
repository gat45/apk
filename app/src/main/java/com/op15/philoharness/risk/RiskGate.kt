package com.op15.philoharness.risk

/**
 * Inspire de SeerGuard (arXiv:2607.15550, verifie reel dans l'audit) : predire
 * l'etat consequent d'une action AVANT de l'executer, et bloquer si le risque
 * est trop eleve par rapport a l'utilite attendue -- plutot que de decouvrir
 * les degats apres coup. SeerGuard predit un etat d'interface complet via un
 * world model ; cette classe n'a PAS cette capacite (hors de portee ici, voir
 * audit Livrable 3) -- elle applique seulement une regle deterministe simple :
 * reversibilite + confiance, pas une prediction d'etat.
 */
enum class Reversibility { REVERSIBLE, PARTIALLY_REVERSIBLE, IRREVERSIBLE }

data class ProposedAction(
    val description: String,
    val reversibility: Reversibility,
    /** Confiance du modele dans le resultat attendu, 0.0-1.0 -- jamais inventee, vient du controleur (brique B). */
    val modelConfidence: Double,
)

sealed interface RiskVerdict {
    object Allow : RiskVerdict
    data class RequireConfirmation(val reason: String) : RiskVerdict
    data class Block(val reason: String) : RiskVerdict
}

class RiskGate(
    private val irreversibleConfidenceThreshold: Double = 0.85,
    private val partiallyReversibleConfidenceThreshold: Double = 0.60,
) {
    fun assess(action: ProposedAction): RiskVerdict = when (action.reversibility) {
        Reversibility.REVERSIBLE -> RiskVerdict.Allow

        Reversibility.PARTIALLY_REVERSIBLE ->
            if (action.modelConfidence >= partiallyReversibleConfidenceThreshold) {
                RiskVerdict.Allow
            } else {
                RiskVerdict.RequireConfirmation(
                    "Action partiellement reversible avec confiance ${"%.2f".format(action.modelConfidence)} " +
                        "< seuil ${"%.2f".format(partiallyReversibleConfidenceThreshold)}",
                )
            }

        Reversibility.IRREVERSIBLE ->
            if (action.modelConfidence >= irreversibleConfidenceThreshold) {
                RiskVerdict.RequireConfirmation(
                    "Action irreversible -- confirmation humaine toujours requise quelle que soit la confiance (${"%.2f".format(action.modelConfidence)})",
                )
            } else {
                RiskVerdict.Block(
                    "Action irreversible avec confiance insuffisante (${"%.2f".format(action.modelConfidence)} " +
                        "< seuil ${"%.2f".format(irreversibleConfidenceThreshold)}) -- bloquee, pas seulement deconseillee",
                )
            }
    }
}
