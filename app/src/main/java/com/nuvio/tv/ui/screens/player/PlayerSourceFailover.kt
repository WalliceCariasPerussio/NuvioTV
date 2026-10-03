package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.ui.screens.stream.SourceRanking
import com.nuvio.tv.ui.screens.stream.autoPlayAudioLanguages
import com.nuvio.tv.ui.screens.stream.bucket
import com.nuvio.tv.ui.screens.stream.rankSourceStreams
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Fork: Netflix-like failover. In the "best in my language" autoplay mode, when the source playing
// fails for good (after the player's own retries and engine switch) or turns out to be an addon's
// error clip, play the next best source in the same language (else the preferred ones) instead of
// the error. Up to MaxSourceFailovers per episode, never a source that already failed, and never
// over a source the user picked by hand in the panels.

private const val MaxSourceFailovers = 3
private const val SourceListWaitMs = 15_000L
private const val SwitchWatchMs = 20_000L
private const val ShortClipCheckAfterMs = 2_000L
/** ~48 Mbps: a real clip this short can't be bigger than that. */
private const val MaxRealClipBytesPerSecond = 6_000_000L

internal class SourceFailoverState {
    var episodeKey: String? = null
    val failedKeys = mutableSetOf<String>()
    var attempts = 0
    /** Source picked by hand in the Fontes/Episodes panels: its failure shows the error. */
    var manualPick: Stream? = null
    var inProgress = false
    /** Source whose short clip was already checked: the progress loop checks each source once. */
    var shortClipCheckedKey: String? = null
}

/**
 * Called right before a fatal playback error is shown ([errorMessage]), or with null for an addon's
 * error clip (see [onShortClipPlaying]); true when another source takes over.
 */
internal fun PlayerRuntimeController.tryAutoSourceFailover(errorMessage: String?): Boolean {
    val settings = latestPlayerSettings ?: return false
    if (settings.streamAutoPlayMode != StreamAutoPlayMode.BEST_PREFERRED_AUDIO) return false
    if (contentType.equals("cloud", ignoreCase = true)) return false
    val failover = sourceFailover
    if (failover.inProgress) return true

    val state = _uiState.value
    val episodeKey = "${currentVideoId ?: contentId}:$currentSeason:$currentEpisode"
    if (failover.episodeKey != episodeKey) {
        failover.episodeKey = episodeKey
        failover.failedKeys.clear()
        failover.attempts = 0
    }
    if (failover.manualPick?.let { state.isPlaying(it) } == true) return false
    if (failover.attempts >= MaxSourceFailovers) return false
    val failedKey = state.currentFailoverKey() ?: return false
    failover.failedKeys += failedKey
    failover.attempts++
    failover.inProgress = true

    // The language playing, else the preferred ones in order (as autoplay picks).
    val languages = (listOf(requestedSourceAudioLanguage ?: state.currentAudioLanguage()) +
        settings.autoPlayAudioLanguages()).filterNotNull().distinct()
    val failedName = state.currentStreamName
    // Keep the loading state up instead of flashing the error while the next source is chosen.
    _uiState.update { it.copy(error = null, isBuffering = true, showLoadingOverlay = it.loadingOverlayEnabled) }
    scope.launch {
        val pick = try {
            awaitFailoverCandidate(languages, failover)
        } finally {
            failover.inProgress = false
        }
        if (pick == null) {
            Log.w(PlayerRuntimeController.TAG, "Source failover: no other source in $languages after $failedName")
            onFailoverExhausted(errorMessage)
            return@launch
        }
        val (next, language) = pick
        Log.w(PlayerRuntimeController.TAG, "Source failover ${failover.attempts}: $failedName -> ${next.name ?: next.addonName} ($language)")
        // Counted as failed up front: if it can't even be resolved, the next attempt skips it.
        next.failoverKey()?.let(failover.failedKeys::add)
        requestedSourceAudioLanguage = language
        _uiState.update { it.copy(sourceStreamsError = null) }
        switchToSourceStream(next)
        // A torrent/debrid candidate that fails to resolve only sets the panel error and leaves the
        // old source (or the loading state) up: move on to the next source instead of staying there.
        // Done when the new source plays; an error clip is still playing while it resolves.
        val outcome = withTimeoutOrNull(SwitchWatchMs) {
            _uiState.first {
                it.sourceStreamsError != null || !it.error.isNullOrBlank() ||
                    (it.isPlaying && it.currentFailoverKey() != failedKey)
            }
        }
        if (outcome?.sourceStreamsError != null) {
            _uiState.update { it.copy(sourceStreamsError = null) }
            if (!tryAutoSourceFailover(errorMessage)) onFailoverExhausted(errorMessage)
        }
    }
    return true
}

/** No other source: the error for a playback failure, the source list for an addon's error clip. */
private fun PlayerRuntimeController.onFailoverExhausted(errorMessage: String?) {
    _uiState.update { it.copy(error = errorMessage, isBuffering = false, showLoadingOverlay = false) }
    if (errorMessage == null) showSourcesPanel()
}

