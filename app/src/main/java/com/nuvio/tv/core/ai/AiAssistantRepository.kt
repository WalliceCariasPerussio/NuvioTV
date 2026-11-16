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

enum class AiAssistantActionKind {
    MarkWatched,
    AddToLibrary,
}

enum class AiAssistantActionStatus {
    /** Esperando a pessoa clicar em Aprovar ou Cancelar. */
    Pending,
    Running,
    Done,
    AlreadyDone,
    Failed,
    Cancelled,

    /** O título pedido não foi achado no TMDB: não há o que fazer. */
    NotFound,
}

data class AiAssistantAction(
    val kind: AiAssistantActionKind,
    /** Como o modelo escreveu o título, para mostrar quando o TMDB não acha. */
    val requestedTitle: String,
    val target: MetaPreview?,
    val status: AiAssistantActionStatus,
    /** Episódios marcados, quando a ação marcou uma série. */
    val episodeCount: Int? = null,
    val error: String? = null,
)

data class AiAssistantTurn(
    val id: Long,
    val prompt: String,
    val status: AiAssistantTurnStatus,
    val reply: String = "",
    val items: List<MetaPreview> = emptyList(),
    val suggestedCount: Int = 0,
    val error: String? = null,
    val actions: List<AiAssistantAction> = emptyList(),
    /** Resposta do modelo em JSON compacto, mandada de volta como histórico. */
    internal val historyText: String? = null,
    /** Título como o modelo escreveu (normalizado, com o tipo), alinhado com [items]. */
    internal val itemKeys: List<String> = emptyList(),
    /** Ano que o modelo deu a cada título, alinhado com [items]. */
    internal val itemYears: List<Int?> = emptyList(),
) {
    val hasPendingActions: Boolean
        get() = actions.any { it.status == AiAssistantActionStatus.Pending }
}

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
    private val actionRunner: AiAssistantActionRunner,
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

        val previousTurns = _uiState.value.turns.filter { it.status == AiAssistantTurnStatus.Done }
        val history = previousTurns.filter { it.historyText != null }.takeLast(MAX_HISTORY_TURNS)
        val turnId = nextId++
        _uiState.update { state ->
            state.copy(turns = state.turns + AiAssistantTurn(turnId, prompt, AiAssistantTurnStatus.Loading))
        }
        activeJob = scope.launch {
            val finished = runTurn(settings, history, previousTurns, prompt, turnId)
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

    /** Faz, uma de cada vez, as ações que estavam esperando aprovação nesta mensagem. */
    fun approveActions(turnId: Long) {
        var started = false
        updateTurn(turnId) { turn ->
            // A lambda do update pode rodar de novo: o valor certo é o da última execução, a que valeu.
            started = turn.hasPendingActions
            if (!started) return@updateTurn turn
            turn.copy(
                actions = turn.actions.map { action ->
                    if (action.status == AiAssistantActionStatus.Pending) {
                        action.copy(status = AiAssistantActionStatus.Running)
                    } else {
                        action
                    }
                },
            )
        }
        if (!started) return
        scope.launch {
            val actions = _uiState.value.turns.firstOrNull { it.id == turnId }?.actions.orEmpty()
            actions.forEachIndexed { index, action ->
                if (action.status != AiAssistantActionStatus.Running) return@forEachIndexed
                val outcome = actionRunner.run(action)
                updateTurn(turnId) { turn ->
                    turn.copy(
                        actions = turn.actions.mapIndexed { i, current ->
                            if (i == index) {
                                current.copy(
                                    status = outcome.status,
                                    episodeCount = outcome.episodeCount,
                                    error = outcome.error,
                                )
                            } else {
                                current
                            }
                        },
                    )
                }
            }
        }
    }

    fun cancelActions(turnId: Long) {
        updateTurn(turnId) { turn ->
            turn.copy(
                actions = turn.actions.map { action ->
                    if (action.status == AiAssistantActionStatus.Pending) {
                        action.copy(status = AiAssistantActionStatus.Cancelled)
                    } else {
                        action
                    }
                },
            )
        }
    }

    private fun updateTurn(turnId: Long, transform: (AiAssistantTurn) -> AiAssistantTurn) {
        _uiState.update { state ->
            state.copy(turns = state.turns.map { if (it.id == turnId) transform(it) else it })
        }
    }

    private suspend fun runTurn(
        settings: AiAssistantSettings,
        history: List<AiAssistantTurn>,
        previousTurns: List<AiAssistantTurn>,
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
        val language = tmdbSettingsDataStore.settings.value.language
        val messages = buildList {
            history.forEach { turn ->
                add(AiChatMessage(AiChatRole.User, turn.prompt))
                add(AiChatMessage(AiChatRole.Assistant, historyMessage(turn)))
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
        val resolved = AiTitleResolver.resolve(reply.titles, language)
        val actions = reply.actions.map { action ->
            val target = findShownTitle(action.target, previousTurns)
                ?: AiTitleResolver.resolveTitle(action.target, language)
            AiAssistantAction(
                kind = action.kind,
                requestedTitle = action.target.title,
                target = target,
                status = if (target != null) AiAssistantActionStatus.Pending else AiAssistantActionStatus.NotFound,
            )
        }
        return AiAssistantTurn(
            id = turnId,
            prompt = prompt,
            status = AiAssistantTurnStatus.Done,
            reply = reply.reply,
            items = resolved.map { it.preview },
            // Só conta o que foi buscado: acima do limite os títulos nem vão ao TMDB.
            suggestedCount = minOf(reply.titles.size, AiRecommendationPrompt.MAX_TITLES),
            actions = actions,
            // O histórico leva só o que apareceu na tela, para "o 1, 2 e 5" bater com os cartazes.
            historyText = AiRecommendationPrompt.encodeForHistory(reply.copy(titles = resolved.map { it.suggested })),
            itemKeys = resolved.map { titleKey(it.suggested.title, it.suggested.type) },
            itemYears = resolved.map { it.suggested.year },
        )
    }

    private fun historyMessage(turn: AiAssistantTurn): String =
        AiRecommendationPrompt.withActionStatuses(
            historyText = turn.historyText.orEmpty(),
            statuses = turn.actions.map(::statusForModel),
        )

    private fun statusForModel(action: AiAssistantAction): String = when (action.status) {
        AiAssistantActionStatus.Pending -> "aguardando aprovação"
        AiAssistantActionStatus.Running -> "em andamento"
        AiAssistantActionStatus.Done -> "feita"
        AiAssistantActionStatus.AlreadyDone -> "já estava feita"
        AiAssistantActionStatus.Failed -> "falhou: ${action.error.orEmpty()}"
        AiAssistantActionStatus.Cancelled -> "cancelada pela pessoa"
        AiAssistantActionStatus.NotFound -> "título não achado no TMDB, nada foi feito"
    }

    /**
     * A ação costuma apontar para um cartaz que já apareceu ("marca o 1 e o 2"). Usar o mesmo
     * cartaz evita que uma busca nova no TMDB caia em outro título com o mesmo nome.
     */
    private fun findShownTitle(target: AiSuggestedTitle, turns: List<AiAssistantTurn>): MetaPreview? {
        val key = titleKey(target.title, target.type)
        val wantedType = target.type.wireName
        turns.asReversed().forEach { turn ->
            turn.itemKeys.forEachIndexed { index, itemKey ->
                if (itemKey == key && yearsMatch(target.year, turn.itemYears.getOrNull(index))) {
                    turn.items.getOrNull(index)?.let { return it }
                }
            }
            turn.items.firstOrNull { item ->
                item.apiType == wantedType &&
                    item.name.normalizedTitle() == target.title.normalizedTitle() &&
                    yearsMatch(target.year, item.releaseInfo?.take(4)?.toIntOrNull())
            }?.let { return it }
        }
        return null
    }

    private fun titleKey(title: String, type: AiTitleType): String = "${title.normalizedTitle()}|${type.wireName}"

    /** The Office de 2001 e o de 2005 têm o mesmo nome: com os dois anos conhecidos, eles decidem. */
    private fun yearsMatch(wanted: Int?, shown: Int?): Boolean = wanted == null || shown == null || wanted == shown

    private companion object {
        /** Quantas trocas anteriores vão como histórico em cada pedido. */
        const val MAX_HISTORY_TURNS = 6
        const val WATCHED_TIMEOUT_MS = 3_000L
        val SERIES_TYPES = setOf("series", "show", "tv", "anime")
    }
}
