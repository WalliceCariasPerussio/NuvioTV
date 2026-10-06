package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.stream.SourceRanking
import com.nuvio.tv.ui.screens.stream.autoPlayAudioLanguages
import com.nuvio.tv.ui.screens.stream.bucket
import com.nuvio.tv.ui.screens.stream.rankSourceStreams
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Fork: Netflix-like failover. In the "best in my language" autoplay mode, when the source playing
// fails for good (after the player's own retries and engine switch) or turns out to be an addon's
// error clip, play the next best source in the same language (else the preferred ones) instead of
// the error. Up to MaxSourceFailovers per episode, never a source that already failed, never above
// the quality cap, and never over a source the user picked by hand (panels or stream screen).

private const val MaxSourceFailovers = 3
private const val SourceListWaitMs = 15_000L
/** A torrent/debrid candidate still resolving after this counts as failed. */
private const val ResolveWatchMs = 60_000L
private const val ShortClipCheckAfterMs = 2_000L
/** ~48 Mbps: a real clip this short can't be bigger than that. */
private const val MaxRealClipBytesPerSecond = 6_000_000L
/** Addon links expire: a source list older than this is searched again before switching. */
private const val SourceListMaxAgeMs = 2 * 60 * 60 * 1_000L

internal class SourceFailoverState(
    /** The player opened on a source picked by hand on the stream screen. */
    var manualStart: Boolean = false,
) {
    var episodeKey: String? = null
    val failedKeys = mutableSetOf<String>()
    var attempts = 0
    /** Source picked by hand in the Fontes/Episodes/audio/quality panels: its failure shows the error. */
    var manualPick: Stream? = null
    /** The failover choosing and starting the next source; a manual pick cancels it. */
    var job: Job? = null
    var resolveJob: Job? = null
    /** Source being replaced while the next one is chosen: further reports of its failure are the same one. */
    var choosingFor: String? = null
    val inProgress: Boolean get() = choosingFor != null
    /** Source whose short clip was already checked: the progress loop checks each source once. */
    var shortClipCheckedKey: String? = null
    /** When the player's source list finished loading (elapsedRealtime). */
    var sourceListCompletedAtMs = 0L
    /** Bumped by [cancel]: a failover coroutine of an older generation stops. */
    var generation = 0

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        resolveJob?.cancel()
        resolveJob = null
        choosingFor = null
    }
}

/**
 * Audio language the next stream starts on. [remember]: picked by hand in the panels, so it becomes
 * the title's audio preference; a language chosen by the failover only applies to that stream.
 */
internal class SourceAudioRequest(val language: String, val remember: Boolean, val episodeKey: String)

internal fun PlayerRuntimeController.currentEpisodeKey(): String =
    "${currentVideoId ?: contentId}:$currentSeason:$currentEpisode"

/**
 * Called right before a fatal playback error is shown ([errorMessage]), or with null for an addon's
 * error clip (see [onShortClipPlaying]); true when another source takes over (for an error clip,
 * also when the source list is shown instead). [showSwitchToMpv] is what the error path would show,
 * for when no other source is left.
 */
