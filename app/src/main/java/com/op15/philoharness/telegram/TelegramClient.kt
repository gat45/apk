package com.op15.philoharness.telegram

import com.op15.philoharness.llm.HttpTransport

data class TelegramMessage(val updateId: Long, val chatId: Long, val text: String)

sealed interface TelegramResult {
    data class Success<T>(val value: T) : TelegramResult
    data class Error(val message: String) : TelegramResult
}

/**
 * Client minimal pour l'API Bot Telegram (long polling via getUpdates +
 * sendMessage), meme discipline que LlmClient : parsing regex cible plutot
 * qu'une dependance JSON lourde, transport injectable (reutilise
 * `com.op15.philoharness.llm.HttpTransport`, aucune nouvelle dependance
 * Gradle). Capacite inspiree du concept documente publiquement par
 * orailnoor/private-agent (integration Telegram pour controle a distance),
 * reimplementee ici a partir de zero -- aucun code source de ce depot n'a
 * ete consulte ni copie.
 *
 * Le token du bot n'est JAMAIS code en dur : fourni par l'appelant (a
 * recuperer depuis les preferences utilisateur, cf. TODO.md -- pas d'UI de
 * configuration cablee pour l'instant).
 */
class TelegramClient(
    private val transport: HttpTransport,
    private val botToken: String,
    private val baseUrl: String = "https://api.telegram.org",
) {
    /**
     * Interroge les nouveaux messages depuis [offsetUpdateId] (exclu) avec
     * un long-polling de [timeoutSeconds]. Retourne la liste des messages
     * texte recus (les autres types -- photos, stickers -- sont ignores,
     * jamais une erreur silencieuse : juste filtres).
     */
    fun getUpdates(offsetUpdateId: Long?, timeoutSeconds: Int = 30): TelegramResult {
        val body = buildString {
            append("{")
            if (offsetUpdateId != null) append("\"offset\":${offsetUpdateId + 1},")
            append("\"timeout\":$timeoutSeconds")
            append("}")
        }
        return try {
            val (status, response) = transport.post("$baseUrl/bot$botToken/getUpdates", body)
            if (status != 200) {
                TelegramResult.Error("HTTP $status : ${response.take(200)}")
            } else {
                TelegramResult.Success(parseUpdates(response))
            }
        } catch (e: Exception) {
            TelegramResult.Error(e.message ?: "erreur reseau inconnue")
        }
    }

    fun sendMessage(chatId: Long, text: String): TelegramResult {
        val body = """{"chat_id":$chatId,"text":${jsonEscape(text)}}"""
        return try {
            val (status, response) = transport.post("$baseUrl/bot$botToken/sendMessage", body)
            if (status != 200) {
                TelegramResult.Error("HTTP $status : ${response.take(200)}")
            } else {
                TelegramResult.Success(Unit)
            }
        } catch (e: Exception) {
            TelegramResult.Error(e.message ?: "erreur reseau inconnue")
        }
    }

    /**
     * Parsing volontairement tolerant (regex, pas un vrai parseur JSON) :
     * extrait chaque triplet update_id/chat.id/text present dans la reponse,
     * ignore silencieusement les updates sans texte (photos, join/leave,
     * etc.) plutot que de planter sur un format inattendu.
     */
    private fun parseUpdates(json: String): List<TelegramMessage> {
        val results = mutableListOf<TelegramMessage>()
        val updateRegex = Regex("\"update_id\"\\s*:\\s*(\\d+)")
        val updateIds = updateRegex.findAll(json).map { it.groupValues[1].toLong() }.toList()
        // Approche simple : on retrouve, pour chaque update_id, le chat.id et le text qui le
        // suivent dans le flux (l'API Telegram serialise toujours les champs dans cet ordre
        // pour un message texte standard -- suffisant pour un client minimal, pas un
        // remplacement d'un vrai parseur JSON pour des payloads complexes/imbriques).
        val segments = json.split(Regex("(?=\"update_id\")"))
        for (segment in segments) {
            val updateId = updateRegex.find(segment)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val chatId = Regex("\"chat\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(-?\\d+)").find(segment)?.groupValues?.get(1)?.toLongOrNull()
            val text = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(segment)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.replace("\\n", "\n")
            if (chatId != null && text != null) {
                results.add(TelegramMessage(updateId, chatId, text))
            }
        }
        return results
    }

    private fun jsonEscape(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }
}
