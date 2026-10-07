package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.ui.screens.stream.StreamLanguageUnknown
import com.nuvio.tv.ui.screens.stream.SourceRanking
import com.nuvio.tv.ui.screens.stream.StreamQualityBucket
import com.nuvio.tv.ui.screens.stream.bucket
import com.nuvio.tv.ui.screens.stream.isWithin
import com.nuvio.tv.ui.screens.stream.rankSourceStreams
import com.nuvio.tv.ui.screens.stream.languageCounts
import com.nuvio.tv.ui.screens.stream.languageKeysByCount
import com.nuvio.tv.ui.screens.stream.matchingLanguageKey
import com.nuvio.tv.ui.screens.stream.streamLanguageCode
import com.nuvio.tv.ui.screens.stream.streamTraits
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Fork: the audio panel's "Todos" scope and the quality panel pick another source of the episode
// playing, from the player's source search (kept in StreamSearchSessionCache while the player is
// open). A source switch carries the chosen audio language to the next stream.

internal data class SourceLanguageOption(
    val key: String,
    val streamCount: Int,
    val bestQuality: StreamQualityBucket?,
    val isCurrent: Boolean,
)

internal data class SourceQualityOption(
    val bucket: StreamQualityBucket,
    val streamCount: Int,
    val isCurrent: Boolean,
    /** Audio languages found in this quality, preferred first, then sources without one (for the "switch audio?" prompt). */
    val languages: List<String>,
)

internal class SourceTrackOptions(
    val currentLanguage: String?,
    val currentQuality: StreamQualityBucket?,
    val languages: List<SourceLanguageOption>,
    val qualitiesWithCurrentAudio: List<SourceQualityOption>,
    val allQualities: List<SourceQualityOption>,
)

internal fun sameLanguage(a: String, b: String): Boolean =
    a == b || a.substringBefore('-') == b.substringBefore('-')

/** The stream playing, found in the source list the same way the sources panel marks it. */
internal fun PlayerUiState.currentSourceStream(streams: List<Stream> = sourceAllStreams): Stream? =
    streams.getOrNull(
        findCurrentStreamIndex(
            streams = streams,
            currentStreamInfoHash = currentStreamInfoHash,
            currentStreamFileIdx = currentStreamFileIdx,
            currentStreamAddonName = currentStreamAddonName,
            currentStreamUrl = currentStreamUrl,
            currentStreamName = currentStreamName,
        )
    )

/**
 * Language of [track] in the source list's terms: the playing source's code that matches its tag,
 * exactly or by base language (a "por" track of a PT-BR source is "pt-br"), else the tag's own code.
 */
private fun trackLanguageVariant(track: TrackInfo, streamLanguages: List<String>): String? {
    val code = track.language?.let(::streamLanguageCode) ?: return null
    return streamLanguages.firstOrNull { it == code }
        ?: streamLanguages.firstOrNull { sameLanguage(it, code) }
        ?: code
}

/**
 * Language of the audio track playing, in the source list's terms (e.g. "pt-br", "ja"). When the
 * source doesn't say, a bare track tag ("pt" from "por") reads as the entry of [preferredLanguages]
 * with the same base ("pt-br"): the same audio, and the bare code would rank PT-PT releases first.
 */
internal fun PlayerUiState.currentAudioLanguage(preferredLanguages: List<String> = emptyList()): String? {
    val streamLanguages = currentSourceStream()?.streamTraits()?.audioLanguages.orEmpty()
    val trackLanguage = audioTracks.firstOrNull { it.index == selectedAudioTrackIndex }
        ?.let { trackLanguageVariant(it, streamLanguages) }
        ?: return streamLanguages.firstOrNull()
    if (trackLanguage in streamLanguages || '-' in trackLanguage) return trackLanguage
    return preferredLanguages.firstOrNull { sameLanguage(it, trackLanguage) } ?: trackLanguage
}

/**
 * The source playing: itself when it was picked in the player or is in the source list, else what
 * the navigation brought (name, description, file name, binge group, addon; a resumed saved link
 * brings only the name).
 */
internal fun PlayerRuntimeController.playingStream(): Stream {
    val state = _uiState.value
    return currentPlayingStream ?: state.currentSourceStream() ?: Stream(
        name = state.currentStreamName,
        title = null,
        description = currentStreamDescription,
        url = null,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = currentStreamBingeGroup,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = currentFilename,
        ),
        addonName = state.currentStreamAddonName.orEmpty(),
        addonLogo = null,
    )
}

