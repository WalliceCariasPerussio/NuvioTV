package com.nuvio.tv.ui.screens.stream

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Stream

// Audio language and quality chips that replace the addon chips on the stream screen.
// The choice travels through StreamScreenUiState.selectedAddonFilter as an encoded key
// ("audio:pt-br|quality:FHD_1080"), so the ViewModel's paging keeps working unchanged:
// it only swaps the addon-name comparison for matchesStreamFilter.

private const val FilterKeyPrefix = "audio:"
private const val QualitySeparator = "|quality:"
internal const val StreamFilterAll = "all"

internal data class StreamFilterKey(val language: String, val quality: String) {
    fun encode(): String = "$FilterKeyPrefix$language$QualitySeparator$quality"

    companion object {
        fun decode(raw: String?): StreamFilterKey? {
            if (raw == null || !raw.startsWith(FilterKeyPrefix)) return null
            val body = raw.removePrefix(FilterKeyPrefix)
            return StreamFilterKey(
                language = body.substringBefore(QualitySeparator),
                quality = body.substringAfter(QualitySeparator, StreamFilterAll),
            )
        }
    }
}

/** The ViewModel's filter: an encoded audio/quality key, or a plain addon name as before. */
internal fun Stream.matchesStreamFilter(filter: String?): Boolean {
    if (filter == null) return true
    val key = StreamFilterKey.decode(filter) ?: return addonName == filter
    if (key.language == StreamFilterAll && key.quality == StreamFilterAll) return true
    val traits = streamTraits()
    val languageMatches = when (key.language) {
        StreamFilterAll -> true
        StreamLanguageUnknown -> traits.audioLanguages.isEmpty()
        else -> key.language in traits.audioLanguages
    }
    return languageMatches && (key.quality == StreamFilterAll || traits.quality.name == key.quality)
}

/** What the two chip rows show and do. Chip "names" are display labels, as AddonFilterChips expects. */
internal class StreamAudioQualityChipState(
    val languageNames: List<String>,
    val selectedLanguageName: String?,
    val qualityNames: List<String>,
    val selectedQualityName: String?,
    val selectLanguage: (String?) -> Unit,
    val selectQuality: (String?) -> Unit,
)

@Composable
internal fun rememberStreamAudioQualityChipState(
    allStreams: List<Stream>,
    selectedFilter: String?,
    playerSettings: PlayerSettings?,
    onFilterSelected: (String?) -> Unit,
): StreamAudioQualityChipState {
    val portuguese = stringResource(R.string.stream_filter_language_portuguese)
    val unknown = stringResource(R.string.stream_filter_language_unknown)
    val otherQuality = stringResource(R.string.stream_filter_quality_other)
    // Auto (preferred language + best quality) until the user picks a chip.
    var userChose by rememberSaveable { mutableStateOf(false) }

    val traits = remember(allStreams) { allStreams.map { it.streamTraits() } }
    val counts = remember(traits) { languageCounts(traits) }
    val keysByCount = remember(counts) { languageKeysByCount(counts) }
    val targets = remember(playerSettings) {
        playerSettings?.let { preferredAudioTargets(it, contentOriginalLanguage = null) }.orEmpty()
    }
    val preferredKeys = remember(keysByCount, targets) {
        targets.mapNotNull { matchingLanguageKey(keysByCount, it) }.distinct()
    }
    // "Não identificado" right after "Tudo", then preferred languages, then the rest by count.
    val languageKeys = listOfNotNull(StreamLanguageUnknown.takeIf { it in counts }) +
        preferredKeys + keysByCount.filterNot { it in preferredKeys }

    val current = StreamFilterKey.decode(selectedFilter)
    val selectedLanguage = current?.language ?: StreamFilterAll
    val languageTraits = traits.filter { streamTraits ->
        when (selectedLanguage) {
            StreamFilterAll -> true
            StreamLanguageUnknown -> streamTraits.audioLanguages.isEmpty()
            else -> selectedLanguage in streamTraits.audioLanguages
        }
    }
    val qualityCounts = StreamQualityBucket.entries.mapNotNull { bucket ->
        val count = languageTraits.count { it.quality == bucket }
        if (count > 0) bucket to count else null
    }

    fun languageName(key: String) = "${streamLanguageLabel(key, portuguese, unknown)} · ${counts[key] ?: 0}"
    fun qualityName(bucket: StreamQualityBucket, count: Int) = "${bucket.label(otherQuality)} · $count"

    val languageNames = languageKeys.map(::languageName)
    val qualityNames = qualityCounts.map { (bucket, count) -> qualityName(bucket, count) }

    // The best quality within the automatic cap, else the closest one above it.
    val maxQuality = playerSettings?.streamAutoPlayMaxQuality?.bucket
    fun bestQuality(language: String): String {
        val qualities = traits
            .filter { streamTraits ->
                when (language) {
                    StreamFilterAll -> true
                    StreamLanguageUnknown -> streamTraits.audioLanguages.isEmpty()
                    else -> language in streamTraits.audioLanguages
                }
            }
            .map { it.quality }
            .filter { it != StreamQualityBucket.OTHER }
        val best = qualities.filter { it.isWithin(maxQuality) }.minByOrNull { it.ordinal }
            ?: qualities.maxByOrNull { it.ordinal }
        return best?.name ?: StreamFilterAll
    }

    val defaultKey = run {
        val language = when {
            targets.isEmpty() || keysByCount.isEmpty() -> StreamFilterAll
            preferredKeys.isNotEmpty() -> preferredKeys.first()
            else -> keysByCount.first()
        }
        StreamFilterKey(language, bestQuality(language))
    }
    LaunchedEffect(defaultKey, userChose, allStreams.isEmpty()) {
        if (!userChose && allStreams.isNotEmpty() && current != defaultKey) {
            onFilterSelected(defaultKey.encode())
        }
    }

    return StreamAudioQualityChipState(
        languageNames = languageNames,
        selectedLanguageName = languageKeys.indexOf(selectedLanguage).takeIf { it >= 0 }?.let(languageNames::get),
        qualityNames = qualityNames,
        selectedQualityName = qualityCounts.indexOfFirst { it.first.name == current?.quality }
            .takeIf { it >= 0 }?.let(qualityNames::get),
        selectLanguage = { name ->
            userChose = true
            val language = name?.let { languageKeys.getOrNull(languageNames.indexOf(it)) } ?: StreamFilterAll
            // A new language starts on its best quality, like the first opening.
            onFilterSelected(StreamFilterKey(language, bestQuality(language)).encode())
        },
        selectQuality = { name ->
            userChose = true
            val quality = name?.let { qualityCounts.getOrNull(qualityNames.indexOf(it))?.first?.name } ?: StreamFilterAll
            onFilterSelected(StreamFilterKey(selectedLanguage, quality).encode())
        },
    )
}

internal fun StreamQualityBucket.label(otherLabel: String): String = when (this) {
    StreamQualityBucket.UHD_4K -> "4K"
    StreamQualityBucket.QHD_1440 -> "1440p"
    StreamQualityBucket.FHD_1080 -> "1080p"
    StreamQualityBucket.HD_720 -> "720p"
    StreamQualityBucket.SD -> "SD"
    StreamQualityBucket.OTHER -> otherLabel
}
