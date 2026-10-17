@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.NuvioToTraktSyncService
import com.nuvio.tv.core.sync.NuvioToTraktUiState
import com.nuvio.tv.core.sync.SyncNowService
import com.nuvio.tv.core.sync.SyncNowStep
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

private const val ELAPSED_TICK_MS = 30_000L
private const val PREVIEW_LINES = 8

@HiltViewModel
class TrackingSyncViewModel @Inject constructor(
    private val syncNow: SyncNowService,
    private val nuvioToTrakt: NuvioToTraktSyncService,
    authManager: AuthManager,
    traktAuthDataStore: TraktAuthDataStore
) : ViewModel() {
    val syncNowState = syncNow.uiState
    val nuvioToTraktState = nuvioToTrakt.uiState

    /** The Nuvio cloud and Trakt both need an account for the send to make sense. */
    val canSendToTrakt: StateFlow<Boolean> = combine(authManager.authState, traktAuthDataStore.state) { auth, trakt ->
        auth is AuthState.FullAccount && trakt.isAuthenticated
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun syncNow() = syncNow.syncNow()
    fun analyzeNuvioToTrakt() = nuvioToTrakt.analyze()
    fun sendNuvioToTrakt() = nuvioToTrakt.send()
    fun dismissNuvioToTrakt() = nuvioToTrakt.dismiss()
}

/** The "Sync" group at the end of the Tracking settings. */
@Composable
internal fun TrackingSyncGroup(viewModel: TrackingSyncViewModel = hiltViewModel()) {
    val syncState by viewModel.syncNowState.collectAsStateWithLifecycle()
    val toTraktState by viewModel.nuvioToTraktState.collectAsStateWithLifecycle()
    val canSendToTrakt by viewModel.canSendToTrakt.collectAsStateWithLifecycle()

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(syncState.finishedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(ELAPSED_TICK_MS)
        }
    }

    val finishedAtMs = syncState.finishedAtMs
    val failedLabels = syncState.failedSteps.map { step -> stepLabel(step) }
    val syncStatus = when {
        syncState.isRunning -> stringResource(R.string.tracking_sync_now_running)
        finishedAtMs != null && syncState.failedSteps.isEmpty() ->
            stringResource(R.string.tracking_sync_now_done, elapsedLabel(nowMs - finishedAtMs))
        finishedAtMs != null -> stringResource(R.string.tracking_sync_now_failed, failedLabels.joinToString(", "))
        else -> null
    }
    val toTraktBusy = toTraktState is NuvioToTraktUiState.Analyzing || toTraktState is NuvioToTraktUiState.Sending
    val toTraktStatus = when (val state = toTraktState) {
        NuvioToTraktUiState.Analyzing -> stringResource(R.string.tracking_nuvio_to_trakt_analyzing)
        NuvioToTraktUiState.Sending -> stringResource(R.string.tracking_nuvio_to_trakt_sending)
        NuvioToTraktUiState.NothingToSend -> stringResource(R.string.tracking_nuvio_to_trakt_nothing)
        NuvioToTraktUiState.Failed -> stringResource(R.string.tracking_nuvio_to_trakt_failed)
        is NuvioToTraktUiState.Done -> stringResource(
            R.string.tracking_nuvio_to_trakt_done,
            state.historySent,
            state.historyConfirmed,
            state.inProgressSent,
            state.librarySent
        )
        else -> null
    }

    SettingsGroupCard(
        title = stringResource(R.string.tracking_sync_section_title),
        subtitle = stringResource(R.string.tracking_sync_section_subtitle)
    ) {
        SettingsActionRow(
            title = stringResource(R.string.tracking_sync_now_title),
            subtitle = listOfNotNull(stringResource(R.string.tracking_sync_now_description), syncStatus).joinToString("\n"),
            enabled = !syncState.isRunning,
            onClick = viewModel::syncNow
        )
        if (canSendToTrakt) {
            SettingsActionRow(
                title = stringResource(R.string.tracking_nuvio_to_trakt_title),
                subtitle = listOfNotNull(stringResource(R.string.tracking_nuvio_to_trakt_description), toTraktStatus)
                    .joinToString("\n"),
                enabled = !toTraktBusy,
                onClick = viewModel::analyzeNuvioToTrakt
            )
        }
    }

    (toTraktState as? NuvioToTraktUiState.Ready)?.let { ready ->
        NuvioToTraktConfirmDialog(
            ready = ready,
            onConfirm = viewModel::sendNuvioToTrakt,
            onDismiss = viewModel::dismissNuvioToTrakt
        )
    }
}