/** [language] as the panels pass it: a language code, or StreamLanguageUnknown for sources without one. */
private fun Stream.hasAudioLanguage(language: String): Boolean {
    val languages = streamTraits().audioLanguages
    return if (language == StreamLanguageUnknown) languages.isEmpty() else languages.any { sameLanguage(it, language) }
}

internal fun PlayerUiState.buildSourceTrackOptions(
    preferredTargets: List<String>,
    maxQuality: StreamQualityBucket? = null,
): SourceTrackOptions {
    val streams = sourceAllStreams
    val traits = streams.map { it.streamTraits() }
    val currentLanguage = currentAudioLanguage(preferredTargets)
    val currentQuality = currentSourceStream(streams)?.streamTraits()?.quality

    val counts = languageCounts(traits) - StreamLanguageUnknown
    val keysByCount = languageKeysByCount(counts)
    val preferredKeys = preferredTargets.mapNotNull { matchingLanguageKey(keysByCount, it) }.distinct()
    val orderedKeys = preferredKeys + keysByCount.filterNot { it in preferredKeys }
    // One row is the one playing: the exact code, else the same base language ("pt" and "pt-br"
    // are two rows, not both current).
    val currentKey = currentLanguage?.let { language ->
        orderedKeys.firstOrNull { it == language } ?: orderedKeys.firstOrNull { sameLanguage(it, language) }
    }

    // What picking the language would play: the best quality within the cap, else the closest above.
    fun bestQuality(language: String): StreamQualityBucket? {
        val qualities = traits.filter { language in it.audioLanguages }.map { it.quality }
        return qualities.filter { it.isWithin(maxQuality) }.minByOrNull { it.ordinal }
            ?: qualities.maxByOrNull { it.ordinal }
    }

    val languages = orderedKeys.map { key ->
        SourceLanguageOption(
            key = key,
            streamCount = counts[key] ?: 0,
            bestQuality = bestQuality(key),
            isCurrent = key == currentKey,
        )
    }

    fun qualities(language: String?): List<SourceQualityOption> = StreamQualityBucket.entries.mapNotNull { bucket ->
        val inBucket = traits.filter { it.quality == bucket }
        val matching = if (language == null) inBucket else inBucket.filter { t -> t.audioLanguages.any { sameLanguage(it, language) } }
        if (matching.isEmpty()) return@mapNotNull null
        val bucketLanguages = orderedKeys.filter { key -> inBucket.any { key in it.audioLanguages } } +
            listOfNotNull(StreamLanguageUnknown.takeIf { inBucket.any { it.audioLanguages.isEmpty() } })
        SourceQualityOption(bucket, matching.size, isCurrent = bucket == currentQuality, languages = bucketLanguages)
    }

    return SourceTrackOptions(
        currentLanguage = currentLanguage,
        currentQuality = currentQuality,
        languages = languages,
        qualitiesWithCurrentAudio = currentLanguage?.let { qualities(it) }.orEmpty(),
        allQualities = qualities(null),
    )
}

/**
 * Best source for a language and/or quality, by the shared ranking (StreamSourceRanking.kt): a
 * language pick stays within the quality cap when it can; a quality picked by hand has no cap. Ties
 * prefer the release group and the addon playing now. Only sources the autoplay would play: no
 * external links, no torrents the debrid hasn't cached (or is still checking).
 */
internal fun PlayerRuntimeController.bestSourceStream(
    streams: List<Stream>,
    language: String?,
    quality: StreamQualityBucket?,
): Stream? {
    val unknownLanguage = language == StreamLanguageUnknown
    val scoped = streams.filter { stream ->
        StreamAutoPlaySelector.isPlayable(stream) &&
            (quality == null || stream.streamTraits().quality == quality) &&
            (!unknownLanguage || stream.streamTraits().audioLanguages.isEmpty())
    }
    return rankSourceStreams(
        scoped,
        SourceRanking(
            language = language.takeUnless { unknownLanguage },
            maxQuality = if (quality == null) latestPlayerSettings?.streamAutoPlayMaxQuality?.bucket else null,
            strictCap = false,
            preferredBingeGroup = currentStreamBingeGroup,
            preferredAddon = _uiState.value.currentStreamAddonName,
        ),
    ).firstOrNull()
}

/** Opens the audio overlay slot as the audio panel or the quality panel, with the source list loaded. */
internal fun PlayerRuntimeController.showSourceTrackPanel(qualityMode: Boolean) {
    _uiState.update {
        it.copy(
            showAudioOverlay = true,
            audioOverlayQualityMode = qualityMode,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showMoreDialog = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showControls = true,
        )
    }
    sourceTrackPanelSearching = true
    loadSourceStreams(forceRefresh = false)
}

