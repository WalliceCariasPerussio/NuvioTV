package com.nuvio.tv.ui.screens.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.ai.AiAssistantSettings
import com.nuvio.tv.core.ai.AiAssistantTurn
import com.nuvio.tv.core.ai.AiAssistantTurnStatus
import com.nuvio.tv.core.ai.AiModelCatalog
import com.nuvio.tv.core.ai.AiModelListResult
import com.nuvio.tv.core.ai.AiModelOption
import com.nuvio.tv.core.ai.AiProvider
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.screens.collection.NuvioButton
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Assistente de IA: a pessoa pede "animes de terror" (digitando ou falando) e recebe uma linha de
 * cartazes do TMDB. Aberto pelo botão de estrelas na linha do campo de busca.
 */
@Composable
fun AiAssistantScreen(
    viewModel: AiAssistantViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    var prompt by rememberSaveable { mutableStateOf("") }
    val inputFocusRequester = remember { FocusRequester() }
    // Alvo de foco que nunca sai da tela nem fica desativado depois de configurado, e que não abre o
    // teclado como o campo de texto.
    val micFocusRequester = remember { FocusRequester() }
    val setupFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    // Cartaz aberto por último: ao voltar dos detalhes, o foco volta para ele.
    var restoreTurnId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreItemIndex by rememberSaveable { mutableStateOf(-1) }
    var scrolledForTurnId by rememberSaveable { mutableStateOf<Long?>(null) }
    val phoneSetup by viewModel.phoneSetup.collectAsState()
    // Muda quando a chave chega pelo celular: recria o cartão com a chave e o provedor novos.
    var setupGeneration by rememberSaveable { mutableStateOf(0) }
    val context = LocalContext.current
    val keyReceivedMessage = stringResource(R.string.ai_assistant_key_received)

    // O botão apertado pode sair da tela (Nova conversa, Tentar de novo, Alterar, Salvar): o foco vai
    // para um alvo que continua nela, senão o D-pad fica sem resposta.
    fun focusSoon(requester: FocusRequester) {
        coroutineScope.launch { requestFocusAfterLayout(requester) }
    }

    fun submit(text: String): Boolean {
        if (!viewModel.send(text)) return false
        prompt = ""
        keyboardController?.hide()
        // A sugestão tocada sai da tela e o Enviar fica desativado: o foco vai para o microfone.
        focusSoon(micFocusRequester)
        return true
    }

    // Falado com outro pedido em andamento: o texto fica no campo para enviar depois, não se perde.
    val voice = rememberAiVoiceInput(onResult = { recognized -> if (!submit(recognized)) prompt = recognized })

    LaunchedEffect(Unit) {
        if (restoreTurnId != null) return@LaunchedEffect // a linha do cartaz restaura o foco
        requestFocusAfterLayout(if (settings.isConfigured) micFocusRequester else setupFocusRequester)
    }
    LaunchedEffect(phoneSetup.received) {
        if (!phoneSetup.received) return@LaunchedEffect
        setupGeneration++
        editing = true // o cartão fica aberto para escolher o modelo com a chave nova
        Toast.makeText(context, keyReceivedMessage, Toast.LENGTH_SHORT).show()
        // Deixa a resposta chegar ao celular antes de tirar o servidor do ar.
        delay(1_000)
        viewModel.stopPhoneSetup()
        requestFocusAfterLayout(setupFocusRequester)
    }
    // App em segundo plano (Home) com o QR aberto: o servidor sai do ar.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopPhoneSetup()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(phoneSetup.error) {
        val error = phoneSetup.error ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        viewModel.stopPhoneSetup()
    }
    // A resposta mais nova fica em cima, logo abaixo do campo. Só rola quando chega uma nova, não
    // ao voltar dos detalhes.
    val newestTurnId = uiState.turns.lastOrNull()?.id
    LaunchedEffect(newestTurnId) {
        if (newestTurnId != null && newestTurnId != scrolledForTurnId) {
            scrolledForTurnId = newestTurnId
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        item(key = "ai_header") {
            AiHeader(
                settings = settings,
                editing = editing,
                hasTurns = uiState.turns.isNotEmpty(),
                onChange = {
                    editing = true
                    focusSoon(setupFocusRequester)
                },
                onNewChat = {
                    viewModel.clear()
                    restoreTurnId = null
                    focusSoon(micFocusRequester)
                },
            )
        }
        item(key = "ai_input") {
            AiPromptRow(
                prompt = prompt,
                onPromptChange = { prompt = it },
                enabled = settings.isConfigured,
                busy = uiState.isBusy,
                listening = voice.listening,
                onSubmit = { submit(prompt) },
                onVoice = voice.start,
                focusRequester = inputFocusRequester,
                micFocusRequester = micFocusRequester,
            )
        }
        if (!settings.isConfigured || editing) {
            item(key = "ai_setup_$setupGeneration") {
                AiSetupCard(
                    settings = settings,
                    canCancel = settings.isConfigured,
                    firstFocusRequester = setupFocusRequester,
                    onPhoneSetup = viewModel::startPhoneSetup,
                    onSave = { provider, apiKey, model ->
                        viewModel.saveSettings(provider, apiKey, model)
                        editing = false
                        focusSoon(micFocusRequester)
                    },
                    onCancel = {
                        editing = false
                        focusSoon(micFocusRequester)
                    },
                    fetchModels = viewModel::fetchModels,
                )
            }
        }
        if (uiState.turns.isEmpty() && settings.isConfigured) {
            item(key = "ai_empty") {
                AiEmptyState(onSuggestion = { submit(it) })
            }
        }
        items(
            items = uiState.turns.asReversed(),
            key = { turn -> "ai_turn_${turn.id}" },
        ) { turn ->
            AiTurnItem(
                turn = turn,
                restoreItemIndex = if (turn.id == restoreTurnId) restoreItemIndex else -1,
                onRetry = {
                    viewModel.retry(turn.id)
                    focusSoon(micFocusRequester)
                },
                onItemOpened = { index ->
                    restoreTurnId = turn.id
                    restoreItemIndex = index
                },
                onItemFocused = {
                    // Foco já voltou para o cartaz: a próxima abertura da tela começa no campo.
                    if (restoreTurnId != null) restoreTurnId = null
                },
                onNavigateToDetail = onNavigateToDetail,
            )
        }
    }

    if (phoneSetup.isActive) {
        QrCodeOverlay(
            qrBitmap = phoneSetup.qrCodeBitmap,
            serverUrl = phoneSetup.serverUrl,
            instruction = stringResource(R.string.ai_assistant_phone_instruction),
            onClose = {
                viewModel.stopPhoneSetup()
                focusSoon(setupFocusRequester)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}

@Composable
private fun AiHeader(
    settings: AiAssistantSettings,
    editing: Boolean,
    hasTurns: Boolean,
    onChange: () -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = NuvioTheme.colors.Secondary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        Text(
            text = stringResource(R.string.ai_assistant_title),
            style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary,
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
        if (settings.isConfigured) {
            Text(
                text = "${settings.provider.displayName} · ${settings.model()}",
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!editing) {
                NuvioButton(onClick = onChange) { Text(stringResource(R.string.ai_assistant_change)) }
            }
            if (hasTurns) {
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
                NuvioButton(onClick = onNewChat) { Text(stringResource(R.string.ai_assistant_new_chat)) }
            }
        }
    }
}

@Composable
private fun AiPromptRow(
    prompt: String,
    onPromptChange: (String) -> Unit,
    enabled: Boolean,
    busy: Boolean,
    listening: Boolean,
    onSubmit: () -> Unit,
    onVoice: () -> Unit,
    focusRequester: FocusRequester,
    micFocusRequester: FocusRequester,
) {
    val canSend = enabled && !busy && prompt.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fica ativo mesmo ocupado ou escutando: desativar o botão focado tira o foco do D-pad.
        // Apertar durante a escuta cancela; com um pedido em andamento, o envio só é recusado.
        AiSquareIconButton(
            onClick = onVoice,
            enabled = enabled,
            highlighted = listening,
            modifier = Modifier.focusRequester(micFocusRequester),
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = stringResource(R.string.ai_assistant_voice),
                tint = if (listening) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary,
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    val code = keyEvent.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                        if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && canSend) onSubmit()
                        true
                    } else {
                        false
                    }
                },
            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            placeholder = {
                Text(
                    text = stringResource(R.string.ai_assistant_prompt_placeholder),
                    color = NuvioTheme.colors.TextTertiary,
                )
            },
            colors = aiTextFieldColors(),
        )
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        AiSquareIconButton(onClick = onSubmit, enabled = canSend) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.ai_assistant_send),
                tint = if (canSend) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
            )
        }
    }
}

