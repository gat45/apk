package com.op15.philoharness.formalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArgumentStructurerTest {

    private val structurer = ArgumentStructurer()

    @Test
    fun `donc marker splits premises and conclusion correctly`() {
        val result = structurer.structure("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        require(result is StructuringResult.Structured)
        assertEquals(2, result.argument.premises.size)
        assertEquals("Socrate est mortel", result.argument.conclusion) // casse originale preservee (cf. correctif ci-dessous)
        assertEquals("donc", result.argument.conclusionMarker)
    }

    @Test
    fun `car marker places conclusion before the marker`() {
        val result = structurer.structure("la route est mouillee car il a plu")
        require(result is StructuringResult.Structured)
        assertEquals("la route est mouillee", result.argument.conclusion)
        assertEquals(listOf("il a plu"), result.argument.premises)
    }

    @Test
    fun `text with no recognized marker is reported, never guessed`() {
        val result = structurer.structure("le ciel est bleu aujourd'hui")
        assertTrue(result is StructuringResult.NoMarkerFound)
    }

    @Test
    fun `original casing is preserved, not forced to lowercase`() {
        // Bug reel trouve en inspectant le device (screenshot 2026-10-13) : la conclusion
        // affichee a l'utilisateur etait TOUJOURS en minuscules, y compris les noms propres.
        val result = structurer.structure("Socrate est un homme donc Socrate est mortel")
        require(result is StructuringResult.Structured)
        assertEquals("Socrate est mortel", result.argument.conclusion)
    }

    @Test
    fun `rambling after the conclusion is never included`() {
        // Bug reel trouve en inspectant le device : un petit modele local qui continue a
        // raisonner APRES sa conclusion (nouvelle ligne) voyait tout ce raisonnement --
        // y compris une repetition degeneree -- affiche comme "la reponse".
        val result = structurer.structure(
            "il a plu donc la route est mouillee\n" +
                "le marqueur explicite est donc :\n\"donc\" \"car\" \"donc\" \"car\"\n**",
        )
        require(result is StructuringResult.Structured)
        assertEquals("la route est mouillee", result.argument.conclusion)
    }

    @Test
    fun `a degenerate first occurrence is skipped in favor of a later meaningful one`() {
        // Bug reel trouve en testant avec le vrai LLM sur device (Marco-Nano, 2026-10-13) :
        // la bulle affichait juste ":" -- la PREMIERE occurrence de " donc " tombait dans
        // un aside du modele juste avant un saut de ligne ("... donc :\n..."), bornee a la
        // premiere ligne ca ne laissait que ":". La vraie conclusion existait plus loin
        // dans la meme reponse.
        val result = structurer.structure(
            "C'est un syllogisme (X est A, donc :\n" +
                "cette partie n'est qu'un aside).\n\n" +
                "Finalement donc Felix est un mammifere.",
        )
        require(result is StructuringResult.Structured)
        assertEquals("Felix est un mammifere", result.argument.conclusion)
    }

    @Test
    fun `a response with only degenerate marker occurrences abstains rather than showing punctuation`() {
        val result = structurer.structure("texte donc :\nautre donc :\n")
        assertTrue(result is StructuringResult.NoMarkerFound)
    }
}
