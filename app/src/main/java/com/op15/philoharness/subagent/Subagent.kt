package com.op15.philoharness.subagent

import com.op15.philoharness.tools.PermissionMode
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Inspire de Sanuu7/AndroidHarness (verifie reel, README : "The task tool spawns
 * subagents that work in parallel on independent chunks ... They research
 * read-only by default; with subagent action tools enabled they also edit
 * files and run commands in Act mode, always under the current permission
 * mode"). Ported ici avec le meme defaut de securite : lecture seule sauf
 * activation explicite.
 */
enum class SubagentMode { RESEARCH_READ_ONLY, ACT }

data class SubagentSpec(
    val id: String,
    val task: String,
    val mode: SubagentMode = SubagentMode.RESEARCH_READ_ONLY,
    /**
     * Mode DEMANDE pour ce sous-agent -- une simple donnee, PAS une preuve que la permission
     * effective a ete calculee (angle mort reel trouve en auditant cette session, OWASP LLM06 :
     * "le mode effectif doit etre calcule par le controleur parent, pas accepte tel quel depuis
     * une specification"). `spawnParallel` ignore ce champ pour le calcul reel et le reclampe
     * toujours depuis le mode du parent -- ce champ ne sert qu'a exprimer l'INTENTION du
     * createur de la spec, jamais directement consomme comme permission finale.
     */
    val inheritedPermissionMode: PermissionMode,
    val modelOverride: String? = null,
)

sealed interface SubagentOutcome {
    data class Completed(val findings: String) : SubagentOutcome
    data class Failed(val reason: String) : SubagentOutcome
}

/**
 * Coordonne des sous-agents independants en parallele. Reste volontairement
 * minimal : la vraie execution (appel modele, outils) est deleguee a
 * `runner`, cette classe ne fait qu'appliquer les regles d'heritage de mode et
 * de permission -- jamais de sous-agent plus permissif que son parent.
 */
class SubagentCoordinator(
    private val runner: suspend (SubagentSpec) -> SubagentOutcome,
) {
    /**
     * Lance tous les sous-agents REELLEMENT en parallele (angle mort corrige, 2026-10-15 :
     * avant, cette methode s'appelait "spawnParallel" mais executait les taches en boucle
     * sequentielle -- le nom promettait une propriete que le code ne tenait pas). Le mode de
     * permission effectif de CHAQUE sous-agent est calcule ICI, depuis [parentPermissionMode],
     * jamais fait confiance au champ `inheritedPermissionMode` de la spec (qui peut avoir ete
     * construit par n'importe quel appelant, y compris indirectement par un LLM) -- corrige un
     * deuxieme angle mort reel : `clampPermission` existait mais n'etait jamais appelee.
     */
    suspend fun spawnParallel(specs: List<SubagentSpec>, parentPermissionMode: PermissionMode): Map<String, SubagentOutcome> {
        require(specs.map { it.id }.distinct().size == specs.size) { "ids de sous-agents dupliques" }
        val clampedSpecs = specs.map { spec ->
            spec.copy(inheritedPermissionMode = clampPermission(parentPermissionMode, spec.inheritedPermissionMode))
        }
        return coroutineScope {
            val deferredById = clampedSpecs.associate { spec -> spec.id to async { runner(spec) } }
            deferredById.mapValues { (_, deferred) -> deferred.await() }
        }
    }

    /**
     * Un sous-agent ne peut jamais recevoir un mode de permission plus large que son parent.
     *
     * Ordre de permissivite CROISSANTE -- corrige un bug reel trouve en auditant cette session :
     * l'ordre precedent placait ASK_FOR_NEW_TOOLS_ONLY en dessous de ASK_EACH_TIME. Or
     * ASK_FOR_NEW_TOOLS_ONLY accorde une permission PERSISTANTE des le premier appel reussi
     * (GatedToolLoop.grantedTools, plus jamais redemandee ensuite dans la meme session) alors
     * qu'ASK_EACH_TIME ne grante jamais rien durablement -- en autonomie cumulee reelle,
     * ASK_FOR_NEW_TOOLS_ONLY est donc PLUS permissif, pas moins. Avec l'ancien ordre, un parent
     * en ASK_EACH_TIME aurait pu "clamper" un enfant demandant ASK_FOR_NEW_TOOLS_ONLY comme
     * acceptable (car juge moins permissif), alors que c'est reellement une escalade de
     * privilege cumulatif. Jamais exploitable avant ce correctif puisque `clampPermission`
     * n'etait jamais appelee -- mais corrige maintenant qu'elle l'est.
     */
    fun clampPermission(parentMode: PermissionMode, requestedChildMode: PermissionMode): PermissionMode {
        val order = listOf(
            PermissionMode.READ_ONLY,
            PermissionMode.ASK_EACH_TIME,
            PermissionMode.ASK_FOR_NEW_TOOLS_ONLY,
            PermissionMode.FULL_ACCESS,
        )
        val parentRank = order.indexOf(parentMode)
        val requestedRank = order.indexOf(requestedChildMode)
        return if (requestedRank > parentRank) parentMode else requestedChildMode
    }
}
