package com.op15.philoharness.ui

import com.op15.philoharness.arguments.ArgumentEntity
import com.op15.philoharness.arguments.EpistemicStatus
import com.op15.philoharness.control.AbstentionReason

sealed interface ChatMessage {
    val argumentId: String
    data class User(override val argumentId: String, val text: String) : ChatMessage
    data class Assistant(
        override val argumentId: String,
        val text: String,
        val meta: String?,
        val status: EpistemicStatus,
    ) : ChatMessage
}

/**
 * Logique de mapping ArgumentEntity -> bulles de chat, extraite de l'ancien ChatMessageAdapter
 * (BaseAdapter, exigeait un Context Android) lors de la migration vers Compose. Fonctions
 * PURES, sans dependance Android -- testables en JVM (gain reel de la migration, pas
 * seulement cosmetique : ce code n'avait jamais pu etre teste avant).
 */
object ChatMapper {

    /** observeActiveArguments() est triee DESC (plus recent d'abord) ; une conversation se lit du plus ancien au plus recent. */
    fun toChatMessages(arguments: List<ArgumentEntity>): List<ChatMessage> {
        val chronological = arguments.asReversed()
        return chronological.flatMap { arg ->
            val userText = arg.questionOrigine.ifBlank { arg.texteOriginal }.replace('_', ' ')
            val assistantText = stripMarkdownBold(arg.conclusion).ifBlank {
                statusPlaceholder(arg.statut, arg.lastAbstentionReason)
            }
            listOf(
                ChatMessage.User(arg.id, userText),
                ChatMessage.Assistant(arg.id, assistantText, metaFor(arg.statut, arg.lastAbstentionReason), arg.statut),
            )
        }
    }

    /**
     * Le LLM renvoie du markdown (**gras**) -- une bulle de chat l'affiche en texte brut,
     * pas avec les asterisques litteraux. Bug reel trouve en inspectant le device : un
     * "**" ISOLE (jamais referme, frequent quand la reponse du modele est tronquee) n'etait
     * pas matche par la regex des paires et restait affiche tel quel -- nettoye en second
     * passage.
     */
    fun stripMarkdownBold(text: String): String =
        text.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1").replace("**", "")

    fun statusPlaceholder(status: EpistemicStatus, reason: String?): String = when {
        status == EpistemicStatus.EN_ATTENTE_DE_VERIFICATION && reason == null -> "..."
        else -> "Je ne peux pas encore repondre a cette question."
    }

    fun metaFor(status: EpistemicStatus, reason: String?): String? {
        if (reason != null) {
            return "Abstention : ${abstentionLabel(reason)}"
        }
        return when (status) {
            EpistemicStatus.SOUTENU, EpistemicStatus.REFUTE -> null // la conclusion parle d'elle-meme, pas besoin d'un badge
            else -> null
        }
    }

    fun abstentionLabel(rawReasonName: String): String = when (
        runCatching { AbstentionReason.valueOf(rawReasonName) }.getOrNull()
    ) {
        AbstentionReason.DEFINITIONS_INSUFFISANTES -> "definitions insuffisantes"
        AbstentionReason.PREMISSES_INCOMPATIBLES -> "premisses incompatibles"
        AbstentionReason.SOURCES_INSUFFISANTES -> "LLM local injoignable"
        AbstentionReason.SOLVEUR_NE_COUVRE_PAS_LE_RAISONNEMENT -> "solveur sans verdict tranche"
        AbstentionReason.TRADUCTION_LOGIQUE_NON_FIABLE -> "formalisation logique non fiable"
        AbstentionReason.ARGUMENTS_CONCURRENTS_RAISONNABLES -> "arguments concurrents raisonnables"
        AbstentionReason.DEPEND_D_UN_CHOIX_DE_VALEURS_NON_SPECIFIE -> "depend d'un choix de valeurs non specifie"
        AbstentionReason.BUDGET_EPUISE -> "budget de raisonnement epuise"
        null -> rawReasonName
    }
}
