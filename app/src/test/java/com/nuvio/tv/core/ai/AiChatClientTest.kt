package com.nuvio.tv.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class AiChatClientTest {
    private val schema = buildJsonObject { put("type", "object") }
    private val messages = listOf(
        AiChatMessage(AiChatRole.User, "quero animes de terror"),
        AiChatMessage(AiChatRole.Assistant, """{"reply":"ok","titles":[]}"""),
        AiChatMessage(AiChatRole.User, "só filmes"),
    )

    private fun body(request: AiHttpRequest): JsonObject = Json.parseToJsonElement(request.body).jsonObject

    @Test
    fun `anthropic request uses messages api with json schema output and fallback`() {
        val request = AiChatClient.buildRequest(
            provider = AiProvider.Anthropic,
            apiKey = "sk-ant-test",
            model = "claude-opus-5-5",
            system = "instrução",
            messages = messages,
            responseSchema = schema,
        )

        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals("2026-10-21", request.headers["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", request.headers["anthropic-beta"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertNull(request.headers["Authorization"])

        val json = body(request)
        assertEquals("claude-opus-5-5", json["model"]!!.jsonPrimitive.content)
        assertEquals("instrução", json["system"]!!.jsonPrimitive.content)
        assertEquals("default", json["fallbacks"]!!.jsonPrimitive.content)
        assertFalse("temperature" in json)
        val sent = json["messages"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), sent.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val outputConfig = json["output_config"]!!.jsonObject
        assertEquals("low", outputConfig["effort"]!!.jsonPrimitive.content)
        assertEquals("json_schema", outputConfig["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(schema, outputConfig["format"]!!.jsonObject["schema"])
    }

    @Test
    fun `anthropic request skips fallback for haiku`() {
        val request = AiChatClient.buildRequest(
            provider = AiProvider.Anthropic,
            apiKey = "k",
            model = "claude-haiku-5-5",
            system = "s",
            messages = messages,
            responseSchema = schema,
        )

        assertNull(request.headers["anthropic-beta"])
        assertFalse("fallbacks" in body(request))
        assertEquals("low", body(request)["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini and openai use chat completions with system message and json object`() {
        listOf(
            AiProvider.Gemini to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            AiProvider.OpenAi to "https://api.openai.com/v1/chat/completions",
        ).forEach { (provider, url) ->
            val request = AiChatClient.buildRequest(
                provider = provider,
                apiKey = "key-123",
                model = provider.defaultModel,
                system = "instrução",
                messages = messages,
                responseSchema = schema,
            )

            assertEquals(url, request.url)
            assertEquals("Bearer key-123", request.headers["Authorization"])
            val json = body(request)
            val sent = json["messages"]!!.jsonArray
            assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("instrução", sent.first().jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals("json_object", json["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `anthropic response joins text blocks and ignores thinking`() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[{"type":"thinking","thinking":"","signature":"x"},
                        {"type":"text","text":"{\"reply\":\"Separei"},
                        {"type":"text","text":" dois.\",\"titles\":[]}"}],
             "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()

        val result = AiChatClient.parseResponse(AiProvider.Anthropic, 200, body)

        assertEquals(AiChatResult.Success("""{"reply":"Separei dois.","titles":[]}"""), result)
    }

    @Test
    fun `anthropic refusal is a failure`() {
        val body = """{"content":[],"stop_reason":"refusal","stop_details":{"type":"refusal","category":null}}"""

        assertTrue(AiChatClient.parseResponse(AiProvider.Anthropic, 200, body) is AiChatResult.Failure)
    }

    @Test
    fun `chat completions response returns message content`() {
        val body = """
            {"choices":[{"index":0,"finish_reason":"stop",
              "message":{"role":"assistant","content":"{\"reply\":\"Oi\",\"titles\":[]}","refusal":null}}]}
        """.trimIndent()

        assertEquals(
            AiChatResult.Success("""{"reply":"Oi","titles":[]}"""),
            AiChatClient.parseResponse(AiProvider.OpenAi, 200, body),
        )
    }

    @Test
    fun `http errors show the api message`() {
        val anthropic = AiChatClient.parseResponse(
            AiProvider.Anthropic,
            401,
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )
        val gemini = AiChatClient.parseResponse(
            AiProvider.Gemini,
            400,
            """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}]""",
        )
        val plain = AiChatClient.parseResponse(AiProvider.OpenAi, 502, "Bad gateway")

        assertEquals(AiChatResult.Failure("Anthropic Claude respondeu 401: invalid x-api-key"), anthropic)
        assertEquals(
            AiChatResult.Failure("Google Gemini respondeu 400: API key not valid. Please pass a valid API key."),
            gemini,
        )
        assertEquals(AiChatResult.Failure("OpenAI respondeu 502: Bad gateway"), plain)
    }

    @Test
    fun `settings payload round trip keeps keys per provider`() {
        val settings = AiAssistantSettings(
            provider = AiProvider.Anthropic,
            apiKeys = mapOf(AiProvider.Anthropic to "a", AiProvider.Gemini to "g", AiProvider.OpenAi to ""),
            models = mapOf(AiProvider.Anthropic to "claude-haiku-5-5"),
        )

        val restored = settings.toPayload().toSettings()

        assertEquals(AiProvider.Anthropic, restored.provider)
        assertEquals("a", restored.apiKey())
        assertEquals("g", restored.apiKey(AiProvider.Gemini))
        assertEquals("", restored.apiKey(AiProvider.OpenAi))
        assertEquals("claude-haiku-5-5", restored.model())
        assertEquals(AiProvider.Gemini.defaultModel, restored.model(AiProvider.Gemini))
        assertTrue(restored.isConfigured)
    }
}
