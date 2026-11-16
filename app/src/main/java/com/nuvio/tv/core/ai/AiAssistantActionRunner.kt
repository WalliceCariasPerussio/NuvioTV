package com.nuvio.tv.core.ai

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.buildCompletedMovieProgress
import com.nuvio.tv.ui.components.posteroptions.toLibraryEntryInput
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

internal data class AiActionOutcome(
    val status: AiAssistantActionStatus,
    val episodeCount: Int? = null,
    val error: String? = null,
)

/**
 * Executa as ações aprovadas no cartão do assistente, pelos mesmos caminhos do menu do cartaz
 * (PosterOptionsController). Nada aqui desfaz estado: título já visto ou já salvo fica como está,
 * porque os botões do cartaz alternam e "marca os 3" desmarcaria o que já estava.
 */
@Singleton
class AiAssistantActionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val tmdbService: TmdbService,
) {
    internal suspend fun run(action: AiAssistantAction): AiActionOutcome {
        val target = action.target ?: return AiActionOutcome(AiAssistantActionStatus.NotFound)
        return try {
            val canonical = canonicalize(target)
            when (action.kind) {
                AiAssistantActionKind.MarkWatched -> markWatched(canonical, target)
                AiAssistantActionKind.AddToLibrary -> addToLibrary(canonical, target)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "${action.kind} falhou para ${target.apiType} ${target.id}", error)
            AiActionOutcome(
                status = AiAssistantActionStatus.Failed,
                error = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
            )
        }
    }

    private suspend fun markWatched(item: MetaPreview, original: MetaPreview): AiActionOutcome {
        if (!item.isSeriesLike()) {
            val alreadyWatched = watchProgressRepository.isWatched(item.id, videoId = item.imdbId).first() ||
                watchProgressRepository.isWatched(original.id, videoId = original.imdbId).first()
            if (alreadyWatched) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
            watchProgressRepository.markAsCompleted(buildCompletedMovieProgress(item))
            return AiActionOutcome(AiAssistantActionStatus.Done)
        }

        val fullyWatched = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        if (item.id in fullyWatched || original.id in fullyWatched) {
            return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
        }
        val episodes = fetchSeriesEpisodes(item)
            .filter { it.season != null && it.episode != null && it.season != 0 }
        // Sem episódios sobraria só a marca da série, que os rastreadores tratam como "todos vistos".
        if (episodes.isEmpty()) {
            return AiActionOutcome(
                status = AiAssistantActionStatus.Failed,
                error = context.getString(R.string.ai_assistant_action_error_no_episodes),
            )
        }
        val now = System.currentTimeMillis()
        watchProgressRepository.markAsCompletedBatch(
            episodes.map { video ->
                WatchProgress(
                    contentId = item.id,
                    contentType = item.apiType,
                    name = item.name,
                    poster = item.poster,
                    backdrop = item.backdropUrl,
                    logo = item.logo,
                    videoId = video.id,
                    season = video.season,
                    episode = video.episode,
                    episodeTitle = video.title,
                    position = 1L,
                    duration = 1L,
                    lastWatched = now,
                    progressPercent = 100f,
                )
            },
        )
        watchedSeriesStateHolder.update(watchedSeriesStateHolder.fullyWatchedSeriesIds.value + item.id)
        return AiActionOutcome(AiAssistantActionStatus.Done, episodeCount = episodes.size)
    }

    private suspend fun addToLibrary(item: MetaPreview, original: MetaPreview): AiActionOutcome {
        val alreadySaved = libraryRepository.isInLibrary(item.id, item.apiType).first() ||
            libraryRepository.isInLibrary(original.id, original.apiType).first()
        if (alreadySaved) return AiActionOutcome(AiAssistantActionStatus.AlreadyDone)
        libraryRepository.toggleDefault(item.toLibraryEntryInput(addonBaseUrl = null))
        return AiActionOutcome(AiAssistantActionStatus.Done)
    }

    /** Cartaz do assistente vem como `tmdb:<id>`; os vistos e a biblioteca guardam o id do IMDb. */
    private suspend fun canonicalize(item: MetaPreview): MetaPreview {
        if (item.id.startsWith("tt")) return item
        val tmdbNumber = parseContentIds(item.id).tmdb ?: item.id.toIntOrNull() ?: return item
        val mediaType = if (item.isSeriesLike()) "tv" else "movie"
        val imdb = runCatching { tmdbService.tmdbToImdb(tmdbNumber, mediaType) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        return if (!imdb.isNullOrBlank()) item.copy(id = imdb, imdbId = imdb) else item
    }

    private suspend fun fetchSeriesEpisodes(item: MetaPreview): List<Video> {
        var episodes: List<Video> = emptyList()
        metaRepository.getMetaFromPrimaryAddon("series", item.id).collect { result ->
            if (result is NetworkResult.Success) episodes = result.data.videos
        }
        return episodes
    }

    private fun MetaPreview.isSeriesLike(): Boolean =
        apiType.equals("series", ignoreCase = true) ||
            apiType.equals("tv", ignoreCase = true) ||
            apiType.equals("anime", ignoreCase = true)

    private companion object {
        const val TAG = "AiAssistantActions"
    }
}
