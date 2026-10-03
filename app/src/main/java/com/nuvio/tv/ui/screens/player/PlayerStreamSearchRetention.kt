package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.StreamSearchTarget

// While the player is open it keeps the stream searches of the episode playing and of the next one
// in StreamSearchSessionCache, past its normal expiry. The "Fontes" panel and moving to the next
// episode then reuse them instead of searching again. Leaving the player releases them.

/** Same target the sources panel searches (loadSourceStreams). */
internal fun PlayerRuntimeController.currentStreamSearchTarget(): StreamSearchTarget? {
    val type = contentType
    val season = currentSeason
    val episode = currentEpisode
    return if (type in listOf("series", "tv") && season != null && episode != null) {
        StreamSearchTarget(type ?: return null, currentVideoId ?: contentId ?: return null, season, episode)
    } else {
        StreamSearchTarget(type ?: "movie", contentId ?: return null, null, null)
    }
}

/** Same target preloadNextEpisodeSources searches. */
internal fun PlayerRuntimeController.nextStreamSearchTarget(): StreamSearchTarget? {
    val nextVideo = nextEpisodeVideo ?: return null
    val type = contentType ?: return null
    if (_uiState.value.nextEpisode?.hasAired != true) return null
    return StreamSearchTarget(type, nextVideo.id, nextVideo.season, nextVideo.episode)
}

internal fun PlayerRuntimeController.retainPlayerStreamSearches() {
    streamRepository.retainStreamSearches(setOfNotNull(currentStreamSearchTarget(), nextStreamSearchTarget()))
}

internal fun PlayerRuntimeController.releasePlayerStreamSearches() {
    streamRepository.retainStreamSearches(emptySet())
}

/** A failed stream may come from a stale search, so the sources panel searches again. */
internal fun PlayerRuntimeController.isPlaybackFailed(): Boolean = !_uiState.value.error.isNullOrBlank()
