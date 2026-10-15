package com.op15.philoharness.formalization

/**
 * Traducteur NL -> SMT-LIB2 deterministe (pas de LLM), tache 5 du plan
 * d'implementation : "test de fidelite round-trip NL->logique". Couvre
 * volontairement un sous-ensemble tres restreint de la logique propositionnelle
 * -- jamais de pretention a couvrir la FOL generale (cf. audit, angle mort
 * "mauvaise formalisation"). Chaque echec de reconnaissance retourne
 * [FormalizationResult.Unformalizable] plutot que de forcer une traduction
 * fausse : mieux vaut INDETERMINE que silencieusement incorrect.
 */
data class Proposition(val id: String, val naturalLanguage: String)

sealed interface FormalizationResult {
    data class Formalized(
        val smtLib2: String,
        val propositions: List<Proposition>,
        /** Rejoue la formule en NL pour le test de fidelite round-trip (tache 5). */
        val backTranslation: String,
    ) : FormalizationResult

    data class Unformalizable(val reason: String) : FormalizationResult
}

/**
 * Formalisation d'un ARGUMENT complet (premisses + conclusion), pas d'une phrase isolee --
 * angle mort reel trouve en auditant cette session : `verifyIndependently()` ne formalisait
 * jusqu'ici QUE `argument.conclusion`, jamais les premisses, et le solveur ne faisait
 * qu'asserter la conclusion seule -- `(assert p0)` sur une variable libre est TOUJOURS
 * trivialement SAT, quel que soit le contenu reel. Ca ne verifiait rien de la relation
 * premisses -> conclusion. Les identifiants de proposition sont PARTAGES entre premisses
 * et conclusion (meme registre `propositions`) pour que le meme atome textuel garde le
 * meme id partout dans l'argument.
 */
data class ArgumentFormalization(
    val declarations: List<String>,
    val premiseAsserts: List<String>,
    val conclusionAssert: String,
    val conclusionBackTranslation: String,
    val propositions: List<Proposition>,
)

sealed interface ArgumentFormalizationResult {
    data class Formalized(val formalization: ArgumentFormalization) : ArgumentFormalizationResult
    /** Au moins une premisse OU la conclusion est hors grammaire -- abstention, jamais une formalisation partielle silencieuse. */
    data class Unformalizable(val reason: String) : ArgumentFormalizationResult
}

/**
 * Grammaire couverte (volontairement minimale et documentee, pas devinee) :
 * - "si X alors Y" / "si X, Y"                  -> (=> X Y)
 * - "X et Y"                                     -> (and X Y)
 * - "X ou Y"                                     -> (or X Y)
 * - "il n'est pas vrai que X" / "non X" / "pas X" -> (not X)
 * - une proposition atomique (aucun connecteur reconnu) -> variable booleenne
 *
 * Toute phrase ne correspondant a aucun de ces patrons -> Unformalizable,
 * jamais une approximation silencieuse.
 */
class NlToSmtFormalizer {

    /**
     * Formalise [premises] et [conclusion] ENSEMBLE, avec un registre d'identifiants
     * partage (meme atome textuel -> meme id partout). Echoue (Unformalizable) si une
     * seule premisse ou la conclusion est hors grammaire -- jamais une verification
     * partielle sur un sous-ensemble des premisses sans le dire.
     */
    fun formalizeArgument(premises: List<String>, conclusion: String): ArgumentFormalizationResult {
        val propositions = mutableListOf<Proposition>()
        val premiseAsserts = mutableListOf<String>()
        for (premise in premises) {
            val trimmed = premise.trim()
            if (trimmed.isEmpty()) continue // une premisse vide n'ajoute aucune contrainte, pas une erreur
            val expr = parseExpression(trimmed, propositions)
                ?: return ArgumentFormalizationResult.Unformalizable(
                    "Premisse hors grammaire (si/alors, et, ou, non/pas) : \"$trimmed\"",
                )
            premiseAsserts += expr
        }
        val conclusionTrimmed = conclusion.trim()
        if (conclusionTrimmed.isEmpty()) return ArgumentFormalizationResult.Unformalizable("Conclusion vide")
        val conclusionExpr = parseExpression(conclusionTrimmed, propositions)
            ?: return ArgumentFormalizationResult.Unformalizable(
                "Conclusion hors grammaire (si/alors, et, ou, non/pas) : \"$conclusionTrimmed\"",
            )
        return ArgumentFormalizationResult.Formalized(
            ArgumentFormalization(
                declarations = propositions.map { "(declare-const ${it.id} Bool)" },
                premiseAsserts = premiseAsserts,
                conclusionAssert = conclusionExpr,
                conclusionBackTranslation = backTranslate(conclusionExpr, propositions),
                propositions = propositions,
            ),
        )
    }

