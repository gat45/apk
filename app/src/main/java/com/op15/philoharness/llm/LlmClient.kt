package com.op15.philoharness.llm

/**
 * Client minimal pour un serveur llama.cpp local expose en OpenAI-compatible
 * (`/v1/chat/completions`), meme contrat que op15-adaptive-harness
 * (ARCHITECTURE.md : "L'executable raccorde Decision::Retrieve au serveur
 * llama.cpp local via /v1/chat/completions. La generation est conservee
 * comme observation : elle ne peut pas produire Answer sans une preuve
 * independante ajoutee au controleur."). Ce client ne fait QUE la
 * communication HTTP -- l'invariant P0 (jamais d'Answer sans preuve) reste
 * applique par PhiloReasoningWorker, pas ici.
 *
 * Transport injectable pour rendre ce client testable sans reseau ni device
 * (voir LlmClientTest -- un FakeTransport simule les reponses).
 */
data class ChatMessage(val role: String, val content: String)

data class LlmResponse(
    val content: String,
    val finishReason: String,
    /** Nombre de tokens generes -- jamais invente si le serveur ne le rapporte pas. */
    val tokensGenerated: Int?,
)

sealed interface LlmCallResult {
    data class Success(val response: LlmResponse) : LlmCallResult
    data class NetworkError(val message: String) : LlmCallResult
    data class ServerError(val httpStatus: Int, val message: String) : LlmCallResult
}

/** Abstraction du transport HTTP -- une implementation reelle (OkHttp) vit hors de ce module pur. */
interface HttpTransport {
    /** @return (statusCode, bodyJson) ou leve une exception reseau. */
    fun post(url: String, jsonBody: String): Pair<Int, String>
}

class LlmClient(
    private val transport: HttpTransport,
    private val baseUrl: String = "http://127.0.0.1:8080",
) {
    /**
     * Appelle /v1/chat/completions. Ne parse que ce qui est necessaire au
     * controleur (contenu + finish_reason + tokens) -- ignore le reste du
     * payload OpenAI-compatible plutot que de le modeliser entierement.
     */
    /**
     * `grammar` (optionnel) : grammaire GBNF transmise telle quelle a llama-server
     * pour CONTRAINDRE la sortie au niveau du sampling, pas seulement la demander
     * dans le prompt -- le modele ne peut tout simplement pas emettre un token qui
     * violerait la grammaire. Pattern verifie dans un prototype Kotlin reel
     * (F:/archive_16tps_20260902/.../oneplus-llm-agent/llm/LlamaServerClient.kt,
     * AgentEngine.kt : grammaire forcant soit un appel d'outil JSON strict soit
     * "FINAL: ..."). Plus fiable que la detection par ligne texte actuelle de
     * ReasoningController (angle mort connu, documente dans TODO.md).
     */
    fun chat(messages: List<ChatMessage>, maxTokens: Int, temperature: Double = 0.0, grammar: String? = null): LlmCallResult {
        val body = buildRequestJson(messages, maxTokens, temperature, grammar)
        return try {
            val (status, responseBody) = transport.post("$baseUrl/v1/chat/completions", body)
            if (status != 200) {
                LlmCallResult.ServerError(status, responseBody)
            } else {
                LlmCallResult.Success(parseResponseJson(responseBody))
            }
        } catch (e: Exception) {
            LlmCallResult.NetworkError(e.message ?: "Erreur reseau inconnue")
        }
    }

    private fun buildRequestJson(messages: List<ChatMessage>, maxTokens: Int, temperature: Double, grammar: String?): String {
        val messagesJson = messages.joinToString(",") { m ->
            """{"role":"${m.role}","content":${jsonEscape(m.content)}}"""
        }
        val grammarField = if (grammar != null) ""","grammar":${jsonEscape(grammar)}""" else ""
        return """{"messages":[$messagesJson],"max_tokens":$maxTokens,"temperature":$temperature,"stream":false$grammarField}"""
    }

    private fun jsonEscape(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return "\"$escaped\""
    }

    /**
     * Parsing minimal, volontairement sans dependance JSON lourde (pas de
     * Gson/Moshi pour ce squelette) -- extrait les 3 champs necessaires via
     * des regex ciblees. Fragile a un format inattendu : c'est assume, un
     * vrai client remplacerait ceci par un parseur JSON complet avant
     * integration serieuse.
     */
    private fun parseResponseJson(json: String): LlmResponse {
        val content = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(json)
            ?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
            ?: ""
        val finishReason = Regex(""""finish_reason"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1) ?: "unknown"
        val tokens = Regex(""""completion_tokens"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()
        return LlmResponse(content = content, finishReason = finishReason, tokensGenerated = tokens)
    }
}
