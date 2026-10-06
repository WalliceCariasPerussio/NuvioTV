package com.nuvio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.stream.languageCounts
import com.nuvio.tv.ui.screens.stream.languageKeysByCount
import com.nuvio.tv.ui.screens.stream.matchingLanguageKey
import com.nuvio.tv.ui.screens.stream.preferredAudioTargets
import com.nuvio.tv.ui.screens.stream.streamLanguageLabel
import com.nuvio.tv.ui.screens.stream.streamTraits
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Starts the stream search as soon as the details screen opens. StreamRepository keeps searches
// in StreamSearchSessionCache, so the stream screen joins this same search instead of starting
// another — as long as the request mirrors the hero play click (heroPlayClick in MetaDetailsScreen).

private const val PrefetchDebounceMs = 500L

internal data class DetailStreamPrefetchRequest(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val originalLanguage: String?,
)

/** Same target as the hero play button, or null when it would not open the stream screen. */
internal fun detailStreamPrefetchRequest(meta: Meta, heroVideo: Video?, canPlay: Boolean): DetailStreamPrefetchRequest? {
    if (!canPlay) return null
    return DetailStreamPrefetchRequest(
        type = meta.apiType,
        videoId = heroVideo?.id ?: meta.id,
        season = heroVideo?.season,
        episode = heroVideo?.episode,
        originalLanguage = meta.resolveContentLanguage(),
    )
}

internal sealed interface DetailStreamPrefetchStatus {
    data object Idle : DetailStreamPrefetchStatus
    data class Loading(val found: Int) : DetailStreamPrefetchStatus
    /** [byLanguage] holds the primary and secondary preferred languages: (language key, count). */
    data class Found(val total: Int, val byLanguage: List<Pair<String, Int>>) : DetailStreamPrefetchStatus
    data object NoneFound : DetailStreamPrefetchStatus
    data object Failed : DetailStreamPrefetchStatus
}

@HiltViewModel
class DetailStreamPrefetchViewModel @Inject constructor(
    private val streamRepository: StreamRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
) : ViewModel() {
    private val _status = MutableStateFlow<DetailStreamPrefetchStatus>(DetailStreamPrefetchStatus.Idle)
    internal val status: StateFlow<DetailStreamPrefetchStatus> = _status.asStateFlow()

    private var request: DetailStreamPrefetchRequest? = null
    private var job: Job? = null

    internal fun prefetch(request: DetailStreamPrefetchRequest?) {
        if (request == this.request) return
        this.request = request
        job?.cancel()
        if (request == null) {
            _status.value = DetailStreamPrefetchStatus.Idle
            return
        }
        _status.value = DetailStreamPrefetchStatus.Loading(found = 0)
        // Cancelling only stops this observer; the search itself lives in the repository cache.
        job = viewModelScope.launch {
            // Watch progress can still change the episode right after opening; wait it out.
            delay(PrefetchDebounceMs)
            var latest: List<AddonStreams> = emptyList()
            var error = false
            // The player pauses local plugins while it plays and they hold the search until resumed.
            // Out here nothing is playing, so they run (the player may have just left them paused).
            streamRepository.setLocalPluginSearchPaused(false)
            streamRepository.getStreamsFromAllAddons(
                type = request.type,
                videoId = request.videoId,
                season = request.season,
                episode = request.episode,
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        latest = result.data
                        _status.value = DetailStreamPrefetchStatus.Loading(latest.sumOf { it.streams.size })
                    }
                    is NetworkResult.Error -> error = true
                    NetworkResult.Loading -> Unit
                }
            }
            val streams = latest.flatMap { it.streams }
            _status.value = when {
                streams.isNotEmpty() -> {
                    val settings = playerSettingsDataStore.playerSettings.first()
                    val counts = languageCounts(streams.map { it.streamTraits() })
                    val keysByCount = languageKeysByCount(counts)
                    val byLanguage = preferredAudioTargets(settings, request.originalLanguage)
                        .take(2)
                        .map { target ->
                            val key = matchingLanguageKey(keysByCount, target)
                            (key ?: target) to (key?.let { counts[it] } ?: 0)
                        }
                        .distinctBy { it.first }
                    DetailStreamPrefetchStatus.Found(streams.size, byLanguage)
                }
                error -> DetailStreamPrefetchStatus.Failed
                else -> DetailStreamPrefetchStatus.NoneFound
            }
        }
    }
}

/** Line under the hero play button. Not focusable, so D-pad navigation is unchanged. */
@Composable
internal fun DetailStreamPrefetchStatusRow(status: DetailStreamPrefetchStatus, modifier: Modifier = Modifier) {
    if (status == DetailStreamPrefetchStatus.Idle) return
    val portuguese = stringResource(R.string.stream_filter_language_portuguese)
    val unknown = stringResource(R.string.stream_filter_language_unknown)
    val text = when (status) {
        DetailStreamPrefetchStatus.Idle -> return
        is DetailStreamPrefetchStatus.Loading -> if (status.found > 0) {
            pluralStringResource(R.plurals.detail_stream_prefetch_loading_found, status.found, status.found)
        } else {
            stringResource(R.string.detail_stream_prefetch_loading)
        }
        is DetailStreamPrefetchStatus.Found -> {
            val found = pluralStringResource(R.plurals.detail_stream_prefetch_found, status.total, status.total)
            val byLanguage = status.byLanguage.map { (key, count) ->
                val label = streamLanguageLabel(key, portuguese, unknown)
                if (count > 0) {
                    stringResource(R.string.detail_stream_prefetch_in_language, count, label)
                } else {
                    stringResource(R.string.detail_stream_prefetch_none_in_language, label)
                }
            }
            (listOf(found) + byLanguage).joinToString(" · ")
        }
        DetailStreamPrefetchStatus.NoneFound -> stringResource(R.string.detail_stream_prefetch_none)
        DetailStreamPrefetchStatus.Failed -> stringResource(R.string.detail_stream_prefetch_failed)
    }
    val icon = when (status) {
        is DetailStreamPrefetchStatus.Found -> Icons.Default.CheckCircle
        DetailStreamPrefetchStatus.Failed -> Icons.Default.ErrorOutline
        DetailStreamPrefetchStatus.NoneFound -> Icons.Default.Info
        else -> Icons.Default.Search
    }
    Row(
        modifier = modifier.padding(top = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = NuvioTheme.extendedColors.textSecondary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
