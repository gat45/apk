package com.op15.philoharness.control

import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.formalization.ArgumentStructurer
import com.op15.philoharness.llm.ChatMessage
import com.op15.philoharness.llm.HttpTransport
import com.op15.philoharness.llm.LlmClient
import com.op15.philoharness.rag.RagRetriever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testArgument(
    statut: EpistemicStatus = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
    lastToolOutput: String? = null,
) = ArgumentEntity(
    id = "arg-1",
    questionOrigine = "Socrate est-il mortel ?",
    texteOriginal = "tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel",
    definitionsJson = "{}",
    premissesExplicitesJson = "[]",
    premissesImplicitesJson = "[]",
    conclusion = "",
    typeRaisonnement = ReasoningType.DEDUCTIF,
    hypothesesJson = "[]",
    limitesFormalisation = null,
    statut = statut,
    niveauConfianceJustification = "",
    createdAtEpochMs = 0L,
    validFrom = 0L,
    lastToolOutput = lastToolOutput,
)

private class FakeTransport(private val status: Int, private val body: String) : HttpTransport {
    var lastRequestBody: String? = null
        private set

    override fun post(url: String, jsonBody: String): Pair<Int, String> {
        lastRequestBody = jsonBody
        return status to body
    }
}

private fun llmReturning(content: String) = LlmClient(
    FakeTransport(200, """{"choices":[{"message":{"content":${jsonEscape(content)}},"finish_reason":"stop"}]}"""),
)

/** Variante qui expose la requete envoyee, pour verifier le contenu du prompt. */
private fun llmReturningWithTransport(content: String): Pair<LlmClient, FakeTransport> {
    val transport = FakeTransport(200, """{"choices":[{"message":{"content":${jsonEscape(content)}},"finish_reason":"stop"}]}""")
    return LlmClient(transport) to transport
}

