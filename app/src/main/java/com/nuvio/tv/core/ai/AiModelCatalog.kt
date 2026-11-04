package com.nuvio.tv.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

data class AiModelOption(
    val id: String,
    val label: String,
)

internal sealed interface AiModelListResult {
    data class Success(val models: List<AiModelOption>) : AiModelListResult
    data class Failure(val message: String) : AiModelListResult
}

/**
 * Lista os modelos de chat que a chave da pessoa pode usar, direto da API do provedor, para ela
 * escolher num botão em vez de digitar o nome.
 */
internal object AiModelCatalog {
    const val MAX_OPTIONS = 8

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, List<AiModelOption>>()

    private val openAiExcluded = listOf(
        "audio", "realtime", "tts", "transcribe", "image", "search", "embedding", "instruct",
        "moderation", "whisper", "dall-e", "computer-use", "codex",
    )
    private val geminiExcluded = listOf(
        "embedding", "tts", "image", "live", "audio", "robotics", "computer-use", "aqa",
    )
    private val datedSnapshot = Regex("-\\d{4}-\\d{2}-\\d{2}$")
    private val numberedSnapshot = Regex("-\\d{3}$")
    private val openAiReasoning = Regex("^o\\d")
    private val geminiVersion = Regex("^gemini-(\\d+(?:\\.\\d+)?)")

    suspend fun fetch(provider: AiProvider, apiKey: String): AiModelListResult {
        val cacheKey = "${provider.id}:${apiKey.hashCode()}"
        cache[cacheKey]?.let { return AiModelListResult.Success(it) }
        val request = buildListRequest(provider, apiKey)
        val response = try {
            AiHttp.get(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AiModelListResult.Failure("Sem conexão com ${provider.displayName}: ${error.message ?: error::class.simpleName}")
        }
        val result = parseList(provider, response.status, response.body)
        if (result is AiModelListResult.Success) cache[cacheKey] = result.models
        return result
    }

    fun buildListRequest(provider: AiProvider, apiKey: String): AiHttpRequest = when (provider) {
        AiProvider.Anthropic -> AiHttpRequest(
            url = "https://api.anthropic.com/v1/models?limit=100",
            headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to AiChatClient.ANTHROPIC_VERSION,
            ),
            body = "",
        )
        AiProvider.OpenAi -> AiHttpRequest(
            url = "https://api.openai.com/v1/models",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = "",
        )
        // A chave vai no cabeçalho, não na URL.
        AiProvider.Gemini -> AiHttpRequest(
            url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
            headers = mapOf("x-goog-api-key" to apiKey),
            body = "",
        )
    }

    fun parseList(provider: AiProvider, status: Int, body: String): AiModelListResult {
        if (status !in 200..299) {
            return AiModelListResult.Failure(
                "${provider.displayName} respondeu $status: ${AiChatClient.extractErrorMessage(body)}",
            )
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return AiModelListResult.Failure("Lista de modelos ilegível de ${provider.displayName}.")
        val models = when (provider) {
            AiProvider.Anthropic -> parseAnthropic(root)
            AiProvider.OpenAi -> parseOpenAi(root)
            AiProvider.Gemini -> parseGemini(root)
        }.distinctBy { it.id }
        return if (models.isEmpty()) {
            AiModelListResult.Failure("${provider.displayName} não devolveu nenhum modelo de chat.")
        } else {
            AiModelListResult.Success(models)
        }
    }

    /**
     * Os botões mostrados: o modelo padrão e o escolhido sempre aparecem (mesmo sem a lista carregada),
     * depois os mais novos da lista.
     */
    fun options(models: List<AiModelOption>, provider: AiProvider, selected: String): List<AiModelOption> {
        val byId = models.associateBy { it.id }
        val pinned = listOf(provider.defaultModel, selected)
            .filter(String::isNotBlank)
            .distinct()
            .map { id -> byId[id] ?: AiModelOption(id, id) }
        val rest = models.filter { option -> pinned.none { it.id == option.id } }
        return (pinned + rest).take(maxOf(MAX_OPTIONS, pinned.size))
    }

    // A API da Anthropic devolve do mais novo para o mais antigo.
    private fun parseAnthropic(root: JsonObject): List<AiModelOption> =
        root.objects("data").mapNotNull { model ->
            val id = model.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AiModelOption(id = id, label = model.string("display_name")?.takeIf(String::isNotBlank) ?: id)
        }

    private fun parseOpenAi(root: JsonObject): List<AiModelOption> =
        root.objects("data")
            .mapNotNull { model ->
                val id = model.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                if (!isOpenAiChatModel(id)) return@mapNotNull null
                val created = (model["created"] as? JsonPrimitive)?.longOrNull ?: 0L
                created to AiModelOption(id = id, label = id)
            }
            .sortedByDescending { it.first }
            .map { it.second }

    private fun parseGemini(root: JsonObject): List<AiModelOption> =
        root.objects("models")
            .mapNotNull { model ->
                val id = model.string("name")?.removePrefix("models/")?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                val methods = (model["supportedGenerationMethods"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if ("generateContent" !in methods || !isGeminiChatModel(id)) return@mapNotNull null
                AiModelOption(id = id, label = model.string("displayName")?.takeIf(String::isNotBlank) ?: id)
            }
            .sortedByDescending { option ->
                geminiVersion.find(option.id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            }

    internal fun isOpenAiChatModel(id: String): Boolean =
        (id.startsWith("gpt-") || openAiReasoning.containsMatchIn(id)) &&
            openAiExcluded.none { it in id } &&
            !datedSnapshot.containsMatchIn(id)

    internal fun isGeminiChatModel(id: String): Boolean =
        id.startsWith("gemini-") &&
            geminiExcluded.none { it in id } &&
            !numberedSnapshot.containsMatchIn(id)
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