/**
 * The audio/quality panel closed (PlayerScreen watches the overlay): stop the source search it
 * started and pause the plugins again, as closing Fontes does, unless Fontes or the failover is
 * using the search now.
 */
internal fun PlayerRuntimeController.releaseSourceTrackPanelSearch() {
    if (!sourceTrackPanelSearching) return
    sourceTrackPanelSearching = false
    if (_uiState.value.showSourcesPanel || sourceFailover.inProgress) return
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    _uiState.update { it.copy(isLoadingSourceStreams = false) }
}

/** "Todos" scope: plays the language; on the stream playing if it carries it, else on its best source. */
internal fun PlayerRuntimeController.selectSourceAudioLanguage(language: String) {
    val state = _uiState.value
    val streamLanguages = state.currentSourceStream()?.streamTraits()?.audioLanguages.orEmpty()
    // A choice by name needs that variant: "es-419" is not the Castilian track, nor "pt-br" the PT-PT one.
    val embedded = state.audioTracks.firstOrNull { trackLanguageVariant(it, streamLanguages) == language }
    if (embedded != null) {
        // Same as picking the track in "Stream atual".
        rememberAudioSelection(embedded.index)
        selectAudioTrack(embedded.index)
        _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
        return
    }
    val stream = bestSourceStream(state.sourceAllStreams, language, quality = null) ?: return
    switchSourceForTrackPanel(stream, language)
}

/**
 * Quality panel: plays the best source of that quality with [language] (null = any,
 * StreamLanguageUnknown = sources without one). Returns false when no source of that quality has
 * it, so the panel asks which audio to use.
 */
internal fun PlayerRuntimeController.selectSourceQuality(quality: StreamQualityBucket, language: String?): Boolean {
    val state = _uiState.value
    val current = state.currentSourceStream()
    // The quality playing, with that audio: nothing to switch, even if another source ranks higher.
    if (current != null && current.streamTraits().quality == quality &&
        (language == null || current.hasAudioLanguage(language))
    ) {
        _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
        return true
    }
    val stream = bestSourceStream(state.sourceAllStreams, language, quality) ?: return false
    if (stream == current) {
        _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
        return true
    }
    switchSourceForTrackPanel(stream, language.takeUnless { it == StreamLanguageUnknown })
    return true
}

private fun PlayerRuntimeController.switchSourceForTrackPanel(stream: Stream, language: String?) {
    // Picked by hand: if it fails, the error shows instead of the failover replacing the choice.
    markManualSourcePick(stream)
    val request = language?.let { SourceAudioRequest(it, remember = true, episodeKey = currentEpisodeKey()) }
    requestedSourceAudio = request
    _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
    val previousResolve = debridResolveJob
    switchToSourceStream(stream)
    // A source that can't be resolved never reports tracks: its request must not reach another stream.
    val resolveJob = debridResolveJob?.takeIf { it !== previousResolve } ?: return
    if (request == null) return
    scope.launch {
        resolveJob.join()
        if (requestedSourceAudio === request && _uiState.value.sourceStreamsError != null) requestedSourceAudio = null
    }
}

/**
 * Called when a stream reports its audio tracks: turns the requested language into the track
 * preference the restore pass applies, so the preferred-language default does not win over it.
 */
internal fun PlayerRuntimeController.applyRequestedSourceAudioLanguage(audioTracks: List<TrackInfo>) {
    val request = requestedSourceAudio ?: return
    if (audioTracks.isEmpty()) return
    requestedSourceAudio = null
    // Asked on another episode (the switch failed, then the episode changed): not for this stream.
    if (request.episodeKey != currentEpisodeKey()) return
    val streamLanguages = _uiState.value.currentSourceStream()?.streamTraits()?.audioLanguages.orEmpty()
    val track = audioTracks.firstOrNull { trackLanguageVariant(it, streamLanguages) == request.language }
        ?: audioTracks.firstOrNull { candidate ->
            candidate.language?.let(::streamLanguageCode)?.let { sameLanguage(it, request.language) } == true
        }
        ?: return
    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = track.language,
        name = track.name,
        trackId = track.trackId,
    )
    persistedTrackPreference = (persistedTrackPreference ?: PlayerRuntimeController.TrackPreference())
        .copy(audio = selection)
    // Only a language picked by hand becomes the title's choice, like picking the track; the
    // failover's fallback language is for this stream alone.
    if (!request.remember) return
    rememberedTrackPreference = (rememberedTrackPreference ?: persistedTrackPreference)?.copy(audio = selection)
    persistTrackPreference()
}
