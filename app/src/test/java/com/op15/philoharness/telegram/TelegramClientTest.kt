package com.op15.philoharness.telegram

import com.op15.philoharness.llm.HttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(private val statusCode: Int, private val body: String) : HttpTransport {
    var lastUrl: String? = null
    var lastBody: String? = null

    override fun post(url: String, jsonBody: String): Pair<Int, String> {
        lastUrl = url
        lastBody = jsonBody
        return statusCode to body
    }
}

private class ThrowingTransport : HttpTransport {
    override fun post(url: String, jsonBody: String): Pair<Int, String> = throw java.net.ConnectException("refused")
}

class TelegramClientTest {

    @Test
    fun `getUpdates parses a single text message`() {
        val json = """
            {"ok":true,"result":[{"update_id":100,"message":{"message_id":1,"chat":{"id":123456789,"type":"private"},"text":"bonjour"}}]}
        """.trimIndent()
        val client = TelegramClient(FakeTransport(200, json), "fake-token")

        val result = client.getUpdates(null)

        require(result is TelegramResult.Success<*>)
        @Suppress("UNCHECKED_CAST")
        val messages = result.value as List<TelegramMessage>
        assertEquals(1, messages.size)
        assertEquals(100L, messages[0].updateId)
        assertEquals(123456789L, messages[0].chatId)
        assertEquals("bonjour", messages[0].text)
    }

    @Test
    fun `getUpdates parses multiple messages in one response`() {
        val json = """
            {"ok":true,"result":[
              {"update_id":1,"message":{"chat":{"id":10},"text":"un"}},
              {"update_id":2,"message":{"chat":{"id":10},"text":"deux"}}
            ]}
        """.trimIndent()
        val client = TelegramClient(FakeTransport(200, json), "fake-token")

        val result = client.getUpdates(null)

        require(result is TelegramResult.Success<*>)
        @Suppress("UNCHECKED_CAST")
        val messages = result.value as List<TelegramMessage>
        assertEquals(2, messages.size)
        assertEquals("un", messages[0].text)
        assertEquals("deux", messages[1].text)
    }

    @Test
    fun `getUpdates with an offset requests updateId plus one`() {
        val transport = FakeTransport(200, """{"ok":true,"result":[]}""")
        val client = TelegramClient(transport, "fake-token")

        client.getUpdates(offsetUpdateId = 42)

        assertTrue(transport.lastBody.orEmpty().contains("\"offset\":43"))
    }

    @Test
    fun `updates without text, like photos, are silently skipped, not an error`() {
        val json = """{"ok":true,"result":[{"update_id":5,"message":{"chat":{"id":10},"photo":[{"file_id":"abc"}]}}]}"""
        val client = TelegramClient(FakeTransport(200, json), "fake-token")

        val result = client.getUpdates(null)

        require(result is TelegramResult.Success<*>)
        @Suppress("UNCHECKED_CAST")
        val messages = result.value as List<TelegramMessage>
        assertEquals(0, messages.size)
    }

    @Test
    fun `non-200 status is reported as Error`() {
        val client = TelegramClient(FakeTransport(401, """{"ok":false,"description":"Unauthorized"}"""), "bad-token")

        val result = client.getUpdates(null)

        require(result is TelegramResult.Error)
        assertTrue(result.message.contains("401"))
    }

    @Test
    fun `network exception is reported as Error, never thrown to the caller`() {
        val client = TelegramClient(ThrowingTransport(), "fake-token")

        val result = client.getUpdates(null)

        require(result is TelegramResult.Error)
    }

    @Test
    fun `sendMessage posts chat id and escaped text`() {
        val transport = FakeTransport(200, """{"ok":true}""")
        val client = TelegramClient(transport, "fake-token")

        val result = client.sendMessage(999L, "il dit \"bonjour\"\net plus")

        require(result is TelegramResult.Success<*>)
        assertTrue(transport.lastUrl.orEmpty().contains("/sendMessage"))
        assertTrue(transport.lastBody.orEmpty().contains("\"chat_id\":999"))
        assertTrue(transport.lastBody.orEmpty().contains("\\\"bonjour\\\""))
    }

    @Test
    fun `bot token never appears in a log-friendly description without the caller choosing to include it`() {
        // Pas un test de redaction automatique (TelegramClient n'a pas de journalisation
        // propre) -- documente juste que l'URL construite contient le token tel quel, donc
        // l'appelant (ex. AuditLog si jamais branche ici) doit le rediger explicitement,
        // meme discipline que AuditLog.redact() pour les futurs appelants.
        val transport = FakeTransport(200, """{"ok":true}""")
        val client = TelegramClient(transport, "SECRET123")

        client.sendMessage(1L, "test")

        assertTrue(transport.lastUrl.orEmpty().contains("SECRET123"))
    }
}
