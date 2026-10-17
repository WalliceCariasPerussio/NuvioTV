package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.library.LIBRARY_SNAPSHOT_PAGE_SIZE
import com.nuvio.tv.core.sync.library.LibrarySyncRemoteDataSource
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryMovieAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistorySeasonAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryShowAddDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListMovieRequestItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListShowRequestItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.remote.supabase.SupabaseWatchProgress
import com.nuvio.tv.data.remote.supabase.SupabaseWatchedItem
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.data.repository.TraktEpisodeMappingService
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.parseIsoToMillis
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NuvioToTraktSync"
private const val WATCHED_ITEMS_PAGE_SIZE = 900
private const val TRAKT_WATCHED_PAGE_LIMIT = 250
private const val TRAKT_MAX_PAGES = 1_000
private const val SEND_CHUNK = 100
private const val IN_PROGRESS_WINDOW_MS = 60L * 24L * 60L * 60L * 1_000L
private const val RECENT_PAUSE_MS = 15L * 60L * 1_000L
private const val RECENT_HISTORY_MS = 12L * 60L * 60L * 1_000L

sealed interface NuvioToTraktUiState {
    data object Idle : NuvioToTraktUiState
    data object Analyzing : NuvioToTraktUiState
    data class Ready(
        val profileId: Int,
        val plan: NuvioToTraktPlan,
        val titles: Map<String, String>
    ) : NuvioToTraktUiState
    data object Sending : NuvioToTraktUiState
    data class Done(
        val historySent: Int,
        val historyConfirmed: Int,
        val inProgressSent: Int,
        val librarySent: Int,
        val skippedUnsupportedIds: Int
    ) : NuvioToTraktUiState
    data object NothingToSend : NuvioToTraktUiState
    data object Failed : NuvioToTraktUiState
}

/**
 * Brings what only the Nuvio cloud has into Trakt, so a profile that reads Trakt sees what was watched on
 * devices not connected to it, without switching sources. Analysis and sending are separate steps: Trakt
 * history adds are not idempotent, so the user confirms the exact counts first.
 */
