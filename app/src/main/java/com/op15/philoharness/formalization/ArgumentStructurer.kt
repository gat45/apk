package com.op15.philoharness.formalization

/**
 * Reimplementation Kotlin minimale du ROLE d'Argdown (tache 4 du plan) --
 * jamais le code original (Argdown est en JS/TS, MIT, voir audit Livrable 2).
 * Argdown lui-meme ne verifie PAS la validite logique (confirme dans l'audit :
 * "purement representationnel/visuel") -- cette classe a la meme limite
 * assumee : elle structure premisses/conclusion a partir de marqueurs
 * linguistiques explicites, elle ne juge jamais la validite de l'inference
 * (ca, c'est le role de NlToSmtFormalizer + LogicSolver en aval).
 */
data class StructuredArgument(
    val premises: List<String>,
    val conclusion: String,
    /** Marqueur linguistique qui a permis d'identifier la conclusion ("donc", "car", etc.). */
    val conclusionMarker: String,
)

sealed interface StructuringResult {
    data class Structured(val argument: StructuredArgument) : StructuringResult
    data class NoMarkerFound(val reason: String) : StructuringResult
}

class ArgumentStructurer {

    // Marqueurs de conclusion : ce qui suit introduit la conclusion, ce qui precede = premisse(s).
    private val conclusionMarkers = listOf("donc", "par consequent", "ainsi", "il s'ensuit que")

    // Marqueurs de premisse : ce qui suit introduit une premisse, ce qui precede = conclusion.
    private val premiseMarkers = listOf("car", "parce que", "puisque", "étant donné que", "etant donne que")

    fun structure(text: String): StructuringResult {
        val original = text.trim()
        val lower = original.lowercase()
        if (lower.isEmpty()) return StructuringResult.NoMarkerFound("Texte vide")

        // Bug reel trouve en testant avec le vrai LLM sur device (2026-10-13, Marco-Nano) :
        // la PREMIERE occurrence de " donc "/" car " dans une reponse verbeuse peut tomber
        // dans un aside explicatif du modele ("...(dans l'ordre : tous les A sont B, X est A,
        // donc X est B).") au lieu de la vraie conclusion -- la bulle affichait alors un
        // fragment degenere (juste ":" une fois borne a la premiere ligne). On scanne
        // desormais TOUTES les occurrences de chaque marqueur, pas seulement la premiere,
        // et on ne retient que celle qui produit un contenu reel (au moins une lettre) --
        // jamais de ":" ou de fragment de ponctuation affiche comme "la conclusion".
        for (marker in conclusionMarkers) {
            var searchFrom = 0
            while (true) {
                val idx = lower.indexOf(" $marker ", searchFrom)
                if (idx < 0) break
                // Bug reel trouve en inspectant le device (screenshot : conclusion affichee
                // tout en minuscules) : extraire depuis `lower` perdait la casse originale.
                // On localise l'index dans le texte en minuscules (insensible a la casse est
                // le but de la recherche de marqueur) mais on DECOUPE le texte ORIGINAL aux
                // memes indices -- la casse est preservee car le passage en minuscules ne
                // change jamais la longueur en caracteres pour le francais courant.
                val premisePart = original.substring(0, idx).trim()
                val conclusion = firstSentenceOnly(original.substring(idx + marker.length + 2).trim())
                if (isMeaningful(conclusion) && isMeaningful(premisePart)) {
                    return StructuringResult.Structured(
                        StructuredArgument(
                            premises = splitPremises(premisePart),
                            conclusion = conclusion,
                            conclusionMarker = marker,
                        ),
                    )
                }
                searchFrom = idx + 1
            }
        }

        for (marker in premiseMarkers) {
            var searchFrom = 0
            while (true) {
                val idx = lower.indexOf(" $marker ", searchFrom)
                if (idx < 0) break
                val conclusion = firstSentenceOnly(original.substring(0, idx).trim())
                val premisePart = original.substring(idx + marker.length + 2).trim()
                if (isMeaningful(conclusion) && isMeaningful(premisePart)) {
                    return StructuringResult.Structured(
                        StructuredArgument(
                            premises = splitPremises(premisePart),
                            conclusion = conclusion,
                            conclusionMarker = marker,
                        ),
                    )
                }
                searchFrom = idx + 1
            }
        }

        return StructuringResult.NoMarkerFound(
            "Aucun marqueur de structuration reconnu (${(conclusionMarkers + premiseMarkers).joinToString(", ")}) dans : \"$text\"",
        )
    }

    /** Rejette un fragment vide ou purement ponctuation/markdown (ex. ":", "**") -- jamais affiche comme conclusion. */
    private fun isMeaningful(text: String): Boolean = text.any { it.isLetter() }

    /**
     * Bug reel trouve en inspectant le device (screenshot : conclusion = tout le reste
     * du raisonnement du modele, y compris une repetition degeneree "donc"/"car"/"donc"...
     * et un "**" non ferme) : avant ce correctif, tout le texte APRES le marqueur jusqu'a
     * la fin de la reponse devenait la conclusion, sans borne. Un petit modele local
     * raisonne souvent a voix haute avant/apres sa conclusion -- on ne garde que la
     * premiere phrase/ligne suivant le marqueur, jamais la suite du raisonnement.
     */
    private fun firstSentenceOnly(text: String): String {
        val newlineIdx = text.indexOf('\n')
        val bounded = if (newlineIdx >= 0) text.substring(0, newlineIdx) else text
        return bounded.trim().trimEnd('.', '!', '?')
    }

    private fun splitPremises(text: String): List<String> =
        text.split(Regex("""\bet\b""", RegexOption.IGNORE_CASE))
            .map { it.trim().trimEnd('.', ',') }
            .filter { it.isNotBlank() }
}
