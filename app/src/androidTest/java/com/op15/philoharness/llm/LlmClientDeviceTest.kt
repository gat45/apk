package com.op15.philoharness.llm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test INSTRUMENTE contre un VRAI serveur llama.cpp local (pas un transport
 * simule) -- valide le dernier point ouvert du README : "JavaHttpTransport
 * n'a jamais ete execute contre un serveur reel". Suppose qu'un llama-server
 * tourne deja sur 127.0.0.1:8080 sur CE device au moment du test (deploye
 * manuellement depuis l'archive runtime geleee, backend HTP0 -- voir
 * README section Spike). Ce test NE DEMARRE PAS le serveur lui-meme : il
 * echoue proprement (NetworkError) si rien n'ecoute, plutot que de giger.
 *
 * Resultat connu et documente (README) : avec le modele Qwen3.5-9B charge au
 * moment de l'ecriture de ce test, /v1/chat/completions renvoie un contenu
 * VIDE pour des prompts simples -- le modele reste en mode "reflexion"
 * (reasoning_content) sans jamais emettre de reponse finale, meme a 2000
 * tokens. Ce test ne verifie donc PAS un contenu non vide (ce serait un
 * faux negatif connu) -- il verifie que le round-trip HTTP + parsing JSON
 * reel fonctionne de bout en bout (connectivite, code 200, champs
 * finish_reason/tokens presents), ce qui est le point reellement nouveau
 * valide ici (jusque-la seul un FakeTransport avait ete exerce).
 */
class LlmClientDeviceTest {

    @Test
    fun realLlamaServerRoundTripParsesSuccessfullyEvenWhenModelNeverFinishesThinking() {
        val client = LlmClient(JavaHttpTransport(connectTimeoutMs = 5_000, readTimeoutMs = 30_000))

        val result = client.chat(
            messages = listOf(ChatMessage("user", "Reponds par le mot OK.")),
            maxTokens = 64,
        )

        when (result) {
            is LlmCallResult.Success -> {
                // Round-trip HTTP + parsing JSON reel reussi -- c'est le point verifie ici.
                // finish_reason doit etre present et non vide (sat/unsat du cote Z3 a son
                // equivalent cote LLM : on verifie la FORME de la reponse, pas son contenu).
                assertTrue(
                    "finish_reason devrait etre renseigne par un vrai serveur (recu: '${result.response.finishReason}')",
                    result.response.finishReason.isNotBlank(),
                )
            }
            is LlmCallResult.NetworkError -> throw AssertionError(
                "Aucun llama-server accessible sur 127.0.0.1:8080 -- deployer le runtime gele " +
                    "(README section Spike) avant de lancer ce test. Detail : ${result.message}",
            )
            is LlmCallResult.ServerError -> throw AssertionError(
                "Serveur joignable mais a repondu une erreur HTTP ${result.httpStatus} : ${result.message}",
            )
        }
    }
}
