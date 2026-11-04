package com.nuvio.tv.core.server

import com.nuvio.tv.core.ai.AiProvider
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class AiAssistantConfigServerTest {
    private var received: Pair<AiProvider, String>? = null
    private val server = AiAssistantConfigServer(
        stateProvider = { AiAssistantWebState(provider = "anthropic", configured = listOf("anthropic")) },
        onKeyReceived = { provider, key -> received = provider to key },
        token = "abc123",
    )

    private fun NanoHTTPD.Response.text(): String = data.readBytes().toString(StandardCharsets.UTF_8)

    @Test
    fun `state tells the provider and which ones have a key, never the key`() {
        val response = server.serve(FakeSession(NanoHTTPD.Method.GET, "/api/state", token = "abc123"))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("""{"provider":"anthropic","configured":["anthropic"]}""", response.text())
    }

    @Test
    fun `posted key reaches the tv trimmed`() {
        val response = server.serve(
            FakeSession(NanoHTTPD.Method.POST, "/api/key", """{"provider":"gemini","apiKey":"  AIza-test-key  "}""", token = "abc123"),
        )

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(AiProvider.Gemini to "AIza-test-key", received)
    }

    @Test
    fun `invalid requests are refused without touching the settings`() {
        listOf(
            """{"provider":"gemini","apiKey":"   "}""",
            """{"provider":"other","apiKey":"k"}""",
            """{"apiKey":"k"}""",
            "not json",
        ).forEach { body ->
            val response = server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/key", body, token = "abc123"))
            assertEquals(body, NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        }
        assertNull(received)
    }

    @Test
    fun `state and key need the token from the qr code`() {
        val valid = """{"provider":"gemini","apiKey":"k"}"""
        listOf(null, "wrong").forEach { token ->
            assertEquals(
                NanoHTTPD.Response.Status.FORBIDDEN,
                server.serve(FakeSession(NanoHTTPD.Method.GET, "/api/state", token = token)).status,
            )
            assertEquals(
                NanoHTTPD.Response.Status.FORBIDDEN,
                server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/key", valid, token = token)).status,
            )
        }
        assertNull(received)
    }

    @Test
    fun `page is served and lists the three providers`() {
        val response = server.serve(FakeSession(NanoHTTPD.Method.GET, "/"))
        val html = response.text()

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        AiProvider.entries.forEach { assertTrue(it.id, html.contains("\"${it.id}\"")) }
        assertFalse(html.contains("\${"))
    }

    private class FakeSession(
        private val method: NanoHTTPD.Method,
        private val uri: String,
        body: String = "",
        private val token: String? = null,
    ) : NanoHTTPD.IHTTPSession {
        private val bytes = body.toByteArray(StandardCharsets.UTF_8)

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf(
            "content-length" to bytes.size.toString(),
            "content-type" to "application/json; charset=utf-8",
        )
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getMethod(): NanoHTTPD.Method = method
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> =
            if (token == null) emptyMap() else mapOf("t" to listOf(token))
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uri
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}