    fun formalize(sentence: String): FormalizationResult {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return FormalizationResult.Unformalizable("Phrase vide")

        val propositions = mutableListOf<Proposition>()
        val expr = parseExpression(trimmed, propositions)
            ?: return FormalizationResult.Unformalizable(
                "Aucun patron reconnu (grammaire couverte : si/alors, et, ou, non/pas) pour : \"$trimmed\"",
            )

        val smt = buildString {
            propositions.forEach { appendLine("(declare-const ${it.id} Bool)") }
            appendLine("(assert $expr)")
        }
        return FormalizationResult.Formalized(
            smtLib2 = smt,
            propositions = propositions,
            backTranslation = backTranslate(expr, propositions),
        )
    }

    private fun parseExpression(text: String, propositions: MutableList<Proposition>): String? {
        val lower = text.lowercase()

        // "si X alors Y" / "si X, Y"
        val ifThen = Regex("""^si\s+(.+?)\s*,?\s*alors\s+(.+)$""").find(lower)
            ?: Regex("""^si\s+(.+?),\s*(.+)$""").find(lower)
        if (ifThen != null) {
            val (antecedent, consequent) = ifThen.destructured
            val a = parseExpression(antecedent, propositions) ?: return null
            val c = parseExpression(consequent, propositions) ?: return null
            return "(=> $a $c)"
        }

        // "il n'est pas vrai que X" / "non X" / "pas X"
        val negation = Regex("""^(il n'est pas vrai que|non|pas)\s+(.+)$""").find(lower)
        if (negation != null) {
            val inner = parseExpression(negation.groupValues[2], propositions) ?: return null
            return "(not $inner)"
        }

        // "X et Y" (conjonction, au premier "et" top-level -- pas de gestion de precedence complexe)
        if (lower.contains(" et ")) {
            val parts = lower.split(" et ", limit = 2)
            val left = parseExpression(parts[0], propositions) ?: return null
            val right = parseExpression(parts[1], propositions) ?: return null
            return "(and $left $right)"
        }

        // "X ou Y"
        if (lower.contains(" ou ")) {
            val parts = lower.split(" ou ", limit = 2)
            val left = parseExpression(parts[0], propositions) ?: return null
            val right = parseExpression(parts[1], propositions) ?: return null
            return "(or $left $right)"
        }

        // Proposition atomique : pas de connecteur reconnu -- devient une variable booleenne.
        val cleaned = lower.trim().trimEnd('.', '!', '?')
        if (cleaned.isEmpty()) return null
        val id = propositions.find { it.naturalLanguage == cleaned }?.id
            ?: "p${propositions.size}".also { propositions.add(Proposition(it, cleaned)) }
        return id
    }

    /** Reconstruit une phrase NL approximative depuis l'arbre SMT -- sert au test de fidelite, pas a l'affichage utilisateur. */
    private fun backTranslate(expr: String, propositions: List<Proposition>): String {
        var result = expr
        propositions.sortedByDescending { it.id.length }.forEach { p ->
            result = result.replace(p.id, p.naturalLanguage)
        }
        return result
            .replace("(=>", "si").replace("(and", "").replace("(or", "")
            .replace("(not", "non").replace("(", "").replace(")", "")
            .replace(Regex("\\s+"), " ").trim()
    }
}
