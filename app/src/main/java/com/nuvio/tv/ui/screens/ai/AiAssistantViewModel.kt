package com.nuvio.tv.ui.screens.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.nuvio.tv.R
import com.nuvio.tv.core.ai.AiAssistantRepository
import com.nuvio.tv.core.ai.AiAssistantSettings
import com.nuvio.tv.core.ai.AiAssistantSettingsStore
import com.nuvio.tv.core.ai.AiAssistantUiState
import com.nuvio.tv.core.ai.AiModelCatalog
import com.nuvio.tv.core.ai.AiModelListResult
import com.nuvio.tv.core.ai.AiProvider
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.server.AiAssistantConfigServer
import com.nuvio.tv.core.server.AiAssistantWebState
import com.nuvio.tv.core.server.DeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** QR code para colar a chave pelo celular. [received] avisa a tela que a chave chegou. */
data class AiPhoneSetupState(
    val isActive: Boolean = false,
    val qrCodeBitmap: Bitmap? = null,
    val serverUrl: String? = null,
    val error: String? = null,
    val received: Boolean = false,
)

@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val repository: AiAssistantRepository,
    private val settingsStore: AiAssistantSettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val uiState: StateFlow<AiAssistantUiState> = repository.uiState
    val settings: StateFlow<AiAssistantSettings> = settingsStore.settings

    private var phoneServer: AiAssistantConfigServer? = null
    // Cada QR é uma sessão: o que chega de uma sessão já fechada é ignorado.
    @Volatile private var activeSession = 0
    private var sessionCounter = 0
    private val _phoneSetup = MutableStateFlow(AiPhoneSetupState())
    val phoneSetup: StateFlow<AiPhoneSetupState> = _phoneSetup.asStateFlow()

    fun send(text: String): Boolean = repository.send(text)

    fun retry(turnId: Long) = repository.retry(turnId)

    fun clear() = repository.clear()

    fun saveSettings(provider: AiProvider, apiKey: String, model: String) =
        settingsStore.save(provider, apiKey, model)

    internal suspend fun fetchModels(provider: AiProvider, apiKey: String): AiModelListResult =
        AiModelCatalog.fetch(provider, apiKey)

    fun startPhoneSetup() {
        stopServer()
        val ip = DeviceIpAddress.get(context)
        if (ip == null) {
            _phoneSetup.value = AiPhoneSetupState(error = context.getString(R.string.error_network_required))
            return
        }
        val session = ++sessionCounter
        activeSession = session
        val server = AiAssistantConfigServer.startOnAvailablePort(
            stateProvider = {
                val current = settingsStore.settings.value
                AiAssistantWebState(
                    provider = current.provider.id,
                    configured = AiProvider.entries.filter { current.apiKey(it).isNotBlank() }.map { it.id },
                )
            },
            onKeyReceived = { provider, apiKey ->
                if (session == activeSession) {
                    // Mantém o modelo já escolhido para esse provedor; o servidor sai do ar na tela.
                    settingsStore.save(provider, apiKey, settingsStore.settings.value.customModel(provider))
                    _phoneSetup.update { it.copy(received = true) }
                }
            },
            context = context,
        )
        if (server == null) {
            _phoneSetup.value = AiPhoneSetupState(error = context.getString(R.string.error_server_ports_unavailable))
            return
        }
        phoneServer = server
        val url = "http://$ip:${server.listeningPort}/?t=${server.token}"
        _phoneSetup.value = AiPhoneSetupState(
            isActive = true,
            qrCodeBitmap = QrCodeGenerator.generate(url, 512),
            serverUrl = url,
        )
    }

    fun stopPhoneSetup() {
        stopServer()
        _phoneSetup.value = AiPhoneSetupState()
    }

    private fun stopServer() {
        activeSession = 0
        phoneServer?.stop()
        phoneServer = null
    }

    override fun onCleared() {
        stopServer()
        super.onCleared()
    }
}