/**
 * Called by the progress loop while a clip shorter than ~2 min plays. Debrid addons answer with a
 * 30 s video when they can't serve the file ("only rar is available", "downloading"): in the
 * "best in my language" mode that is a failed source too, so the next one plays.
 */
internal fun PlayerRuntimeController.onShortClipPlaying(positionMs: Long, durationMs: Long) {
    // A couple of seconds in, so a duration reported before the real one is known doesn't count.
    if (positionMs < ShortClipCheckAfterMs) return
    val state = _uiState.value
    val key = state.currentFailoverKey() ?: return
    if (sourceFailover.shortClipCheckedKey == key) return
    sourceFailover.shortClipCheckedKey = key
    // The size the source declares: currentVideoSize can be the clip's own, measured for subtitles.
    val declaredSize = state.sourceAllStreams.firstOrNull { state.isPlaying(it) }?.behaviorHints?.videoSize
    if (!isAddonErrorClip(durationMs, declaredSize)) return
    Log.w(PlayerRuntimeController.TAG, "Source failover: ${durationMs / 1000}s clip from ${_uiState.value.currentStreamName} looks like an addon error video")
    tryAutoSourceFailover(errorMessage = null)
}

/** A short clip is the real file only when the source declares a size that fits it. */
private fun isAddonErrorClip(durationMs: Long, declaredSize: Long?): Boolean {
    val size = declaredSize?.takeIf { it > 0L } ?: return true
    return size / (durationMs / 1000).coerceAtLeast(1L) > MaxRealClipBytesPerSecond
}

/** Marks a source the user picked by hand (Fontes/Episodes panels), so its failure is not hidden. */
internal fun PlayerRuntimeController.markManualSourcePick(stream: Stream) {
    sourceFailover.manualPick = stream
}

/** The best source left and the language it was picked for, trying [languages] in order. */
private suspend fun PlayerRuntimeController.awaitFailoverCandidate(
    languages: List<String>,
    failover: SourceFailoverState,
): Pair<Stream, String?>? {
    // The episode's search is kept by the player, so this is usually instant.
    if (_uiState.value.sourceAllStreams.isEmpty()) loadSourceStreams(forceRefresh = false)
    // Use what already arrived; wait for slow addons only when nothing fits yet.
    return bestFailoverCandidate(languages, failover) ?: run {
        withTimeoutOrNull(SourceListWaitMs) {
            _uiState.first { it.sourceAllStreams.isNotEmpty() && !it.isLoadingSourceStreams }
        }
        bestFailoverCandidate(languages, failover)
    }
}

private fun PlayerRuntimeController.bestFailoverCandidate(
    languages: List<String>,
    failover: SourceFailoverState,
): Pair<Stream, String?>? {
    val state = _uiState.value
    // The source playing, as the list knows it (its hash or URL may differ from the player's).
    state.sourceAllStreams.filter { state.isPlaying(it) }.mapNotNull { it.failoverKey() }
        .forEach(failover.failedKeys::add)
    val candidates = state.sourceAllStreams.filter { stream ->
        stream.isFailoverPlayable() && stream.failoverKey() !in failover.failedKeys && !state.isPlaying(stream)
    }
    for (language in languages.ifEmpty { listOf(null) }) {
        val best = rankSourceStreams(
            candidates,
            SourceRanking(
                language = language,
                maxQuality = latestPlayerSettings?.streamAutoPlayMaxQuality?.bucket,
                strictCap = false,
                preferredBingeGroup = currentStreamBingeGroup,
            ),
        ).firstOrNull()
        if (best != null) return best to language
    }
    return null
}

/** Same source as the one playing (the matcher behind the sources panel's "Assistindo" badge). */
private fun PlayerUiState.isPlaying(stream: Stream): Boolean =
    findCurrentStreamIndex(
        streams = listOf(stream),
        currentStreamInfoHash = currentStreamInfoHash,
        currentStreamFileIdx = currentStreamFileIdx,
        currentStreamAddonName = currentStreamAddonName,
        currentStreamUrl = currentStreamUrl,
        currentStreamName = currentStreamName,
    ) == 0 || (stream.addonName == currentStreamAddonName && (stream.name ?: stream.addonName) == currentStreamName)

// A torrent is identified by its hash alone: the file index is not kept the same way everywhere.
private fun Stream.failoverKey(): String? =
    (infoHash ?: clientResolve?.infoHash)?.lowercase() ?: getStreamUrl() ?: externalUrl

private fun PlayerUiState.currentFailoverKey(): String? =
    currentStreamInfoHash?.lowercase() ?: currentStreamUrl?.takeIf { it.isNotBlank() }

private fun Stream.isFailoverPlayable(): Boolean {
    if (isExternal()) return false
    return when (debridCacheStatus?.state) {
        StreamDebridCacheState.CHECKING, StreamDebridCacheState.NOT_CACHED, StreamDebridCacheState.UNKNOWN -> false
        else -> true
    }
}
