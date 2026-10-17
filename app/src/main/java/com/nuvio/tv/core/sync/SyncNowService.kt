package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProgressProviderRegistry
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncNowService"

enum class SyncNowStep {
    PENDING_UPLOADS,
    NUVIO_PULL,
    TRACKING_REFRESH
}

data class SyncNowUiState(
    val isRunning: Boolean = false,
    val finishedAtMs: Long? = null,
    val failedSteps: Set<SyncNowStep> = emptySet()
)

/**
 * Manual "Sync now", the last resort when Continue Watching or the history looks out of date. Sends what
 * never reached the Nuvio cloud, pulls everything again from it, asks the connected tracking services to
 * reload, and drops the Continue Watching cache so Home rebuilds it from the fresh data.
 */
@Singleton
class SyncNowService @Inject constructor(
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val startupSyncService: StartupSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val librarySyncService: LibrarySyncService,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
    private val traktProgressService: TraktProgressService,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _uiState = MutableStateFlow(SyncNowUiState())
    val uiState: StateFlow<SyncNowUiState> = _uiState.asStateFlow()

    fun syncNow() {
        synchronized(this) {
            if (job?.isActive == true) return
            _uiState.value = _uiState.value.copy(isRunning = true)
            job = scope.launch {
                var failed: Set<SyncNowStep>? = null
                try {
                    failed = run()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Sync now failed", error)
                    failed = SyncNowStep.values().toSet()
                } finally {
                    _uiState.value = SyncNowUiState(
                        isRunning = false,
                        finishedAtMs = failed?.let { System.currentTimeMillis() },
                        failedSteps = failed.orEmpty()
                    )
                }
            }
        }
    }

    internal suspend fun run(): Set<SyncNowStep> {
        val profileId = profileManager.activeProfileId.value
        val failed = linkedSetOf<SyncNowStep>()
        val signedIn = authManager.authState.value is AuthState.FullAccount
        val progressSource = traktSettingsDataStore.getWatchProgressSource(profileId)
        val libraryMode = traktSettingsDataStore.getLibrarySourceMode(profileId)
        Log.i(TAG, "Sync now started profile=$profileId signedIn=$signedIn source=$progressSource library=$libraryMode")

        if (signedIn) {
            // Rows whose push failed stay queued locally; sending them first keeps the pull below from burying them.
            if (!pushPendingUploads(profileId)) failed += SyncNowStep.PENDING_UPLOADS
            if (!startupSyncService.syncNowAndAwait()) failed += SyncNowStep.NUVIO_PULL
        }
        if (!refreshTracking(progressSource, libraryMode)) failed += SyncNowStep.TRACKING_REFRESH

        // The cache only needs rebuilding when Nuvio itself is the source and its pull finished. A tracking
        // service refreshes in the background and replaces the cache as soon as it answers, and a failed pull
        // (or no account) leaves the cache as the only thing Home has for posters and next-up episodes.
        if (signedIn && progressSource == WatchProgressSource.NUVIO_SYNC && failed.isEmpty()) {
            cwEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true)
            cwEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true)
        }
        Log.i(TAG, "Sync now finished profile=$profileId failedSteps=$failed")
        return failed
    }

    private suspend fun pushPendingUploads(profileId: Int): Boolean {
        // With a tracking service as the source, Nuvio holds no pending watch rows worth sending.
        var succeeded = true
        if (watchProgressSyncService.shouldUseSupabaseWatchProgressSync(profileId)) {
            succeeded = watchProgressSyncService.pushToRemote(profileId).isSuccess && succeeded
            succeeded = watchedItemsSyncService.pushToRemote(profileId).isSuccess && succeeded
        }
        succeeded = librarySyncService.pushToRemote(profileId).isSuccess && succeeded
        return succeeded
    }

    private suspend fun refreshTracking(
        progressSource: WatchProgressSource,
        libraryMode: LibrarySourceMode
    ): Boolean {
        var succeeded = true
        val progressProviderId = progressSource.providerId
        if (progressProviderId == TrackingProviderId.TRAKT) {
            // Resets Trakt's activity fingerprints and caches, so the reload cannot be skipped as "nothing changed".
            runCatching { traktProgressService.invalidateAndRefresh() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    succeeded = false
                }
        } else if (progressProviderId != null) {
            runCatching { progressProviders.provider(progressProviderId)?.refresh(TrackingRefreshIntent.USER_INITIATED) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    succeeded = false
                }
        }
        libraryMode.providerId?.let(libraryProviders::provider)?.let { provider ->
            runCatching { provider.refresh(TrackingRefreshIntent.USER_INITIATED) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    succeeded = false
                }
        }
        return succeeded
    }
}
