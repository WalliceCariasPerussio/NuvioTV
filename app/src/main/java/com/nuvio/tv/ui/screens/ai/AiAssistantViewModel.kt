package com.nuvio.tv.ui.screens.ai

import androidx.lifecycle.ViewModel
import com.nuvio.tv.core.ai.AiAssistantRepository
import com.nuvio.tv.core.ai.AiAssistantSettings
import com.nuvio.tv.core.ai.AiAssistantSettingsStore
import com.nuvio.tv.core.ai.AiAssistantUiState
import com.nuvio.tv.core.ai.AiModelCatalog
import com.nuvio.tv.core.ai.AiModelListResult
import com.nuvio.tv.core.ai.AiProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val repository: AiAssistantRepository,
    private val settingsStore: AiAssistantSettingsStore,
) : ViewModel() {
    val uiState: StateFlow<AiAssistantUiState> = repository.uiState
    val settings: StateFlow<AiAssistantSettings> = settingsStore.settings

    fun send(text: String): Boolean = repository.send(text)

    fun retry(turnId: Long) = repository.retry(turnId)

    fun clear() = repository.clear()

    fun saveSettings(provider: AiProvider, apiKey: String, model: String) =
        settingsStore.save(provider, apiKey, model)

    internal suspend fun fetchModels(provider: AiProvider, apiKey: String): AiModelListResult =
        AiModelCatalog.fetch(provider, apiKey)
}