internal fun PlayerRuntimeController.tryAutoSourceFailover(
    errorMessage: String?,
    showSwitchToMpv: Boolean = false,
): Boolean {
    val settings = latestPlayerSettings ?: return false
    if (settings.streamAutoPlayMode != StreamAutoPlayMode.BEST_PREFERRED_AUDIO) return false
    if (contentType.equals("cloud", ignoreCase = true)) return false
    val failover = sourceFailover
    val state = _uiState.value
    val failedKey = state.currentFailoverKey() ?: return false
    // The same failure reported again (player error, engine callbacks) while its replacement is chosen.
    if (failover.choosingFor == failedKey) return true
    // A failover still running is for a source no longer playing.
    failover.cancel()

    val episodeKey = currentEpisodeKey()
    if (failover.episodeKey != episodeKey) {
        failover.episodeKey = episodeKey
        failover.failedKeys.clear()
        failover.attempts = 0
    }
    if (failover.manualStart || failover.manualPick?.let { state.isPlaying(it) } == true) return false
    if (failover.attempts >= MaxSourceFailovers) {
        // An error clip has no error screen of its own: show the sources instead.
        if (errorMessage != null) return false
        onFailoverExhausted(errorMessage = null, showSwitchToMpv = false)
        return true
    }
    failover.failedKeys += failedKey
    failover.attempts++
    failover.choosingFor = failedKey
    val generation = failover.generation

    // The language playing, else the preferred ones in order (as autoplay picks).
    val preferred = settings.autoPlayAudioLanguages(contentLanguage)
    val requested = requestedSourceAudio?.takeIf { it.episodeKey == episodeKey }?.language
    val languages = (listOf(requested ?: state.currentAudioLanguage(preferred)) + preferred).filterNotNull().distinct()
    val failedName = state.currentStreamName
    val manualPickBefore = failover.manualPick
    // Keep the loading state up instead of flashing the error while the next source is chosen.
    _uiState.update { it.copy(error = null, isBuffering = true, showLoadingOverlay = it.loadingOverlayEnabled) }
    failover.job = scope.launch {
        val pick = try {
            awaitFailoverCandidate(languages, failover)
        } finally {
            if (failover.generation == generation) failover.choosingFor = null
        }
        // The user moved on while the list loaded (another source or episode): that choice stands.
        if (failover.generation != generation || currentEpisodeKey() != episodeKey ||
            _uiState.value.currentFailoverKey() != failedKey || failover.manualPick !== manualPickBefore
        ) {
            return@launch
        }
        if (pick == null) {
            Log.w(PlayerRuntimeController.TAG, "Source failover: no other source in $languages after $failedName")
            onFailoverExhausted(errorMessage, showSwitchToMpv)
            return@launch
        }
        val (next, language) = pick
        Log.w(PlayerRuntimeController.TAG, "Source failover ${failover.attempts}: $failedName -> ${next.name ?: next.addonName} ($language)")
        // Counted as failed up front: if it can't even be resolved, the next attempt skips it.
        next.failoverKey()?.let(failover.failedKeys::add)
        requestedSourceAudio = language?.let { SourceAudioRequest(it, remember = false, episodeKey = episodeKey) }
        _uiState.update { it.copy(sourceStreamsError = null) }
        val previousResolve = debridResolveJob
        switchToSourceStream(next)
        // Torrent, debrid and YouTube candidates resolve first. A failure there only sets the panel
        // error and leaves the loading state up: move on to the next source instead of staying there.
        // Once the new source starts, its own failures come back here through the error paths.
        val resolveJob = debridResolveJob?.takeIf { it !== previousResolve } ?: return@launch
        failover.resolveJob = resolveJob
        // The failed source stays the one "playing" until the candidate resolves.
        failover.choosingFor = failedKey
        val resolved = try {
            withTimeoutOrNull(ResolveWatchMs) { resolveJob.join() } != null
        } finally {
            if (failover.generation == generation) {
                failover.choosingFor = null
                failover.resolveJob = null
            }
        }
        if (!resolved) resolveJob.cancel()
        if (resolved && _uiState.value.sourceStreamsError == null) return@launch
        requestedSourceAudio = null
        _uiState.update { it.copy(sourceStreamsError = null, isLoadingSourceStreams = false) }
        failover.job = null
        if (!tryAutoSourceFailover(errorMessage, showSwitchToMpv)) onFailoverExhausted(errorMessage, showSwitchToMpv)
    }
    return true
}

/**
 * No other source: the error (with the fields its error path sets) for a playback failure, the
 * source list for an addon's error clip, which keeps playing behind it.
 */
private fun PlayerRuntimeController.onFailoverExhausted(errorMessage: String?, showSwitchToMpv: Boolean) {
    if (errorMessage == null) {
        _uiState.update { it.copy(isBuffering = false, showLoadingOverlay = false) }
        showSourcesPanel()
        return
    }
    _uiState.update {
        it.copy(
            error = errorMessage,
            showSwitchToMpvErrorAction = showSwitchToMpv,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            isBuffering = false,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            playbackEnded = false,
            postPlayMode = null,
        )
    }
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
    // The size the source declares. currentVideoSize is that one too, unless the source declared
    // none: then it is measured from the file itself (for subtitles), which never looks like a clip.
    val listed = if (isCurrentSourceList()) state.currentSourceStream() else null
    val declaredSize = listed?.behaviorHints?.videoSize ?: currentVideoSize
    if (!isAddonErrorClip(durationMs, declaredSize)) return
    Log.w(PlayerRuntimeController.TAG, "Source failover: ${durationMs / 1000}s clip from ${state.currentStreamName} looks like an addon error video")
    tryAutoSourceFailover(errorMessage = null)
}

