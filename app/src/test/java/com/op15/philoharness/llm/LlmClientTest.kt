package com.op15.philoharness.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(
    private val statusCode: Int,
    private val body: String,
) : HttpTransport {
    var lastUrl: String? = null
    var lastBody: String? = null

    override fun post(url: String, jsonBody: String): Pair<Int, String> {
        lastUrl = url
        lastBody = jsonBody
        return statusCode to body
    }
}

private class ThrowingTransport : HttpTransport {
    override fun post(url: String, jsonBody: String): Pair<Int, String> =
        throw java.net.ConnectException("Connection refused")

}

class LlmClientTest {

    @Test
    fun `grammar is included in the request body when provided`() {
        val transport = FakeTransport(200, """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}]}""")
        val client = LlmClient(transport)

        client.chat(listOf(ChatMessage("user", "hi")), maxTokens = 10, grammar = "root ::= \"FINAL:\" [^\\n]+")

        assertTrue(transport.lastBody.orEmpty().contains("\"grammar\""))
        assertTrue(transport.lastBody.orEmpty().contains("FINAL:"))
    }

    @Test
    fun `omitting grammar does not add a grammar field to the request body`() {
        val transport = FakeTransport(200, """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}]}""")
        val client = LlmClient(transport)

        client.chat(listOf(ChatMessage("user", "hi")), maxTokens = 10)

        assertTrue(!transport.lastBody.orEmpty().contains("\"grammar\""))
    }

    @Test
    fun `successful response parses content, finish reason and token count`() {
        val transport = FakeTransport(
            200,
            """{"choices":[{"message":{"content":"la conclusion est valide"},"finish_reason":"stop"}],"usage":{"completion_tokens":12}}""",
        )
        val client = LlmClient(transport)
        val result = client.chat(listOf(ChatMessage("user", "test")), maxTokens = 128)
        require(result is LlmCallResult.Success)
        assertEquals("la conclusion est valide", result.response.content)
        assertEquals("stop", result.response.finishReason)
        assertEquals(12, result.response.tokensGenerated)
    }

    @Test
    fun `request targets the configured base URL chat completions endpoint`() {
        val transport = FakeTransport(200, """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}]}""")
        val client = LlmClient(transport, baseUrl = "http://127.0.0.1:8080")
        client.chat(listOf(ChatMessage("user", "test")), maxTokens = 64)
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", transport.lastUrl)
    }

    @Test
    fun `non-200 status is reported as ServerError, not silently treated as success`() {
        val transport = FakeTransport(500, """{"error":"internal"}""")
        val client = LlmClient(transport)
        val result = client.chat(listOf(ChatMessage("user", "test")), maxTokens = 64)
        assertTrue(result is LlmCallResult.ServerError)
    }

    @Test
    fun `network exception is reported as NetworkError, never crashes the caller`() {
        val client = LlmClient(ThrowingTransport())
        val result = client.chat(listOf(ChatMessage("user", "test")), maxTokens = 64)
        assertTrue(result is LlmCallResult.NetworkError)
    }

    @Test
    fun `quotes and newlines in message content are escaped in the request body`() {
        val transport = FakeTransport(200, """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""")
        val client = LlmClient(transport)
        client.chat(listOf(ChatMessage("user", "il a dit \"bonjour\"\net voila")), maxTokens = 64)
        val body = transport.lastBody!!
        assertTrue(body.contains("\\\"bonjour\\\""))
        assertTrue(body.contains("\\n"))
    }
}
