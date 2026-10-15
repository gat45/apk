package com.op15.philoharness.llm

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Implementation reelle de HttpTransport via HttpURLConnection (JDK/Android
 * standard, aucune dependance supplementaire type OkHttp pour ce squelette).
 * Jamais utilisee dans les tests (qui injectent un FakeTransport) -- seule
 * classe du module `llm` qui touche reellement le reseau.
 */
class JavaHttpTransport(private val connectTimeoutMs: Int = 5_000, private val readTimeoutMs: Int = 60_000) : HttpTransport {

    override fun post(url: String, jsonBody: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs

            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(jsonBody) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return status to body
        } finally {
            connection.disconnect()
        }
    }
}
