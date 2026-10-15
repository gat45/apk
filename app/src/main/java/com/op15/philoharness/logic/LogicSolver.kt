package com.op15.philoharness.logic

/**
 * Verdict d'une tentative de verification formelle. "INCONNU" couvre a la fois
 * l'indecidabilite (quantificateurs non bornes, voir audit Livrable 2/3) et un
 * echec d'infrastructure (solveur indisponible sur ce device) -- les deux sont
 * distincts d'une refutation et ne doivent JAMAIS etre presentes comme REFUTE.
 */
enum class SolverVerdict { SATISFIABLE, INSATISFIABLE, INCONNU }

data class SolverResult(
    val verdict: SolverVerdict,
    /** Modele satisfaisant ou noyau d'insatisfiabilite, selon le solveur. Jamais invente si absent. */
    val detail: String?,
    val solverUsed: String,
)

/**
 * Abstraction volontaire : l'audit (section "Portage Android non prouve de Z3",
 * Angle mort n°8) n'a trouve AUCUNE preuve tierce de build Z3 fonctionnel sur
 * Android/Bionic -- seul un CI arm64-v8a recent et non confirme stable. Le reste
 * du harnais ne doit jamais dependre directement de Z3 : il depend de cette
 * interface, dont l'implementation peut basculer sur le fallback sans casser
 * les appelants (contrat explicitement demande par le plan d'implementation,
 * tache 1).
 */
interface LogicSolver {
    /** @param smtLib2 formule(s) en syntaxe SMT-LIB2. */
    fun solve(smtLib2: String): SolverResult

    /** true si le solveur reel (Z3 natif) est disponible sur ce device, false => fallback actif. */
    fun isNativeSolverAvailable(): Boolean
}

/**
 * Pont JNI vers Z3 natif. NON VALIDE EMPIRIQUEMENT (spike technique = tache 1 du
 * plan d'implementation, audit Livrable 5). Ne pas appeler en production avant
 * d'avoir confirme la compilation arm64-v8a et teste solve() sur des cas connus.
 */
class Z3JniSolver : LogicSolver {
    private val nativeLoaded: Boolean = tryLoadNativeLibrary()

    private fun tryLoadNativeLibrary(): Boolean = try {
        System.loadLibrary("z3_jni_bridge") // libz3_jni_bridge.so, produit par le spike (cmake/app/src/main/cpp)
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    override fun isNativeSolverAvailable(): Boolean = nativeLoaded

    /**
     * Implementee cote natif (z3_jni_bridge.cpp). Retourne toujours "sat" / "unsat" /
     * "unknown" -- jamais une exception propagee pour une formule malformee (le pont
     * natif capture et retombe sur "unknown").
     */
    private external fun nativeSolve(smtLib2: String): String

    override fun solve(smtLib2: String): SolverResult {
        if (!nativeLoaded) {
            return SolverResult(SolverVerdict.INCONNU, "libz3_jni_bridge indisponible sur ce device", "z3-jni(absent)")
        }
        val raw = nativeSolve(smtLib2)
        val verdict = when (raw) {
            "sat" -> SolverVerdict.SATISFIABLE
            "unsat" -> SolverVerdict.INSATISFIABLE
            else -> SolverVerdict.INCONNU
        }
        return SolverResult(verdict, detail = raw, solverUsed = "z3-jni")
    }
}

/**
 * Verifications deterministes "maison" (meme philosophie que jarvix-memory
 * `verify_auto` : zero-LLM, oracles externes au raisonnement). Couvre uniquement
 * des contraintes explicitement codees -- PAS une logique de premier ordre
 * generale. Sert de filet de securite si Z3JniSolver reste indisponible.
 */
class RuleBasedFallbackSolver(
    private val rules: List<(Map<String, Boolean>) -> Boolean> = emptyList(),
) : LogicSolver {
    override fun isNativeSolverAvailable(): Boolean = false

    override fun solve(smtLib2: String): SolverResult =
        SolverResult(
            verdict = SolverVerdict.INCONNU,
            detail = "Pas de solveur SMT general embarque -- seules des regles explicites " +
                "peuvent etre evaluees via evaluateRule(), pas une formule SMT-LIB arbitraire.",
            solverUsed = "rule-based-fallback",
        )

    /** Evalue une regle deterministe explicite plutot qu'une formule SMT generale. */
    fun evaluateRule(ruleIndex: Int, assignment: Map<String, Boolean>): Boolean? =
        rules.getOrNull(ruleIndex)?.invoke(assignment)
}

/**
 * Point d'entree unique utilise par le reste du harnais. Bascule automatiquement
 * sur le fallback si le natif n'est pas disponible -- l'appelant ne sait jamais
 * si c'est Z3 ou les regles maison qui ont repondu, seulement `solverUsed`.
 */
class SwitchingLogicSolver(
    private val native: LogicSolver = Z3JniSolver(),
    private val fallback: LogicSolver = RuleBasedFallbackSolver(),
) : LogicSolver {
    override fun isNativeSolverAvailable(): Boolean = native.isNativeSolverAvailable()

    override fun solve(smtLib2: String): SolverResult =
        if (native.isNativeSolverAvailable()) native.solve(smtLib2) else fallback.solve(smtLib2)
}
