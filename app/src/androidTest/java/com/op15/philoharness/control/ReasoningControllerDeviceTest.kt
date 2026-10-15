package com.op15.philoharness.control

import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.formalization.ArgumentStructurer
import com.op15.philoharness.llm.JavaHttpTransport
import com.op15.philoharness.llm.LlmClient
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test INSTRUMENTE de la boucle COMPLETE (pas seulement la connectivite HTTP,
 * cf. LlmClientDeviceTest) : vrai LLM -> vraie reponse -> ArgumentStructurer
 * reel -> decision. Suppose un llama-server deja lance sur 127.0.0.1:8080
 * avec un modele SANS mode "reflexion" (Marco-Nano ou equivalent -- voir
 * README : Qwen3.5-9B en mode reflexion ne convient PAS ici, son contenu
 * reste vide meme a 2000 tokens).
 *
 * Ce test ne force RIEN sur la reponse du modele -- si le modele ne produit
 * aucun marqueur donc/car reconnu par ArgumentStructurer, le test echoue
 * avec un message explicite plutot que de masquer silencieusement un
 * desaccord modele/structurer. C'est un signal utile (prompt a ajuster,
 * ou modele pas assez instruction-following), pas un bug de ce test.
 */
class ReasoningControllerDeviceTest {

    @Test
    fun realModelProducesAStructurableConclusionForASimpleSyllogism() {
        val argument = ArgumentEntity(
            id = "device-test-1",
            questionOrigine = "Socrate est-il mortel ?",
            texteOriginal = "tous les hommes sont mortels et Socrate est un homme",
            definitionsJson = "{}",
            premissesExplicitesJson = "[]",
            premissesImplicitesJson = "[]",
            conclusion = "",
            typeRaisonnement = ReasoningType.DEDUCTIF,
            hypothesesJson = "[]",
            limitesFormalisation = null,
            statut = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
            niveauConfianceJustification = "",
            createdAtEpochMs = System.currentTimeMillis(),
            validFrom = System.currentTimeMillis(),
        )

        val controller = ReasoningController(
            llmClient = LlmClient(JavaHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 60_000)),
            argumentStructurer = ArgumentStructurer(),
        )

        val outcome = controller.decide(argument, ReasoningPhase.NEEDS_REASONING)

        when (val decision = outcome.decision) {
            is ReasoningDecision.Reason -> {
                assertTrue(
                    "Reason emis mais structuredConclusion est null/vide -- incoherence interne",
                    !outcome.structuredConclusion.isNullOrBlank(),
                )
            }
            is ReasoningDecision.Abstain -> throw AssertionError(
                "Le modele reel n'a produit aucun marqueur structurable (abstention: ${decision.reason}) -- " +
                    "soit le modele charge a un mode reflexion actif (voir README), soit le prompt doit etre ajuste.",
            )
            else -> throw AssertionError("Decision inattendue en phase NEEDS_REASONING : $decision")
        }
    }
}