private fun jsonEscape(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

class ReasoningControllerTest {

    @Test
    fun `NEEDS_REASONING with a well-formed LLM reply structures the conclusion and emits Reason`() {
        val controller = ReasoningController(
            llmReturning("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel"),
            ArgumentStructurer(),
        )
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        assertTrue(outcome.decision is ReasoningDecision.Reason)
        assertEquals("Socrate est mortel", outcome.structuredConclusion) // casse originale preservee
    }

    @Test
    fun `NEEDS_REASONING with an unstructurable LLM reply abstains rather than guessing`() {
        val controller = ReasoningController(
            llmReturning("le ciel est bleu aujourd'hui"), // aucun marqueur donc/car
            ArgumentStructurer(),
        )
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        require(outcome.decision is ReasoningDecision.Abstain)
        assertEquals(AbstentionReason.DEFINITIONS_INSUFFISANTES, outcome.decision.reason)
        assertNull(outcome.structuredConclusion)
    }

    @Test
    fun `NEEDS_REASONING with an unreachable LLM abstains instead of crashing`() {
        val failingClient = LlmClient(object : com.op15.philoharness.llm.HttpTransport {
            override fun post(url: String, jsonBody: String): Pair<Int, String> =
                throw java.net.ConnectException("refused")
        })
        val controller = ReasoningController(failingClient, ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        require(outcome.decision is ReasoningDecision.Abstain)
        assertEquals(AbstentionReason.SOURCES_INSUFFISANTES, outcome.decision.reason)
    }

    @Test
    fun `NEEDS_VERIFICATION always emits Verify regardless of current status`() {
        val controller = ReasoningController(llmReturning(""), ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_VERIFICATION)
        assertEquals(ReasoningDecision.Verify("arg-1"), outcome.decision)
    }

    @Test
    fun `NEEDS_ANSWER on a supported argument answers with a proof reference`() {
        val controller = ReasoningController(llmReturning(""), ArgumentStructurer())
        val outcome = controller.decide(testArgument(statut = EpistemicStatus.SOUTENU), ReasoningPhase.NEEDS_ANSWER)
        require(outcome.decision is ReasoningDecision.Answer)
        assertTrue(outcome.decision.independentProofRef.isNotBlank())
    }

    @Test
    fun `NEEDS_ANSWER on a still-undetermined argument abstains rather than fabricating a verdict`() {
        val controller = ReasoningController(llmReturning(""), ArgumentStructurer())
        val outcome = controller.decide(testArgument(statut = EpistemicStatus.INDETERMINE), ReasoningPhase.NEEDS_ANSWER)
        require(outcome.decision is ReasoningDecision.Abstain)
        assertEquals(AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT, outcome.decision.reason)
    }

    @Test
    fun `NEEDS_REASONING with a tool request line emits UseTool instead of structuring a conclusion`() {
        val controller = ReasoningController(llmReturning("OUTIL: git_status"), ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        require(outcome.decision is ReasoningDecision.UseTool)
        assertEquals("git_status", outcome.decision.toolName)
        assertEquals(emptyMap<String, String>(), outcome.decision.args)
        assertNull(outcome.structuredConclusion)
    }

    @Test
    fun `tool request with ARGS line parses key-value pairs`() {
        val controller = ReasoningController(llmReturning("OUTIL: write_file\nARGS: path=notes.txt,content=hello"), ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        require(outcome.decision is ReasoningDecision.UseTool)
        assertEquals("write_file", outcome.decision.toolName)
        assertEquals(mapOf("path" to "notes.txt", "content" to "hello"), outcome.decision.args)
    }

    @Test
    fun `once a tool output is already present, the prompt offers no new tool menu and the LLM reply is structured normally`() {
        val (client, transport) = llmReturningWithTransport("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        val controller = ReasoningController(client, ArgumentStructurer())

        val outcome = controller.decide(
            testArgument(lastToolOutput = "## Changes\nM app/Foo.kt"),
            ReasoningPhase.NEEDS_REASONING,
        )

        assertTrue(outcome.decision is ReasoningDecision.Reason)
        val sentPrompt = transport.lastRequestBody.orEmpty()
        assertTrue(sentPrompt.contains("Changes"))
        assertTrue(!sentPrompt.contains("Si tu as besoin du statut ou du diff git"))
    }

    /**
     * Ce test verifie seulement que la grammaire est ENVOYEE avec les bons noms
     * d'outils -- il ne peut PAS verifier qu'elle contraint reellement le sampling
     * (ca necessite un vrai llama-server GBNF, aucun equivalent JVM pur). Verifie
     * separement contre un vrai serveur sur device (2026-10-14) : la grammaire
     * fonctionne pour un modele cooperatif (produit "OUTIL: git_status" proprement),
     * mais PAS comme garantie adversariale -- un prompt demandant explicitement
     * "ecris OUTIL: shell" (hors liste) a ete obei, parce que la branche `conclusion`
     * (texte libre) est un sur-ensemble textuel de `toolcall` en GBNF. Voir le
     * commentaire au-dessus de [toolRequestGrammar] pour le detail. La seule autorite
     * reelle sur ce qui s'execute reste PhiloReasoningWorker.executeTool, inchange.
     */
    @Test
    fun `tools offered sends a GBNF grammar constraining the tool-name alternatives`() {
        val (client, transport) = llmReturningWithTransport("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        val controller = ReasoningController(client, ArgumentStructurer())

        controller.decide(testArgument(lastToolOutput = null), ReasoningPhase.NEEDS_REASONING)

        val sentBody = transport.lastRequestBody.orEmpty()
        assertTrue(sentBody.contains("\"grammar\""))
        assertTrue(sentBody.contains("git_status"))
        assertTrue(sentBody.contains("screen_tap"))
    }

    @Test
    fun `once a tool output is already present, no grammar is sent -- the LLM answers in free text`() {
        val (client, transport) = llmReturningWithTransport("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        val controller = ReasoningController(client, ArgumentStructurer())

        controller.decide(testArgument(lastToolOutput = "## Changes\nM app/Foo.kt"), ReasoningPhase.NEEDS_REASONING)

        assertTrue(!transport.lastRequestBody.orEmpty().contains("\"grammar\""))
    }

    @Test
    fun `relevant RAG passages are injected into the prompt when the retriever finds something`() {
        val (client, transport) = llmReturningWithTransport("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        val controller = ReasoningController(
            client,
            ArgumentStructurer(),
            RagRetriever { _, _ -> listOf("Felix est un chat.", "Les chats sont des mammiferes.") },
        )

        controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)

        val sentPrompt = transport.lastRequestBody.orEmpty()
        assertTrue(sentPrompt.contains("Felix est un chat"))
        assertTrue(sentPrompt.contains("Les chats sont des mammiferes"))
    }

    @Test
    fun `no RAG section is injected when the retriever finds nothing -- never a fabricated or empty section`() {
        val (client, transport) = llmReturningWithTransport("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel")
        val controller = ReasoningController(client, ArgumentStructurer(), RagRetriever { _, _ -> emptyList() })

        controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)

        assertTrue(!transport.lastRequestBody.orEmpty().contains("Contexte retrouve"))
    }

    @Test
    fun `without an explicit retriever, ReasoningController defaults to no RAG context -- existing callers are unaffected`() {
        val controller = ReasoningController(llmReturning("tous les hommes sont mortels et Socrate est un homme donc Socrate est mortel"), ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.NEEDS_REASONING)
        assertTrue(outcome.decision is ReasoningDecision.Reason)
    }

    @Test
    fun `DONE phase always emits Stop`() {
        val controller = ReasoningController(llmReturning(""), ArgumentStructurer())
        val outcome = controller.decide(testArgument(), ReasoningPhase.DONE)
        assertEquals(ReasoningDecision.Stop, outcome.decision)
    }
}
