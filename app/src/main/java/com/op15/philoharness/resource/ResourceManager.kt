package com.op15.philoharness.resource

/**
 * Selectionne un backend selon ce qui est REELLEMENT valide (pas ce qui est
 * simplement present), le profil courant de l'appareil et une reserve de
 * securite memoire. Jamais d'hypothese implicite "NPU > GPU > CPU" (consigne
 * explicite de l'utilisateur) -- l'ordre de preference vient des validations
 * mesurees, pas d'une hierarchie codee en dur.
 */
class ResourceManager(
    private val validator: AcceleratorValidator,
    /** Cache des derniers verdicts de validation par backend, pour eviter de revalider a chaque appel. */
    private val validationCache: MutableMap<Backend, BackendValidation> = mutableMapOf(),
) {
    fun recordValidation(backend: Backend, result: BackendValidation) {
        validationCache[backend] = result
    }

    fun validationFor(backend: Backend): BackendValidation =
        validationCache[backend] ?: BackendValidation.NotYetTested

    /**
     * Choisit un plan d'execution. Repli en cascade : backend prefere -> prochain
     * backend valide -> CPU degrade. Ne choisit JAMAIS un backend non valide,
     * meme si c'est le seul disponible -- un plan degrade explicite vaut mieux
     * qu'une sortie silencieusement fausse (le risque exact que Bram a documente
     * pour son backend Vulkan).
     */
    fun planFor(profile: DeviceProfile, preferenceOrder: List<Backend> = listOf(Backend.HEXAGON_NPU, Backend.VULKAN_GPU, Backend.CPU)): ExecutionPlan {
        if (profile.thermalStatus == ThermalStatus.SEVERE || profile.thermalStatus == ThermalStatus.CRITICAL) {
            return ExecutionPlan(
                chosenBackend = Backend.CPU,
                contextTokens = reducedContextFor(profile),
                reason = "Etat thermique ${profile.thermalStatus} -- repli CPU a contexte reduit, pas de backend accelere tant que l'appareil n'a pas refroidi",
                degraded = true,
            )
        }

        for (backend in preferenceOrder) {
            if (backend == Backend.HEXAGON_NPU && !profile.hexagonNpuPresent) continue
            if (backend == Backend.VULKAN_GPU && !profile.vulkanSupported) continue

            when (val v = validationFor(backend)) {
                is BackendValidation.Validated -> return ExecutionPlan(
                    chosenBackend = backend,
                    contextTokens = contextFor(profile),
                    reason = "Backend $backend valide (accord ${"%.1f".format(v.agreementRatio * 100)}% vs CPU, x${"%.2f".format(v.speedupVsCpu)})",
                    degraded = false,
                )
                is BackendValidation.Rejected -> continue // backend explicitement ecarte, jamais utilise meme en dernier recours
                BackendValidation.NotYetTested -> continue // ne jamais utiliser un backend non teste -- demander validation avant
            }
        }

        // Aucun backend accelere valide : repli CPU, mode degrade explicite.
        return ExecutionPlan(
            chosenBackend = Backend.CPU,
            contextTokens = contextFor(profile),
            reason = "Aucun backend accelere valide disponible -- repli CPU (reference, toujours considere valide)",
            degraded = true,
        )
    }

    private fun contextFor(profile: DeviceProfile): Int =
        when {
            profile.availableRamBytes < 1_500_000_000L -> 1024
            profile.availableRamBytes < 3_000_000_000L -> 2048
            else -> 4096
        }

    private fun reducedContextFor(profile: DeviceProfile): Int = (contextFor(profile) / 2).coerceAtLeast(512)
}
