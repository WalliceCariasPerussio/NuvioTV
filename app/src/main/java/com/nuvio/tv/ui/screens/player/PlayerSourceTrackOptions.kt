package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
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
import kotlinx.coroutines.flow.update

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
    /** Audio languages found in this quality, preferred first (for the "switch audio?" prompt). */
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

/** Language of the audio track playing, in the source list's terms (e.g. "pt-br", "ja"). */
internal fun PlayerUiState.currentAudioLanguage(): String? {
    val streamLanguages = currentSourceStream()?.streamTraits()?.audioLanguages.orEmpty()
    val trackLanguage = audioTracks.firstOrNull { it.index == selectedAudioTrackIndex }
        ?.language
        ?.let(::streamLanguageCode)
        ?: return streamLanguages.firstOrNull()
    return streamLanguages.firstOrNull { it == trackLanguage }
        ?: streamLanguages.firstOrNull { sameLanguage(it, trackLanguage) }
        ?: trackLanguage
}

internal fun PlayerUiState.buildSourceTrackOptions(
    preferredTargets: List<String>,
    maxQuality: StreamQualityBucket? = null,
): SourceTrackOptions {
    val streams = sourceAllStreams
    val traits = streams.map { it.streamTraits() }
    val currentLanguage = currentAudioLanguage()
    val currentQuality = currentSourceStream(streams)?.streamTraits()?.quality

    val counts = languageCounts(traits) - StreamLanguageUnknown
    val keysByCount = languageKeysByCount(counts)
    val preferredKeys = preferredTargets.mapNotNull { matchingLanguageKey(keysByCount, it) }.distinct()
    val orderedKeys = preferredKeys + keysByCount.filterNot { it in preferredKeys }

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
            isCurrent = currentLanguage != null && sameLanguage(key, currentLanguage),
        )
    }

    fun qualities(language: String?): List<SourceQualityOption> = StreamQualityBucket.entries.mapNotNull { bucket ->
        val inBucket = traits.filter { it.quality == bucket }
        val matching = if (language == null) inBucket else inBucket.filter { t -> t.audioLanguages.any { sameLanguage(it, language) } }
        if (matching.isEmpty()) return@mapNotNull null
        val bucketLanguages = orderedKeys.filter { key -> inBucket.any { key in it.audioLanguages } }
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
 * prefer the release group and the addon playing now.
 */
internal fun PlayerRuntimeController.bestSourceStream(
    streams: List<Stream>,
    language: String?,
    quality: StreamQualityBucket?,
): Stream? {
    val scoped = if (quality == null) streams else streams.filter { it.streamTraits().quality == quality }
    return rankSourceStreams(
        scoped,
        SourceRanking(
            language = language,
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
    loadSourceStreams(forceRefresh = false)
}

/** "Todos" scope: plays the language; on the stream playing if it carries it, else on its best source. */
internal fun PlayerRuntimeController.selectSourceAudioLanguage(language: String) {
    val state = _uiState.value
    val embedded = state.audioTracks.firstOrNull { track ->
        track.language?.let(::streamLanguageCode)?.let { sameLanguage(it, language) } == true
    }
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
 * Quality panel: plays the best source of that quality with [language] (null = any).
 * Returns false when no source of that quality has it, so the panel asks which audio to use.
 */
internal fun PlayerRuntimeController.selectSourceQuality(quality: StreamQualityBucket, language: String?): Boolean {
    val state = _uiState.value
    val stream = bestSourceStream(state.sourceAllStreams, language, quality) ?: return false
    if (stream == state.currentSourceStream()) {
        _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
        return true
    }
    switchSourceForTrackPanel(stream, language)
    return true
}

private fun PlayerRuntimeController.switchSourceForTrackPanel(stream: Stream, language: String?) {
    requestedSourceAudioLanguage = language
    _uiState.update { it.copy(showAudioOverlay = false, audioOverlayQualityMode = false) }
    switchToSourceStream(stream)
}

/**
 * Called when a stream reports its audio tracks: turns the requested language into the track
 * preference the restore pass applies, so the preferred-language default does not win over it.
 */
internal fun PlayerRuntimeController.applyRequestedSourceAudioLanguage(audioTracks: List<TrackInfo>) {
    val language = requestedSourceAudioLanguage ?: return
    if (audioTracks.isEmpty()) return
    requestedSourceAudioLanguage = null
    val track = audioTracks.firstOrNull { candidate ->
        candidate.language?.let(::streamLanguageCode)?.let { sameLanguage(it, language) } == true
    } ?: return
    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = track.language,
        name = track.name,
        trackId = track.trackId,
    )
    persistedTrackPreference = (persistedTrackPreference ?: PlayerRuntimeController.TrackPreference())
        .copy(audio = selection)
    // Like picking the track by hand, the choice is remembered for this title.
    rememberedTrackPreference = (rememberedTrackPreference ?: persistedTrackPreference)?.copy(audio = selection)
    persistTrackPreference()
}
