package com.nuvio.tv.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal enum class AiChatRole(val wireName: String) {
    User("user"),
    Assistant("assistant"),
}

internal data class AiChatMessage(
    val role: AiChatRole,
    val text: String,
)

internal sealed interface AiChatResult {
    data class Success(val text: String) : AiChatResult
    data class Failure(val message: String) : AiChatResult
}

/**
 * Chama o modelo de chat do provedor escolhido. A Anthropic vai pela Messages API; OpenAI e
 * Gemini vão pelo formato de chat completions (o Gemini pelo endpoint compatível com a OpenAI).
 * O pedido é sempre de resposta em JSON.
 */
internal object AiChatClient {
    const val ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
    const val ANTHROPIC_VERSION = "2026-10-21"
    const val ANTHROPIC_FALLBACK_BETA = "server-side-fallback-2026-07-01"
    const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    private const val ANTHROPIC_MAX_TOKENS = 8192
    private const val ERROR_SNIPPET_LENGTH = 400

    /** Modelos que aceitam `fallbacks: "default"` (recusa do classificador vai para outro modelo). */
    private val anthropicFallbackModels = setOf(
        "claude-opus-5-5",
        "claude-opus-5",
        "claude-sonnet-5-5",
        "claude-fable-5-1",
        "claude-fable-5",
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun complete(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiChatMessage>,
        responseSchema: JsonObject,
    ): AiChatResult {
        val request = buildRequest(provider, apiKey, model, system, messages, responseSchema)
        val response = try {
            AiHttp.post(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AiChatResult.Failure("Sem conexão com ${provider.displayName}: ${error.message ?: error::class.simpleName}")
        }
        return parseResponse(provider, response.status, response.body)
    }

    fun buildRequest(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiChatMessage>,
        responseSchema: JsonObject,
    ): AiHttpRequest = when (provider) {
        AiProvider.Anthropic -> buildAnthropicRequest(apiKey, model, system, messages, responseSchema)
        AiProvider.OpenAi -> buildChatCompletionsRequest(OPENAI_URL, apiKey, model, system, messages)
        AiProvider.Gemini -> buildChatCompletionsRequest(GEMINI_URL, apiKey, model, system, messages)
    }

    fun parseResponse(provider: AiProvider, status: Int, body: String): AiChatResult {
        if (status !in 200..299) {
            return AiChatResult.Failure("${provider.displayName} respondeu $status: ${extractErrorMessage(body)}")
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return AiChatResult.Failure("Resposta ilegível de ${provider.displayName}: ${body.snippet()}")
        return when (provider) {
            AiProvider.Anthropic -> parseAnthropic(root)
            AiProvider.OpenAi, AiProvider.Gemini -> parseChatCompletions(root, provider)
        }
    }

    private fun buildAnthropicRequest(
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiChatMessage>,
        responseSchema: JsonObject,
    ): AiHttpRequest {
        val useFallbacks = model in anthropicFallbackModels
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("x-api-key", apiKey)
            put("anthropic-version", ANTHROPIC_VERSION)
            if (useFallbacks) put("anthropic-beta", ANTHROPIC_FALLBACK_BETA)
        }
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", ANTHROPIC_MAX_TOKENS)
            put("system", system)
            put("messages", buildJsonArray {
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wireName)
                        put("content", message.text)
                    })
                }
            })
            put("output_config", buildJsonObject {
                if (anthropicSupportsEffort(model)) put("effort", "low")
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("schema", responseSchema)
                })
            })
            if (useFallbacks) put("fallbacks", "default")
        }
        return AiHttpRequest(ANTHROPIC_URL, headers, body.toString())
    }

    private fun buildChatCompletionsRequest(
        url: String,
        apiKey: String,
        model: String,
        system: String,
        messages: List<AiChatMessage>,
    ): AiHttpRequest {
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Authorization" to "Bearer $apiKey",
        )
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
                messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.wireName)
                        put("content", message.text)
                    })
                }
            })
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        return AiHttpRequest(url, headers, body.toString())
    }

    private fun parseAnthropic(root: JsonObject): AiChatResult {
        when (root.string("stop_reason")) {
            "refusal" -> return AiChatResult.Failure("O modelo recusou este pedido. Tente com outras palavras.")
            "max_tokens" -> return AiChatResult.Failure("A resposta passou do limite de tamanho. Peça menos títulos.")
        }
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "text" }
            .mapNotNull { it.string("text") }
            .joinToString("")
        return if (text.isBlank()) {
            AiChatResult.Failure("A Anthropic respondeu sem texto.")
        } else {
            AiChatResult.Success(text)
        }
    }

    private fun parseChatCompletions(root: JsonObject, provider: AiProvider): AiChatResult {
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return AiChatResult.Failure("${provider.displayName} respondeu sem resultado.")
        if (choice.string("finish_reason") == "length") {
            return AiChatResult.Failure("A resposta passou do limite de tamanho. Peça menos títulos.")
        }
        val message = choice["message"] as? JsonObject
            ?: return AiChatResult.Failure("${provider.displayName} respondeu sem mensagem.")
        message.string("refusal")?.takeIf(String::isNotBlank)?.let { refusal ->
            return AiChatResult.Failure("O modelo recusou: $refusal")
        }
        val content = when (val raw = message["content"]) {
            is JsonPrimitive -> raw.contentOrNull
            is JsonArray -> raw.mapNotNull { (it as? JsonObject)?.string("text") }.joinToString("")
            else -> null
        }
        return if (content.isNullOrBlank()) {
            AiChatResult.Failure("${provider.displayName} respondeu sem texto.")
        } else {
            AiChatResult.Success(content)
        }
    }

    /** Pega a mensagem de erro dos formatos `{"error": {...}}` e `[{"error": {...}}]`. */
    internal fun extractErrorMessage(body: String): String {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
        val error = when (element) {
            is JsonObject -> element["error"]
            is JsonArray -> (element.firstOrNull() as? JsonObject)?.get("error")
            else -> null
        }
        val message = when (error) {
            is JsonObject -> error.string("message")
            is JsonPrimitive -> error.contentOrNull
            else -> null
        }
        return message?.takeIf(String::isNotBlank)?.snippet() ?: body.snippet().ifBlank { "(sem detalhes)" }
    }

    private fun anthropicSupportsEffort(model: String): Boolean =
        !model.startsWith("claude-3") &&
            !model.startsWith("claude-haiku-4") &&
            model != "claude-sonnet-4-5" &&
            !model.startsWith("claude-sonnet-4-5-") &&
            !model.startsWith("claude-opus-4-1") &&
            !model.startsWith("claude-opus-4-0") &&
            model != "claude-opus-4" &&
            model != "claude-sonnet-4"

    private fun String.snippet(): String {
        val clean = trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= ERROR_SNIPPET_LENGTH) clean else clean.take(ERROR_SNIPPET_LENGTH) + "…"
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
