package com.op15.philoharness.ui

import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.arguments.ReasoningType
import com.op15.philoharness.control.AbstentionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testArgument(
    id: String = "arg-1",
    question: String = "Les chats sont-ils des mammiferes ?",
    conclusion: String = "",
    statut: EpistemicStatus = EpistemicStatus.EN_ATTENTE_DE_VERIFICATION,
    lastAbstentionReason: String? = null,
) = ArgumentEntity(
    id = id,
    questionOrigine = question,
    texteOriginal = question,
    definitionsJson = "{}",
    premissesExplicitesJson = "[]",
    premissesImplicitesJson = "[]",
    conclusion = conclusion,
    typeRaisonnement = ReasoningType.DEDUCTIF,
    hypothesesJson = "[]",
    limitesFormalisation = null,
    statut = statut,
    niveauConfianceJustification = "",
    createdAtEpochMs = 0L,
    validFrom = 0L,
    lastAbstentionReason = lastAbstentionReason,
)

/**
 * Logique de mapping extraite de l'ancien ChatMessageAdapter (BaseAdapter, impossible a
 * tester sans Context Android) lors de la migration Compose -- premiere fois que ce code
 * passe sous test JVM.
 */
class ChatMapperTest {

    @Test
    fun `one argument becomes a user then an assistant message, chronological order`() {
        val older = testArgument(id = "a", question = "Premiere question", conclusion = "Felix est un mammifere")
        val newer = testArgument(id = "b", question = "Deuxieme question", conclusion = "Oui")
        // observeActiveArguments() est DESC (plus recent d'abord) -- le mapper doit inverser.
        val messages = ChatMapper.toChatMessages(listOf(newer, older))

        assertEquals(4, messages.size)
        assertTrue(messages[0] is ChatMessage.User)
        assertEquals("Premiere question", (messages[0] as ChatMessage.User).text)
        assertTrue(messages[2] is ChatMessage.User)
        assertEquals("Deuxieme question", (messages[2] as ChatMessage.User).text)
    }

    @Test
    fun `a blank conclusion falls back to an abstention placeholder, never blank`() {
        val arg = testArgument(statut = EpistemicStatus.INDETERMINE, lastAbstentionReason = AbstentionReason.SOURCES_INSUFFISANTES.name)
        val messages = ChatMapper.toChatMessages(listOf(arg))
        val assistant = messages[1] as ChatMessage.Assistant
        assertEquals("Je ne peux pas encore repondre a cette question.", assistant.text)
        assertEquals("Abstention : LLM local injoignable", assistant.meta)
    }

    @Test
    fun `a supported or refuted conclusion carries no meta badge, the bubble color speaks for it`() {
        val supported = testArgument(conclusion = "Felix est un mammifere", statut = EpistemicStatus.SOUTENU)
        val messages = ChatMapper.toChatMessages(listOf(supported))
        val assistant = messages[1] as ChatMessage.Assistant
        assertNull(assistant.meta)
        assertEquals(EpistemicStatus.SOUTENU, assistant.status)
    }

    @Test
    fun `paired and unpaired markdown bold markers are both stripped`() {
        assertEquals("Felix est un mammifere", ChatMapper.stripMarkdownBold("**Felix** est un mammifere"))
        assertEquals("texte tronque", ChatMapper.stripMarkdownBold("texte tronque**"))
    }
}
