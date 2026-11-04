package com.nuvio.tv.core.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provedores de IA do assistente (mesmos do mobile e do desktop). O modelo de cada um é escolhido
 * numa lista que a API do provedor devolve.
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val keyUrl: String,
) {
    Gemini(
        id = "gemini",
        displayName = "Google Gemini",
        defaultModel = "gemini-3.5-flash-lite",
        keyUrl = "aistudio.google.com/apikey",
    ),
    Anthropic(
        id = "anthropic",
        displayName = "Anthropic Claude",
        defaultModel = "claude-opus-5-5",
        keyUrl = "console.anthropic.com/settings/keys",
    ),
    OpenAi(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "gpt-6-luna",
        keyUrl = "platform.openai.com/api-keys",
    );

    companion object {
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: Gemini
    }
}

data class AiAssistantSettings(
    val provider: AiProvider = AiProvider.Gemini,
    val apiKeys: Map<AiProvider, String> = emptyMap(),
    val models: Map<AiProvider, String> = emptyMap(),
) {
    fun apiKey(provider: AiProvider = this.provider): String = apiKeys[provider].orEmpty()

    fun model(provider: AiProvider = this.provider): String =
        models[provider]?.trim()?.takeIf(String::isNotEmpty) ?: provider.defaultModel

    fun customModel(provider: AiProvider = this.provider): String = models[provider].orEmpty()

    val isConfigured: Boolean
        get() = apiKey().isNotBlank()
}

@Serializable
internal data class AiAssistantSettingsPayload(
    val provider: String? = null,
    val apiKeys: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
)

internal fun AiAssistantSettings.toPayload(): AiAssistantSettingsPayload =
    AiAssistantSettingsPayload(
        provider = provider.id,
        apiKeys = apiKeys.filterValues(String::isNotBlank).mapKeys { it.key.id },
        models = models.filterValues(String::isNotBlank).mapKeys { it.key.id },
    )

internal fun AiAssistantSettingsPayload.toSettings(): AiAssistantSettings =
    AiAssistantSettings(
        provider = AiProvider.fromId(provider),
        apiKeys = apiKeys.mapNotNull { (id, key) ->
            AiProvider.entries.firstOrNull { it.id == id }?.let { it to key.trim() }
        }.toMap(),
        models = models.mapNotNull { (id, model) ->
            AiProvider.entries.firstOrNull { it.id == id }?.let { it to model.trim() }
        }.toMap(),
    )

/**
 * A chave fica só nesta TV (sem sincronização com a conta) e vale para todos os perfis.
 */
@Singleton
class AiAssistantSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("nuvio_ai_assistant", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AiAssistantSettings> = _settings.asStateFlow()

    fun save(provider: AiProvider, apiKey: String, model: String) {
        val current = _settings.value
        val updated = current.copy(
            provider = provider,
            apiKeys = current.apiKeys + (provider to apiKey.trim()),
            models = current.models + (provider to model.trim()),
        )
        if (updated == current) return
        _settings.value = updated
        preferences.edit()
            .putString(PAYLOAD_KEY, json.encodeToString(AiAssistantSettingsPayload.serializer(), updated.toPayload()))
            .apply()
    }

    private fun load(): AiAssistantSettings =
        preferences.getString(PAYLOAD_KEY, null)
            ?.let { payload ->
                runCatching { json.decodeFromString<AiAssistantSettingsPayload>(payload).toSettings() }.getOrNull()
            }
            ?: AiAssistantSettings()

    private companion object {
        const val PAYLOAD_KEY = "ai_assistant_settings"
    }
}
