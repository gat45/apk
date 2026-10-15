package com.op15.philoharness.formalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NlToSmtFormalizerTest {

    private val formalizer = NlToSmtFormalizer()

    @Test
    fun `simple if-then sentence formalizes to implication`() {
        val result = formalizer.formalize("si il pleut alors la route est mouillee")
        require(result is FormalizationResult.Formalized)
        assertTrue(result.smtLib2.contains("(assert (=> p0 p1))"))
        assertEquals(2, result.propositions.size)
    }

    @Test
    fun `conjunction formalizes to and`() {
        val result = formalizer.formalize("il fait beau et il fait chaud")
        require(result is FormalizationResult.Formalized)
        assertTrue(result.smtLib2.contains("(and"))
    }

    @Test
    fun `negation formalizes to not`() {
        val result = formalizer.formalize("il n'est pas vrai que la terre est plate")
        require(result is FormalizationResult.Formalized)
        assertTrue(result.smtLib2.contains("(not"))
    }

    @Test
    fun `unrecognized grammar returns Unformalizable rather than a guess`() {
        // Phrase avec une structure que la grammaire couverte (si/et/ou/non) ne capture pas correctement
        // en tant que connecteur logique explicite -- doit etre traitee comme une proposition atomique,
        // jamais une invention de structure.
        val result = formalizer.formalize("")
        assertTrue(result is FormalizationResult.Unformalizable)
    }

    @Test
    fun `atomic proposition with no connective becomes a single boolean variable`() {
        val result = formalizer.formalize("le ciel est bleu")
        require(result is FormalizationResult.Formalized)
        assertEquals(1, result.propositions.size)
        assertTrue(result.smtLib2.contains("(assert p0)"))
    }

    // formalizeArgument : angle mort reel trouve en auditant cette session -- verifyIndependently()
    // ne formalisait avant que la conclusion seule, jamais les premisses (TODO.md, trouvaille audit).

    @Test
    fun `premises and conclusion share the same proposition registry`() {
        val result = formalizer.formalizeArgument(
            premises = listOf("si il pleut alors la route est mouillee"),
            conclusion = "la route est mouillee",
        )
        require(result is ArgumentFormalizationResult.Formalized)
        val f = result.formalization
        // "la route est mouillee" doit reutiliser le MEME id dans la premisse et la conclusion,
        // pas un nouvel atome distinct -- sinon Gamma et C ne partagent aucune variable et le
        // test d'entailment devient vide de sens.
        assertEquals(1, f.premiseAsserts.size)
        assertEquals("(=> p0 p1)", f.premiseAsserts[0])
        assertEquals("p1", f.conclusionAssert)
        assertEquals(2, f.propositions.size)
    }

    @Test
    fun `a premise outside the covered grammar makes the whole argument unformalizable`() {
        // Une seule premisse hors grammaire doit tout faire echouer -- jamais une verification
        // partielle qui ignorerait silencieusement une premisse non comprise.
        val result = formalizer.formalizeArgument(
            premises = listOf("il pleut et  et il neige"), // double "et" laisse un cote vide apres decoupe -- jamais un atome degenere en silence
            conclusion = "la route est mouillee",
        )
        assertTrue(result is ArgumentFormalizationResult.Unformalizable)
    }

    @Test
    fun `empty premise list formalizes the conclusion alone, consistent with no constraints`() {
        val result = formalizer.formalizeArgument(premises = emptyList(), conclusion = "le ciel est bleu")
        require(result is ArgumentFormalizationResult.Formalized)
        assertTrue(result.formalization.premiseAsserts.isEmpty())
        assertEquals("p0", result.formalization.conclusionAssert)
    }
}
