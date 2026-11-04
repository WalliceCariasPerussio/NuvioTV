package com.nuvio.tv.core.ai

import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class AiAssistantTurnStatus {
    Loading,
    Done,
    Failed,
}

data class AiAssistantTurn(
    val id: Long,
    val prompt: String,
    val status: AiAssistantTurnStatus,
    val reply: String = "",
    val items: List<MetaPreview> = emptyList(),
    val suggestedCount: Int = 0,
    val error: String? = null,
    /** Resposta do modelo em JSON compacto, mandada de volta como histórico. */
    internal val historyText: String? = null,
)

data class AiAssistantUiState(
    val turns: List<AiAssistantTurn> = emptyList(),
) {
    val isBusy: Boolean
        get() = turns.any { it.status == AiAssistantTurnStatus.Loading }
}

/** Conversa do assistente. Fica só na memória: some ao fechar o app. */
@Singleton
class AiAssistantRepository @Inject constructor(
    private val settingsStore: AiAssistantSettingsStore,
    private val watchProgressRepository: WatchProgressRepository,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _uiState = MutableStateFlow(AiAssistantUiState())
    val uiState: StateFlow<AiAssistantUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var nextId = 1L

    fun send(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isEmpty() || _uiState.value.isBusy) return false
        val settings = settingsStore.settings.value
        if (!settings.isConfigured) return false

        val history = _uiState.value.turns
            .filter { it.status == AiAssistantTurnStatus.Done && it.historyText != null }
            .takeLast(MAX_HISTORY_TURNS)
        val turnId = nextId++
        _uiState.update { state ->
            state.copy(turns = state.turns + AiAssistantTurn(turnId, prompt, AiAssistantTurnStatus.Loading))
        }
        activeJob = scope.launch {
            val finished = runTurn(settings, history, prompt, turnId)
            _uiState.update { state ->
                state.copy(turns = state.turns.map { if (it.id == turnId) finished else it })
            }
        }
        return true
    }

    /** Tira a mensagem que falhou e manda o mesmo pedido de novo. */
    fun retry(turnId: Long) {
        val turn = _uiState.value.turns.firstOrNull { it.id == turnId } ?: return
        if (turn.status != AiAssistantTurnStatus.Failed || _uiState.value.isBusy) return
        _uiState.update { state -> state.copy(turns = state.turns.filterNot { it.id == turnId }) }
        send(turn.prompt)
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        _uiState.value = AiAssistantUiState()
    }

    private suspend fun runTurn(
        settings: AiAssistantSettings,
        history: List<AiAssistantTurn>,
        prompt: String,
        turnId: Long,
    ): AiAssistantTurn {
        val watched = withTimeoutOrNull(WATCHED_TIMEOUT_MS) { watchProgressRepository.allProgress.first() }
            .orEmpty()
            .map { progress ->
                AiWatchedTitle(
                    name = progress.name,
                    isSeries = progress.season != null || progress.contentType.lowercase() in SERIES_TYPES,
                    year = null,
                    watchedAt = progress.lastWatched,
                )
            }
        val messages = buildList {
            history.forEach { turn ->
                add(AiChatMessage(AiChatRole.User, turn.prompt))
                add(AiChatMessage(AiChatRole.Assistant, turn.historyText.orEmpty()))
            }
            add(AiChatMessage(AiChatRole.User, AiRecommendationPrompt.buildUserTurn(prompt, watched)))
        }

        val failed = AiAssistantTurn(turnId, prompt, AiAssistantTurnStatus.Failed)
        val result = AiChatClient.complete(
            provider = settings.provider,
            apiKey = settings.apiKey(),
            model = settings.model(),
            system = AiRecommendationPrompt.systemPrompt,
            messages = messages,
            responseSchema = AiRecommendationPrompt.responseSchema,
        )
        val text = when (result) {
            is AiChatResult.Failure -> return failed.copy(error = result.message)
            is AiChatResult.Success -> result.text
        }
        val reply = AiRecommendationPrompt.parseReply(text)
            ?: return failed.copy(error = "Resposta em formato inesperado: ${text.take(200)}")
        val items = AiTitleResolver.resolve(reply.titles, tmdbSettingsDataStore.settings.value.language)
        return AiAssistantTurn(
            id = turnId,
            prompt = prompt,
            status = AiAssistantTurnStatus.Done,
            reply = reply.reply,
            items = items,
            // Só conta o que foi buscado: acima do limite os títulos nem vão ao TMDB.
            suggestedCount = minOf(reply.titles.size, AiRecommendationPrompt.MAX_TITLES),
            historyText = AiRecommendationPrompt.encodeForHistory(reply),
        )
    }

    private companion object {
        const val MAX_HISTORY_TURNS = 6
        const val WATCHED_TIMEOUT_MS = 3_000L
        val SERIES_TYPES = setOf("series", "show", "tv", "anime")
    }
}
