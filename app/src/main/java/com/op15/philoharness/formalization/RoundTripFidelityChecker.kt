package com.op15.philoharness.formalization

/**
 * Angle mort "Mauvaise formalisation NL->logique" (audit, Livrable 3) :
 * un solveur peut valider une conclusion issue d'une traduction qui ne respecte
 * pas le sens initial. Cette classe ne verifie PAS la correction semantique
 * (impossible sans un LLM ou un humain) -- elle detecte seulement une
 * divergence LEXICALE grossiere entre la phrase originale et sa retraduction,
 * comme garde-fou minimal avant d'accepter une formalisation.
 *
 * Bug reel trouve en croisant avec la litterature (HANS, McCoy et al. 2019 ACL -- les
 * heuristiques de recouvrement lexical donnent de bons scores a des inferences invalides ;
 * meme probleme documente pour BLEU, ou la suppression d'une negation n'est pas penalisee
 * proportionnellement a la gravite de l'erreur). L'ancienne implementation (Jaccard sur
 * `Set<String>`) donnait 100% de recouvrement entre "il pleut" et "il ne pleut pas" : un
 * mot ajoute (la negation elle-meme) n'est jamais penalise puisque seule l'INTERSECTION
 * compte, jamais la TAILLE de l'ensemble retraduit. Remplace par un score de Dice sur
 * multiset (compte les occurrences, penalise symetriquement les mots ajoutes ET retires) --
 * meilleur, mais reste un diagnostic LEXICAL secondaire : un score parfait peut toujours
 * masquer une inversion logique (ex. une double negation mal geree). Ne doit jamais etre la
 * seule porte d'acceptation d'une formalisation.
 */
data class FidelityVerdict(val divergent: Boolean, val overlapRatio: Double, val detail: String)

class RoundTripFidelityChecker(
    private val minOverlapRatio: Double = 0.5,
) {
    init {
        require(minOverlapRatio in 0.0..1.0) { "minOverlapRatio doit etre compris entre 0 et 1, recu $minOverlapRatio" }
    }

    fun check(original: String, backTranslation: String): FidelityVerdict {
        val originalWords = tokenize(original)
        val backWords = tokenize(backTranslation)
        if (originalWords.isEmpty()) {
            return FidelityVerdict(divergent = true, overlapRatio = 0.0, detail = "Phrase originale vide apres tokenisation")
        }
        if (backWords.isEmpty()) {
            return FidelityVerdict(divergent = true, overlapRatio = 0.0, detail = "Retrotraduction vide apres tokenisation")
        }

        // Comptage multiensemble : chaque occurrence du cote retraduit ne peut consommer
        // qu'une seule occurrence correspondante du cote original -- "non non A" et "non A"
        // ne doivent pas obtenir le meme score qu'avec un simple Set qui deduplique.
        val remaining = originalWords.groupingBy { it }.eachCount().toMutableMap()
        var overlap = 0
        for (word in backWords) {
            val count = remaining[word] ?: 0
            if (count > 0) {
                overlap++
                remaining[word] = count - 1
            }
        }

        // Coefficient de Dice : penalise les mots absents ET les mots ajoutes (contrairement
        // a Jaccard/ratio-sur-original-seul, qui ignore totalement les ajouts cote retraduit).
        val ratio = 2.0 * overlap / (originalWords.size + backWords.size)

        return FidelityVerdict(
            divergent = ratio < minOverlapRatio,
            overlapRatio = ratio,
            detail = if (ratio < minOverlapRatio) {
                "Chevauchement de Dice ${"%.0f".format(ratio * 100)}% < seuil ${"%.0f".format(minOverlapRatio * 100)}% -- " +
                    "formalisation rejetee, risque de divergence de sens"
            } else {
                "Chevauchement de Dice ${"%.0f".format(ratio * 100)}% -- aucune divergence lexicale majeure detectee " +
                    "(diagnostic lexical seul, ne prouve pas la fidelite semantique)"
            },
        )
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9àâäéèêëïîôöùûüç\\s]"), " ") // ponctuation -> espace (pas suppression) pour ne jamais fusionner deux mots ("n'est" -> "n est", pas "nest")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
}
