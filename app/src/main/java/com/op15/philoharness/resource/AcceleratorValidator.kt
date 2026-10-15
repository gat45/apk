package com.op15.philoharness.resource

/**
 * Reproduit le mecanisme central de KuRue/bram : un backend n'est marque
 * "utilisable" que s'il reproduit une sortie de reference calculee sur CPU, sur
 * un petit lot de cas connus. Chiffre reel observe par Bram sur un Snapdragon 8
 * Elite (README, verifie dans l'audit) : Hexagon NPU valide a 95.8% d'accord
 * avec le CPU (1.5-1.9x plus rapide) ; Vulkan REJETE car il divergeait sur 25%
 * des predictions des la premiere couche deportee, et s'effondrait totalement
 * au-dela de 7 couches -- sans jamais remonter d'erreur. C'est exactement le
 * scenario que cette classe doit detecter plutot que de faire confiance a
 * l'initialisation reussie d'un backend.
 */
interface ReferenceTask {
    /** Execute la tache sur le backend donne et retourne un resultat comparable (ex. logits, hash de sortie). */
    fun run(backend: Backend): FloatArray
}

class AcceleratorValidator(
    private val minAgreementRatio: Double = 0.90,
) {
    /**
     * @param referenceTasks petit lot de cas connus (3-10 tache, pas tout un benchmark).
     * @return le verdict de validation pour ce backend, jamais "Validated" sans comparaison explicite au CPU.
     */
    fun validate(backend: Backend, referenceTasks: List<ReferenceTask>, startedAtEpochMs: Long, endedAtEpochMs: Long): BackendValidation {
        if (backend == Backend.CPU) {
            // Le CPU EST la reference : il n'est jamais "valide contre lui-meme" au sens de ce mecanisme.
            return BackendValidation.Validated(agreementRatio = 1.0, speedupVsCpu = 1.0)
        }
        if (referenceTasks.isEmpty()) {
            return BackendValidation.Rejected("Aucune tache de reference fournie -- impossible de valider sans comparaison", null)
        }

        var agreeing = 0
        for (task in referenceTasks) {
            val cpuOutput = task.run(Backend.CPU)
            val candidateOutput = task.run(backend)
            if (outputsAgree(cpuOutput, candidateOutput)) agreeing += 1
        }
        val ratio = agreeing.toDouble() / referenceTasks.size

        return if (ratio >= minAgreementRatio) {
            val elapsedMs = (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(1)
            BackendValidation.Validated(agreementRatio = ratio, speedupVsCpu = estimateSpeedup(elapsedMs))
        } else {
            BackendValidation.Rejected(
                reason = "Desaccord ${"%.1f".format(ratio * 100)}% avec la reference CPU (seuil ${"%.0f".format(minAgreementRatio * 100)}%) -- backend NON valide, meme si l'initialisation a reussi",
                agreementRatio = ratio,
            )
        }
    }

    private fun outputsAgree(reference: FloatArray, candidate: FloatArray, tolerance: Float = 1e-2f): Boolean {
        if (reference.size != candidate.size) return false
        for (i in reference.indices) {
            if (kotlin.math.abs(reference[i] - candidate[i]) > tolerance) return false
        }
        return true
    }

    // Place-holder : le vrai calcul de speedup vient de mesures E2E separees, pas de cette seule methode.
    private fun estimateSpeedup(elapsedMs: Long): Double = 1.0
}
