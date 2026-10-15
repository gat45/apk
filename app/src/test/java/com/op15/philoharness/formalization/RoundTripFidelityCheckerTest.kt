package com.op15.philoharness.formalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundTripFidelityCheckerTest {

    private val checker = RoundTripFidelityChecker(minOverlapRatio = 0.5)

    @Test
    fun `identical text is never divergent`() {
        val verdict = checker.check("il pleut", "il pleut")
        assertFalse(verdict.divergent)
    }

    @Test
    fun `completely unrelated back-translation is flagged as divergent`() {
        // Reproduit le scenario "mauvaise formalisation" de l'audit : la retraduction
        // n'a presque aucun mot en commun avec l'original -- signal fort de divergence de sens.
        val verdict = checker.check("il pleut aujourd'hui", "les chats aiment le fromage")
        assertTrue(verdict.divergent)
    }

    @Test
    fun `partial overlap above threshold is accepted`() {
        val verdict = checker.check("si il pleut alors la route est mouillee", "il pleut la route est mouillee")
        assertFalse(verdict.divergent)
    }

    @Test
    fun `end-to-end formalize then check fidelity on a well-covered sentence`() {
        val formalizer = NlToSmtFormalizer()
        val original = "si il pleut alors la route est mouillee"
        val result = formalizer.formalize(original)
        require(result is FormalizationResult.Formalized)
        val verdict = checker.check(original, result.backTranslation)
        assertFalse("round-trip fidelity should hold for a sentence within the covered grammar", verdict.divergent)
    }

    // Regression : bug reel trouve en croisant avec HANS/BLEU (voir commentaire de classe).
    // L'ancienne implementation Jaccard-sur-Set donnait 100% de recouvrement ici (le mot
    // ajoute par la negation n'etait jamais penalise, Set deduplique et seule l'intersection
    // comptait). Le score de Dice sur multiset penalise desormais reellement les mots
    // ajoutes : 0.667 au lieu de 1.0 -- une amelioration reelle et mesuree, PAS une
    // resolution complete du probleme (Dice reste lexical, pas logique -- passe toujours
    // sous le seuil par defaut de divergence ici). Documente honnetement plutot que de
    // pretendre un correctif plus fort qu'il ne l'est : voir le commentaire de classe.
    @Test
    fun `adding a negation lowers the score but Dice alone does not fully solve negation`() {
        val verdict = checker.check("il pleut", "il ne pleut pas")
        assertEquals(2.0 * 2 / (2 + 4), verdict.overlapRatio, 0.0001) // 0.667, pas 1.0 comme avant le correctif
        assertFalse(
            "le score de Dice seul ne suffit pas a detecter cette divergence au seuil par defaut -- " +
                "limite connue et documentee, la comparaison structurelle AST (P1) est le vrai correctif",
            verdict.divergent,
        )
    }

    @Test
    fun `apostrophe is treated as a word boundary, not silently removed`() {
        // Bug reel trouve en croisant avec la revue externe : l'ancien regex supprimait les
        // caracteres non reconnus (dont l'apostrophe) au lieu de les remplacer par un espace,
        // donc "n'est" devenait "nest" -- un mot different, qui ne matche plus "est" ni "n".
        val verdict = checker.check("il n'est pas vrai que la terre est plate", "il n'est pas vrai que la terre est plate")
        assertFalse(verdict.divergent)
        assertEquals(1.0, verdict.overlapRatio, 0.0001)
    }

    @Test
    fun `constructor rejects an out-of-range threshold rather than silently misbehaving`() {
        assertThrows(IllegalArgumentException::class.java) { RoundTripFidelityChecker(minOverlapRatio = 1.5) }
    }
}