@Composable
private fun AiSquareIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .size(NuvioTheme.spacing.huge)
            .border(
                width = if (focused || highlighted) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                color = when {
                    highlighted -> NuvioTheme.colors.Secondary
                    focused -> NuvioTheme.colors.FocusRing
                    else -> NuvioTheme.colors.Border
                },
                shape = shape,
            )
            .background(
                color = if (highlighted) NuvioTheme.colors.Secondary.copy(alpha = 0.15f) else NuvioTheme.colors.BackgroundCard,
                shape = shape,
            ),
    ) {
        content()
    }
}

@Composable
private fun aiTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = NuvioTheme.colors.BackgroundCard,
    unfocusedContainerColor = NuvioTheme.colors.BackgroundCard,
    disabledContainerColor = NuvioTheme.colors.BackgroundCard,
    focusedIndicatorColor = NuvioTheme.colors.FocusRing,
    unfocusedIndicatorColor = NuvioTheme.colors.Border,
    focusedTextColor = NuvioTheme.colors.TextPrimary,
    unfocusedTextColor = NuvioTheme.colors.TextPrimary,
    cursorColor = NuvioTheme.colors.FocusRing,
    focusedLabelColor = NuvioTheme.colors.TextSecondary,
    unfocusedLabelColor = NuvioTheme.colors.TextSecondary,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AiChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusRing.copy(alpha = 0.2f) else NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (selected) NuvioTheme.spacing.xxs else NuvioTheme.spacing.hairline,
                    color = if (selected) NuvioTheme.colors.FocusRing else NuvioTheme.colors.Border,
                ),
                shape = shape,
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape,
            ),
        ),
        shape = ButtonDefaults.shape(shape),
    ) {
        Text(text = label, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSetupCard(
    settings: AiAssistantSettings,
    canCancel: Boolean,
    firstFocusRequester: FocusRequester,
    onPhoneSetup: () -> Unit,
    onSave: (AiProvider, String, String) -> Unit,
    onCancel: () -> Unit,
    fetchModels: suspend (AiProvider, String) -> AiModelListResult,
) {
    var providerId by rememberSaveable { mutableStateOf(settings.provider.id) }
    val provider = AiProvider.fromId(providerId)
    var apiKey by rememberSaveable(providerId) { mutableStateOf(settings.apiKey(provider)) }
    var model by rememberSaveable(providerId) { mutableStateOf(settings.customModel(provider)) }
    val freeLabel = stringResource(R.string.ai_assistant_free)

    Column(
        modifier = Modifier
            .padding(horizontal = NuvioTheme.spacing.xxxl)
            .fillMaxWidth()
            .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.lg))
            .padding(NuvioTheme.spacing.xl),
    ) {
        Text(
            text = stringResource(R.string.ai_assistant_setup_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.ai_assistant_setup_message),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            AiProvider.entries.forEach { option ->
                AiChoiceChip(
                    label = if (option == AiProvider.Gemini) "${option.displayName} ($freeLabel)" else option.displayName,
                    selected = option == provider,
                    onClick = { providerId = option.id },
                    modifier = if (option == provider) Modifier.focusRequester(firstFocusRequester) else Modifier,
                )
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim() },
            singleLine = true,
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
            label = { Text(stringResource(R.string.ai_assistant_key_label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(NuvioTheme.radii.md),
            colors = aiTextFieldColors(),
        )
        Text(
            text = stringResource(R.string.ai_assistant_key_hint, provider.keyUrl),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        NuvioButton(onClick = onPhoneSetup) {
            Text(stringResource(R.string.ai_assistant_phone_setup))
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        AiModelPicker(
            provider = provider,
            apiKey = apiKey,
            selected = model.ifBlank { provider.defaultModel },
            onSelect = { id -> model = if (id == provider.defaultModel) "" else id },
            fetchModels = fetchModels,
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            if (canCancel) {
                NuvioButton(onClick = onCancel) { Text(stringResource(R.string.ai_assistant_cancel)) }
            }
            NuvioButton(
                onClick = { onSave(provider, apiKey, model) },
                enabled = apiKey.isNotBlank(),
            ) {
                Text(stringResource(R.string.ai_assistant_save))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelPicker(
    provider: AiProvider,
    apiKey: String,
    selected: String,
    onSelect: (String) -> Unit,
    fetchModels: suspend (AiProvider, String) -> AiModelListResult,
) {
    var models by remember(provider) { mutableStateOf<List<AiModelOption>>(emptyList()) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    var loading by remember(provider) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(provider, apiKey, reload) {
        models = emptyList()
        error = null
        if (apiKey.isBlank()) return@LaunchedEffect
        delay(400)
        loading = true
        try {
            when (val result = fetchModels(provider, apiKey)) {
                is AiModelListResult.Success -> models = result.models
                is AiModelListResult.Failure -> error = result.message
            }
        } finally {
            loading = false
        }
    }

    Text(
        text = stringResource(R.string.ai_assistant_model_title),
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
    )
    val status = when {
        apiKey.isBlank() -> stringResource(R.string.ai_assistant_models_need_key)
        loading -> stringResource(R.string.ai_assistant_models_loading)
        else -> null
    }
    if (status != null) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs),
        )
    }
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
    val defaultLabel = stringResource(R.string.ai_assistant_model_default)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
    ) {
        AiModelCatalog.options(models, provider, selected).forEach { option ->
            AiChoiceChip(
                label = if (option.id == provider.defaultModel) "${option.label} · $defaultLabel" else option.label,
                selected = option.id == selected,
                onClick = { onSelect(option.id) },
            )
        }
    }
    error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.Error,
            modifier = Modifier.padding(top = NuvioTheme.spacing.md),
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
        NuvioButton(onClick = { reload++ }) { Text(stringResource(R.string.ai_assistant_retry)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiEmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.ai_assistant_suggestion_1),
        stringResource(R.string.ai_assistant_suggestion_2),
        stringResource(R.string.ai_assistant_suggestion_3),
        stringResource(R.string.ai_assistant_suggestion_4),
    )
    Column(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)) {
        Text(
            text = stringResource(R.string.ai_assistant_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.ai_assistant_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        ) {
            suggestions.forEach { suggestion ->
                AiChoiceChip(label = suggestion, selected = false, onClick = { onSuggestion(suggestion) })
            }
        }
    }
}

@Composable
private fun AiTurnItem(
    turn: AiAssistantTurn,
    restoreItemIndex: Int,
    onRetry: () -> Unit,
    onItemOpened: (Int) -> Unit,
    onItemFocused: () -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
) {
    val resultsTitle = stringResource(R.string.ai_assistant_results_title)
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxxl),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = turn.prompt,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(NuvioTheme.radii.lg))
                    .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))
        val textModifier = Modifier.padding(horizontal = NuvioTheme.spacing.xxxl)
        when (turn.status) {
            AiAssistantTurnStatus.Loading -> {
                Text(
                    text = stringResource(R.string.ai_assistant_thinking),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = textModifier,
                )
            }

            AiAssistantTurnStatus.Failed -> {
                Column(modifier = textModifier) {
                    Text(
                        text = turn.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.Error,
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                    NuvioButton(onClick = onRetry) { Text(stringResource(R.string.ai_assistant_retry)) }
                }
            }

            AiAssistantTurnStatus.Done -> {
                if (turn.reply.isNotBlank()) {
                    Text(
                        text = turn.reply,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = textModifier,
                    )
                }
                if (turn.items.isNotEmpty()) {
                    val row = remember(turn.id, turn.items) {
                        CatalogRow(
                            addonId = "ai_assistant",
                            addonName = "",
                            addonBaseUrl = "",
                            catalogId = "ai_turn_${turn.id}",
                            catalogName = resultsTitle,
                            type = ContentType.MOVIE,
                            items = turn.items,
                            hasMore = false,
                        )
                    }
                    CatalogRowSection(
                        catalogRow = row,
                        showSeeAll = false,
                        showAddonName = false,
                        showCatalogTypeSuffix = false,
                        focusedItemIndex = restoreItemIndex,
                        restorerFocusedIndex = restoreItemIndex,
                        onItemFocus = { onItemFocused() },
                        onItemClick = { id, type, addonBaseUrl ->
                            onItemOpened(turn.items.indexOfFirst { it.id == id && it.apiType == type }.coerceAtLeast(0))
                            HeroBackdropState.update(turn.items.firstOrNull { it.id == id }?.background)
                            onNavigateToDetail(id, type, addonBaseUrl)
                        },
                    )
                }
                val missing = turn.suggestedCount - turn.items.size
                val note = when {
                    turn.suggestedCount > 0 && turn.items.isEmpty() -> stringResource(R.string.ai_assistant_none_found)
                    missing > 0 -> stringResource(R.string.ai_assistant_some_missing, missing)
                    else -> null
                }
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = textModifier,
                    )
                }
            }
        }
    }
}