/** A short clip is an addon's error video only when the source declares a size far too big for it. */
private fun isAddonErrorClip(durationMs: Long, declaredSize: Long?): Boolean {
    val size = declaredSize?.takeIf { it > 0L } ?: return false
    return size / (durationMs / 1000).coerceAtLeast(1L) > MaxRealClipBytesPerSecond
}

/** Marks a source the user picked by hand, so its failure is not hidden; stops a failover running. */
internal fun PlayerRuntimeController.markManualSourcePick(stream: Stream) {
    sourceFailover.cancel()
    sourceFailover.manualStart = false
    sourceFailover.manualPick = stream
    requestedSourceAudio = null
}

/** The next episode's source was chosen automatically: a manual pick of this one no longer applies. */
internal fun PlayerRuntimeController.forgetManualSourcePick() {
    sourceFailover.cancel()
    sourceFailover.manualStart = false
    sourceFailover.manualPick = null
    requestedSourceAudio = null
}

/** The player's source list is the one of the episode playing (loadSourceStreams' request). */
internal fun PlayerRuntimeController.isCurrentSourceList(): Boolean {
    val target = currentStreamSearchTarget() ?: return false
    return sourceStreamsCacheRequestKey ==
        buildSourceRequestKey(type = target.type, videoId = target.videoId, season = target.season, episode = target.episode)
}

/** Called by loadSourceStreams when the list is complete. */
internal fun PlayerRuntimeController.onSourceListCompleted() {
    sourceFailover.sourceListCompletedAtMs = SystemClock.elapsedRealtime()
}

private fun PlayerRuntimeController.isSourceListStale(): Boolean =
    isCurrentSourceList() && sourceStreamsFetchCompleted &&
        SystemClock.elapsedRealtime() - sourceFailover.sourceListCompletedAtMs > SourceListMaxAgeMs

/** The best source left and the language it was picked for, trying [languages] in order. */
private suspend fun PlayerRuntimeController.awaitFailoverCandidate(
    languages: List<String>,
    failover: SourceFailoverState,
): Pair<Stream, String?>? {
    // Asked every time: it reuses the episode's search kept by the player (usually instant), and
    // starts over when the list holds another episode's sources or is too old for its links. The
    // list only counts once it is this episode's: loadSourceStreams switches it in a coroutine.
    loadSourceStreams(forceRefresh = isSourceListStale())
    val listJob = sourceStreamsJob
    // Use what already arrived; wait for the rest of the addons only when nothing fits yet.
    if (isCurrentSourceList()) bestFailoverCandidate(languages, failover)?.let { return it }
    if (!isCurrentSourceList() || !sourceStreamsFetchCompleted) {
        withTimeoutOrNull(SourceListWaitMs) { listJob?.join() }
    }
    return if (isCurrentSourceList()) bestFailoverCandidate(languages, failover) else null
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
        StreamAutoPlaySelector.isPlayable(stream) && stream.failoverKey() !in failover.failedKeys && !state.isPlaying(stream)
    }
    // No languages to go by: any language.
    for (language in languages.ifEmpty { listOf(null) }) {
        val best = rankSourceStreams(
            candidates,
            SourceRanking(
                language = language,
                maxQuality = latestPlayerSettings?.streamAutoPlayMaxQuality?.bucket,
                // Above the cap is a manual choice only, like the autoplay.
                strictCap = true,
                preferredBingeGroup = currentStreamBingeGroup,
            ),
        ).firstOrNull()
        if (best != null) return best to language
    }
    return null
}

/**
 * Same source as the one playing, by URL or torrent hash (the matcher behind the sources panel's
 * "Assistindo" badge). Not by name: addons give every source of a resolution the same one.
 */
private fun PlayerUiState.isPlaying(stream: Stream): Boolean =
    findCurrentStreamIndex(
        streams = listOf(stream),
        currentStreamInfoHash = currentStreamInfoHash,
        currentStreamFileIdx = currentStreamFileIdx,
        currentStreamAddonName = currentStreamAddonName,
        currentStreamUrl = currentStreamUrl,
        currentStreamName = currentStreamName,
    ) == 0

// A torrent is identified by its hash alone: the file index is not kept the same way everywhere.
private fun Stream.failoverKey(): String? =
    (infoHash ?: clientResolve?.infoHash)?.lowercase() ?: getStreamUrl() ?: externalUrl

private fun PlayerUiState.currentFailoverKey(): String? =
    currentStreamInfoHash?.lowercase() ?: currentStreamUrl?.takeIf { it.isNotBlank() }