@Composable
private fun NuvioToTraktConfirmDialog(
    ready: NuvioToTraktUiState.Ready,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val plan = ready.plan
    // Newest first: what was just watched elsewhere is what the user is looking for.
    val watching = (
        plan.inProgress.map { progress ->
            Triple(progress.record.contentId, progress.record.season, progress.record.episode) to progress.record.lastWatched
        } + plan.history.map { item ->
            Triple(item.contentId, item.season, item.episode) to item.watchedAt
        }
        )
        .sortedByDescending { (_, at) -> at }
        .map { (identity, _) -> identity }
    val watchingLines = watching.map { (contentId, season, episode) ->
        val title = ready.titles[contentId] ?: contentId
        if (season != null && episode != null) {
            stringResource(R.string.tracking_nuvio_to_trakt_episode, title, season, episode)
        } else {
            title
        }
    }
    val allLines = watchingLines + plan.library.map { item -> item.name.ifBlank { item.id } }
    val previewLines = allLines.take(PREVIEW_LINES)
    val hidden = allLines.size - previewLines.size
    val moreLabel = if (hidden > 0) stringResource(R.string.tracking_nuvio_to_trakt_dialog_more, hidden) else null

    val summary = listOfNotNull(
        plan.history.size.takeIf { it > 0 }?.let { stringResource(R.string.tracking_nuvio_to_trakt_dialog_history, it) },
        plan.inProgress.size.takeIf { it > 0 }?.let { stringResource(R.string.tracking_nuvio_to_trakt_dialog_progress, it) },
        plan.library.size.takeIf { it > 0 }?.let { stringResource(R.string.tracking_nuvio_to_trakt_dialog_library, it) }
    )

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.tracking_nuvio_to_trakt_dialog_title),
        subtitle = summary.joinToString("\n"),
        width = 720.dp,
        suppressFirstKeyUp = false
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = (previewLines.map { line -> "• $line" } + listOfNotNull(moreLabel)).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            if (plan.skippedUnsupportedIds > 0) {
                Text(
                    text = stringResource(R.string.tracking_nuvio_to_trakt_dialog_skipped, plan.skippedUnsupportedIds),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss
            )
            SettingsDialogActionButton(
                text = stringResource(R.string.tracking_nuvio_to_trakt_send),
                onClick = onConfirm,
                primary = true
            )
        }
    }
}

@Composable
private fun elapsedLabel(elapsedMs: Long): String {
    val minutes = (elapsedMs / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> stringResource(R.string.tracking_sync_now_just_now)
        minutes < 60L -> stringResource(R.string.tracking_sync_now_minutes_ago, minutes.toInt())
        else -> stringResource(R.string.tracking_sync_now_hours_ago, (minutes / 60L).toInt())
    }
}

@Composable
private fun stepLabel(step: SyncNowStep): String = stringResource(
    when (step) {
        SyncNowStep.PENDING_UPLOADS -> R.string.tracking_sync_now_step_uploads
        SyncNowStep.NUVIO_PULL -> R.string.tracking_sync_now_step_nuvio
        SyncNowStep.TRACKING_REFRESH -> R.string.tracking_sync_now_step_tracking
    }
)