@Singleton
class NuvioToTraktSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val postgrest: Postgrest,
    private val libraryRemote: LibrarySyncRemoteDataSource,
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktLibraryService: TraktLibraryService,
    private val episodeMappingService: TraktEpisodeMappingService,
    private val syncNowService: SyncNowService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var job: Job? = null

    private data class SentPause(val percent: Double, val sentAtMs: Long)

    // Keyed by "profileId|item"; only kept for this app session.
    private val sentPauses = mutableMapOf<String, SentPause>()
    private val sentHistoryAt = mutableMapOf<String, Long>()

    private val _uiState = MutableStateFlow<NuvioToTraktUiState>(NuvioToTraktUiState.Idle)
    val uiState: StateFlow<NuvioToTraktUiState> = _uiState.asStateFlow()

    fun analyze() {
        val profileId = profileManager.activeProfileId.value
        launchExclusive(NuvioToTraktUiState.Analyzing) {
            val (plan, traktTitles) = buildPlan(profileId)
            Log.i(
                TAG,
                "plan profile=$profileId history=${plan.history.size} inProgress=${plan.inProgress.size} " +
                    "library=${plan.library.size} skippedUnsupported=${plan.skippedUnsupportedIds}"
            )
            if (plan.isEmpty) {
                NuvioToTraktUiState.NothingToSend
            } else {
                val titles = plan.history
                    .filter { item -> item.title.isNotBlank() && item.title != item.contentId }
                    .associate { item -> item.contentId to item.title } + traktTitles
                NuvioToTraktUiState.Ready(profileId = profileId, plan = plan, titles = titles)
            }
        }
    }

    fun send() {
        val ready = _uiState.value as? NuvioToTraktUiState.Ready ?: return
        launchExclusive(NuvioToTraktUiState.Sending) { sendPlan(ready.profileId, ready.plan) }
    }

    fun dismiss() {
        synchronized(lock) {
            if (job?.isActive == true) return
            _uiState.value = NuvioToTraktUiState.Idle
        }
    }

    private fun launchExclusive(
        runningState: NuvioToTraktUiState,
        block: suspend () -> NuvioToTraktUiState
    ) {
        synchronized(lock) {
            if (job?.isActive == true) return
            _uiState.value = runningState
            job = scope.launch {
                var finalState: NuvioToTraktUiState = NuvioToTraktUiState.Failed
                try {
                    finalState = block()
                } catch (error: CancellationException) {
                    finalState = NuvioToTraktUiState.Idle
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Nuvio to Trakt sync failed", error)
                } finally {
                    _uiState.value = finalState
                }
            }
        }
    }

    // ── Analysis ────────────────────────────────────────────────────────

    private suspend fun requireReady(profileId: Int) {
        check(authManager.authState.value is AuthState.FullAccount) { "A Nuvio account is required" }
        check(traktAuthService.getCurrentAuthState().isAuthenticated) { "Trakt must be connected" }
        check(profileManager.activeProfileId.value == profileId) { "The active profile changed" }
    }

    /** The plan, plus Trakt's titles for the ids it knows (Nuvio progress rows carry no title). */
    private suspend fun buildPlan(profileId: Int): Pair<NuvioToTraktPlan, Map<String, String>> {
        requireReady(profileId)
        val nuvioWatched = pullNuvioWatched(profileId)
        val nuvioProgress = pullNuvioProgress(profileId)
        val nuvioLibrary = libraryRemote.pullSnapshot(profileId, LIBRARY_SNAPSHOT_PAGE_SIZE)
        // A failed Trakt read must stop here: planning against an empty Trakt would resend the whole history.
        val traktWatched = fetchTraktWatched()
        val playback = fetchTraktPlayback()
        val latestActivity = traktWatched.latestByContentId.toMutableMap()
        playback.latestByContentId.forEach { (contentId, pausedAt) ->
            latestActivity[contentId] = maxOf(latestActivity[contentId] ?: Long.MIN_VALUE, pausedAt)
        }
        val recent = recentlySent(profileId)
        val pausedPercent = playback.percentByKey.toMutableMap()
        recent.pausedPercentByKey.forEach { (key, percent) ->
            pausedPercent[key] = maxOf(pausedPercent[key] ?: 0.0, percent)
        }
        traktLibraryService.refreshNow()
        val traktLibraryKeys = traktLibraryService.observeAllItems().first()
            .flatMapTo(linkedSetOf()) { entry ->
                libraryMatchKeys(entry.id, entry.type, entry.imdbId, entry.tmdbId, entry.traktId)
            }
        val plan = planNuvioToTraktSync(
            nuvioWatched = nuvioWatched,
            nuvioProgress = nuvioProgress,
            traktWatchedKeys = traktWatched.keys,
            traktLatestActivityByContentId = latestActivity,
            inProgressCutoffEpochMs = System.currentTimeMillis() - IN_PROGRESS_WINDOW_MS,
            traktPausedPercentByKey = pausedPercent,
            recentlySentHistory = recent.historyIdentities,
            nuvioLibrary = nuvioLibrary,
            traktLibraryKeys = traktLibraryKeys
        )
        return plan to (playback.titlesByContentId + traktWatched.titlesByContentId)
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (!authManager.refreshSessionIfJwtExpired(e)) throw e
        block()
    }

    /** Raw cloud read: unlike the app's own pull, it ignores the active watch source. */
    private suspend fun pullNuvioWatched(profileId: Int): List<WatchedItem> {
        val items = mutableListOf<WatchedItem>()
        var page = 1
        while (true) {
            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_page", page)
                put("p_page_size", WATCHED_ITEMS_PAGE_SIZE)
            }
            val remote = withJwtRefreshRetry { postgrest.rpc("sync_pull_watched_items", params) }
                .decodeList<SupabaseWatchedItem>()
            items += remote.map { entry ->
                WatchedItem(
                    contentId = entry.contentId,
                    contentType = entry.contentType,
                    title = entry.title,
                    season = entry.season,
                    episode = entry.episode,
                    watchedAt = entry.watchedAt
                )
            }
            if (remote.size < WATCHED_ITEMS_PAGE_SIZE) break
            page++
        }
        return items
    }

    private suspend fun pullNuvioProgress(profileId: Int): List<CloudProgressRecord> {
        val params = buildJsonObject { put("p_profile_id", profileId) }
        return withJwtRefreshRetry { postgrest.rpc("sync_pull_watch_progress", params) }
            .decodeList<SupabaseWatchProgress>()
            .map { entry ->
                CloudProgressRecord(
                    contentId = entry.contentId,
                    contentType = entry.contentType,
                    videoId = entry.videoId,
                    season = entry.season,
                    episode = entry.episode,
                    position = entry.position,
                    duration = entry.duration,
                    lastWatched = entry.lastWatched
                )
            }
    }

    private class TraktWatchedSnapshot(
        val keys: Set<String>,
        val latestByContentId: Map<String, Long>,
        val titlesByContentId: Map<String, String>
    )

    private class TraktPlaybackSnapshot(
        val latestByContentId: Map<String, Long>,
        val percentByKey: Map<String, Double>,
        val titlesByContentId: Map<String, String>
    )

    private fun traktIdForms(ids: TraktIdsDto?): List<String> = listOfNotNull(
        ids?.imdb?.trim()?.takeIf(String::isNotBlank),
        ids?.tmdb?.let { "tmdb:$it" },
        ids?.trakt?.let { "trakt:$it" }
    )

    private fun <T> Response<T>?.bodyOrThrow(what: String): T {
        if (this == null) throw IOException("Trakt $what request was skipped")
        if (!isSuccessful) throw IOException("Trakt $what failed (${code()})")
        return body() ?: throw IOException("Trakt $what returned no body")
    }

    private suspend fun fetchTraktWatched(): TraktWatchedSnapshot {
        val keys = linkedSetOf<String>()
        val latest = mutableMapOf<String, Long>()
        val titles = mutableMapOf<String, String>()

        fun note(forms: List<String>, title: String?, watchedAt: String?) {
            val at = watchedAt?.let(::parseIsoToMillis)
            forms.forEach { form ->
                if (at != null) latest[form] = maxOf(latest[form] ?: Long.MIN_VALUE, at)
                if (!title.isNullOrBlank()) titles[form] = title
            }
        }

        var page = 1
        while (page <= TRAKT_MAX_PAGES) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatched(authorization = authHeader, type = "movies", page = page, limit = TRAKT_WATCHED_PAGE_LIMIT)
            }
            val items = response.bodyOrThrow("watched movies")
            if (items.isEmpty()) break
            items.forEach { item ->
                val forms = traktIdForms(item.movie?.ids)
                forms.forEach { form -> keys += watchedKey("movie", form, null, null) }
                note(forms, item.movie?.title, item.lastWatchedAt)
            }
            val pageCount = response!!.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (pageCount != null && page >= pageCount) break
            page++
        }

        page = 1
        while (page <= TRAKT_MAX_PAGES) {
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getWatchedShows(authorization = authHeader, page = page, limit = TRAKT_WATCHED_PAGE_LIMIT)
            }
            val items = response.bodyOrThrow("watched shows")
            if (items.isEmpty()) break
            items.forEach { item ->
                val forms = traktIdForms(item.show?.ids)
                item.seasons.orEmpty().forEach { season ->
                    val seasonNumber = season.number ?: return@forEach
                    season.episodes.orEmpty().forEach episodes@{ episode ->
                        val episodeNumber = episode.number ?: return@episodes
                        if ((episode.plays ?: 1) <= 0) return@episodes
                        forms.forEach { form -> keys += watchedKey("series", form, seasonNumber, episodeNumber) }
                    }
                }
                note(forms, item.show?.title, item.lastWatchedAt)
            }
            val pageCount = response!!.headers()["X-Pagination-Page-Count"]?.toIntOrNull()
            if (pageCount != null && page >= pageCount) break
            page++
        }
        return TraktWatchedSnapshot(keys = keys, latestByContentId = latest, titlesByContentId = titles)
    }

    /** Trakt's paused items, keyed under every id form Trakt reports for the title. */
    private suspend fun fetchTraktPlayback(): TraktPlaybackSnapshot {
        val latest = mutableMapOf<String, Long>()
        val percent = mutableMapOf<String, Double>()
        val titles = mutableMapOf<String, String>()
        listOf("movies", "episodes").forEach { type ->
            val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                traktApi.getPlayback(authorization = authHeader, type = type)
            }
            response.bodyOrThrow("playback $type").forEach { item ->
                val forms = traktIdForms(item.show?.ids ?: item.movie?.ids)
                val title = (item.show?.title ?: item.movie?.title)?.takeIf(String::isNotBlank)
                val pausedAt = item.pausedAt?.let(::parseIsoToMillis)
                forms.forEach { form ->
                    if (pausedAt != null) latest[form] = maxOf(latest[form] ?: Long.MIN_VALUE, pausedAt)
                    item.progress?.let { percent[pausedProgressKey(form, item.episode?.season, item.episode?.number)] = it.toDouble() }
                    if (title != null) titles[form] = title
                }
            }
        }
        return TraktPlaybackSnapshot(latestByContentId = latest, percentByKey = percent, titlesByContentId = titles)
    }

    // ── Sending ─────────────────────────────────────────────────────────

    private suspend fun sendPlan(profileId: Int, plan: NuvioToTraktPlan): NuvioToTraktUiState {
        requireReady(profileId)
        // Adds are sent in small batches; a failed batch stops here and a re-run recomputes what is left.
        plan.history.chunked(SEND_CHUNK).forEach { chunk ->
            val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
                traktApi.addHistory(authHeader, historyRequest(chunk))
            }
            if (response?.isSuccessful != true) throw IOException("Trakt history add failed (${response?.code()})")
            val sentAt = System.currentTimeMillis()
            synchronized(lock) { chunk.forEach { item -> sentHistoryAt["$profileId|${historyIdentity(item)}"] = sentAt } }
        }

        var inProgressSent = 0
        plan.inProgress.forEach { progress ->
            if (sendPause(profileId, progress)) inProgressSent++
        }

        var libraryAdded = 0
        plan.library.chunked(SEND_CHUNK).forEach { chunk ->
            libraryAdded += addToWatchlist(chunk)
        }

        // Trakt answers adds without per-item detail; re-reading its history shows what actually landed.
        val confirmedKeys = fetchTraktWatched().keys
        val historyConfirmed = plan.history.count { item ->
            watchedKey(item.contentType, item.contentId, item.season, item.episode) in confirmedKeys
        }
        Log.i(
            TAG,
            "sent profile=$profileId history=${plan.history.size} confirmed=$historyConfirmed " +
                "inProgress=$inProgressSent/${plan.inProgress.size} library=$libraryAdded/${plan.library.size}"
        )
        syncNowService.syncNow()
        return NuvioToTraktUiState.Done(
            historySent = plan.history.size,
            historyConfirmed = historyConfirmed,
            inProgressSent = inProgressSent,
            librarySent = libraryAdded,
            skippedUnsupportedIds = plan.skippedUnsupportedIds
        )
    }

    private fun idsOf(contentId: String): TraktIdsDto {
        val parsed = parseContentIds(contentId)
        return TraktIdsDto(imdb = parsed.imdb, tmdb = parsed.tmdb, trakt = parsed.trakt)
    }

    private fun historyRequest(items: List<WatchedItem>): TraktHistoryAddRequestDto {
        val (movieItems, episodeItems) = items.partition { item -> isMovieType(item.contentType) }
        val shows = episodeItems.groupBy { item -> item.contentId }.map { (contentId, episodes) ->
            TraktHistoryShowAddDto(
                title = episodes.first().title.takeIf { it.isNotBlank() && it != contentId },
                ids = idsOf(contentId),
                seasons = episodes.groupBy { item -> item.season }.mapNotNull { (season, inSeason) ->
                    season ?: return@mapNotNull null
                    TraktHistorySeasonAddDto(
                        number = season,
                        episodes = inSeason.mapNotNull { item ->
                            item.episode?.let { number ->
                                TraktHistoryEpisodeAddDto(number = number, watchedAt = Instant.ofEpochMilli(item.watchedAt).toString())
                            }
                        }
                    )
                }
            )
        }
        return TraktHistoryAddRequestDto(
            movies = movieItems.map { item ->
                TraktHistoryMovieAddDto(
                    title = item.title.takeIf { it.isNotBlank() && it != item.contentId },
                    ids = idsOf(item.contentId),
                    watchedAt = Instant.ofEpochMilli(item.watchedAt).toString()
                )
            }.takeIf { it.isNotEmpty() },
            shows = shows.takeIf { it.isNotEmpty() }
        )
    }

    /** Resumes one title on Trakt with a paused scrobble; true when Trakt took it. */
    private suspend fun sendPause(profileId: Int, progress: NuvioToTraktProgress): Boolean {
        val record = progress.record
        val ids = idsOf(record.contentId)
        val percent = progress.progressPercent.toFloat()
        val body = if (isMovieType(record.contentType)) {
            TraktScrobbleRequestDto(
                movie = TraktMovieDto(ids = ids),
                progress = percent,
                appVersion = BuildConfig.VERSION_NAME
            )
        } else {
            val season = record.season ?: return false
            val episode = record.episode ?: return false
            val mapped = episodeMappingService.resolveEpisodeMapping(
                contentId = record.contentId,
                contentType = record.contentType,
                videoId = record.videoId,
                season = season,
                episode = episode
            )
            TraktScrobbleRequestDto(
                show = TraktShowDto(ids = ids),
                episode = TraktEpisodeDto(season = mapped?.season ?: season, number = mapped?.episode ?: episode),
                progress = percent,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.scrobblePause(authHeader, body)
        }
        val accepted = response != null && (response.isSuccessful || response.code() == 409)
        if (!accepted) {
            // A title Trakt does not know answers 404; it is skipped, and offered again on the next comparison.
            Log.w(TAG, "pause not accepted for ${record.contentId} code=${response?.code()}")
            return false
        }
        synchronized(lock) {
            sentPauses["$profileId|${pausedProgressKey(record.contentId, record.season, record.episode)}"] =
                SentPause(percent = progress.progressPercent, sentAtMs = System.currentTimeMillis())
        }
        return true
    }

    /** Adds titles to the Trakt watchlist; returns how many Trakt now holds there (added or already present). */
    private suspend fun addToWatchlist(items: List<SavedLibraryItem>): Int {
        val (movies, shows) = items.partition { item -> isMovieType(item.type) }
        val body = TraktListItemsMutationRequestDto(
            movies = movies.map { item -> TraktListMovieRequestItemDto(ids = idsOf(normalizeTraktComparableId(item.id))) }
                .takeIf { it.isNotEmpty() },
            shows = shows.map { item -> TraktListShowRequestItemDto(ids = idsOf(normalizeTraktComparableId(item.id))) }
                .takeIf { it.isNotEmpty() }
        )
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addToWatchlist(authHeader, body)
        }
        if (response?.isSuccessful != true) {
            Log.w(TAG, "watchlist add failed code=${response?.code()}")
            return 0
        }
        val result = response.body()
        return listOfNotNull(result?.added, result?.existing).sumOf { counts -> (counts.movies ?: 0) + (counts.shows ?: 0) }
    }

    private class RecentlySent(val pausedPercentByKey: Map<String, Double>, val historyIdentities: Set<String>)

    private fun recentlySent(profileId: Int): RecentlySent {
        val prefix = "$profileId|"
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            sentPauses.entries.removeAll { (_, pause) -> now - pause.sentAtMs > RECENT_PAUSE_MS }
            sentHistoryAt.entries.removeAll { (_, sentAt) -> now - sentAt > RECENT_HISTORY_MS }
            RecentlySent(
                pausedPercentByKey = sentPauses
                    .filterKeys { key -> key.startsWith(prefix) }
                    .entries
                    .associate { (key, pause) -> key.removePrefix(prefix) to pause.percent },
                historyIdentities = sentHistoryAt.keys
                    .filter { key -> key.startsWith(prefix) }
                    .mapTo(linkedSetOf()) { key -> key.removePrefix(prefix) }
            )
        }
    }
}
