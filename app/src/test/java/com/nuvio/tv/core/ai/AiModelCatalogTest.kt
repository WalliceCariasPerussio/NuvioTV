package com.nuvio.tv.core.ai

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AiModelCatalogTest {
    private fun success(result: AiModelListResult): List<AiModelOption> =
        (result as AiModelListResult.Success).models

    @Test
    fun `anthropic list keeps api order and display names`() {
        val body = """
            {"data":[
              {"type":"model","id":"claude-sonnet-5-5","display_name":"Claude Sonnet 5.5","created_at":"2026-09-01T00:00:00Z"},
              {"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5","created_at":"2026-08-01T00:00:00Z"}
            ],"has_more":false,"first_id":"claude-sonnet-5-5","last_id":"claude-haiku-5-5"}
        """.trimIndent()

        assertEquals(
            listOf(
                AiModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5"),
                AiModelOption("claude-haiku-5-5", "Claude Haiku 5.5"),
            ),
            success(AiModelCatalog.parseList(AiProvider.Anthropic, 200, body)),
        )
    }

    @Test
    fun `openai list keeps chat models only, newest first`() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-5-mini","created":100,"owned_by":"openai"},
              {"id":"gpt-6-luna","created":300,"owned_by":"openai"},
              {"id":"gpt-6-luna-2026-09-01","created":301,"owned_by":"openai"},
              {"id":"text-embedding-3-small","created":400,"owned_by":"openai"},
              {"id":"gpt-realtime-2.1-mini","created":500,"owned_by":"openai"},
              {"id":"o4-mini","created":200,"owned_by":"openai"},
              {"id":"whisper-1","created":50,"owned_by":"openai"}
            ]}
        """.trimIndent()

        assertEquals(
            listOf("gpt-6-luna", "o4-mini", "gpt-5-mini"),
            success(AiModelCatalog.parseList(AiProvider.OpenAi, 200, body)).map { it.id },
        )
    }

    @Test
    fun `gemini list keeps generate content chat models, newest version first`() {
        val body = """
            {"models":[
              {"name":"models/gemini-3.1-pro","displayName":"Gemini 3.1 Pro","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-3.5-flash-lite","displayName":"Gemini 3.5 Flash-Lite","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-3.5-flash-preview-tts","displayName":"TTS","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.0-flash-001","displayName":"Gemini 2.0 Flash 001","supportedGenerationMethods":["generateContent"]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                AiModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"),
                AiModelOption("gemini-3.1-pro", "Gemini 3.1 Pro"),
            ),
            success(AiModelCatalog.parseList(AiProvider.Gemini, 200, body)),
        )
    }

    @Test
    fun `list errors show the api message`() {
        val result = AiModelCatalog.parseList(
            AiProvider.Anthropic,
            401,
            """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )

        assertEquals(AiModelListResult.Failure("Anthropic Claude respondeu 401: invalid x-api-key"), result)
        assertTrue(AiModelCatalog.parseList(AiProvider.OpenAi, 200, """{"data":[]}""") is AiModelListResult.Failure)
    }

    @Test
    fun `options always show the default and the selected model first`() {
        val loaded = listOf(
            AiModelOption("claude-opus-5-5", "Claude Opus 5.5"),
            AiModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            AiModelOption("claude-haiku-5-5", "Claude Haiku 5.5"),
        )

        assertEquals(
            listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"),
            AiModelCatalog.options(loaded, AiProvider.Anthropic, selected = "claude-sonnet-5-5").map { it.id },
        )
        // Sem a lista (chave ainda não colada), o padrão continua escolhível.
        assertEquals(
            listOf(AiModelOption("claude-opus-5-5", "claude-opus-5-5")),
            AiModelCatalog.options(emptyList(), AiProvider.Anthropic, selected = "claude-opus-5-5"),
        )
    }

    @Test
    fun `list requests put the key in headers, never in the url`() {
        AiProvider.entries.forEach { provider ->
            val request = AiModelCatalog.buildListRequest(provider, "secret-key")
            assertFalse(provider.id, "secret-key" in request.url)
            assertTrue(provider.id, request.headers.values.any { "secret-key" in it })
        }
    }
}