/**
 * Pede foco depois que o item entrou na LazyColumn: logo após uma mudança de estado o alvo ainda
 * não foi composto e o requestFocus falha.
 */
private suspend fun requestFocusAfterLayout(requester: FocusRequester) {
    repeat(5) {
        withFrameNanos { }
        // Conforme a versão do Compose, um alvo ainda não composto lança exceção ou devolve false.
        val outcome = runCatching { requester.requestFocus() as Any? }
        if (outcome.isSuccess && outcome.getOrNull() != false) return
    }
}

private class AiVoiceInput(
    val listening: Boolean,
    val start: () -> Unit,
)

/** Reconhecimento de voz como na busca: SpeechRecognizer direto, com pedido de microfone. */
@Composable
private fun rememberAiVoiceInput(onResult: (String) -> Unit): AiVoiceInput {
    val context = LocalContext.current
    val failedMessage = stringResource(R.string.ai_assistant_voice_failed)
    val permissionMessage = stringResource(R.string.ai_assistant_voice_permission)
    val onResultState = rememberUpdatedState(onResult)
    var listening by remember { mutableStateOf(false) }
    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    fun startListening() {
        val active = recognizer ?: run {
            Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
            return
        }
        // Uma escuta nova substitui a anterior (o reconhecedor recusa duas ao mesmo tempo).
        active.cancel()
        active.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
            }

            override fun onError(error: Int) {
                listening = false
                if (error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY && error != SpeechRecognizer.ERROR_CLIENT) {
                    Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (text.isNullOrEmpty()) {
                    Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
                } else {
                    onResultState.value(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        active.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            },
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startListening()
        } else {
            Toast.makeText(context, permissionMessage, Toast.LENGTH_SHORT).show()
        }
    }

    return AiVoiceInput(
        listening = listening,
        start = {
            if (listening) {
                // Segundo aperto durante a escuta: cancela.
                recognizer?.cancel()
                listening = false
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (hasPermission) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}
