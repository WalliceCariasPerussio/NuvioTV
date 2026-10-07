package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMaxQuality
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState

// Fork: one rule for every automatic source choice (autoplay "best in my language", the player's
// audio/quality panels and the failover to the next source), Netflix-like: the language first,
// then the best quality up to the cap, then the source most likely to start well.

/** The cap as a quality bucket; null means no limit. */
internal val StreamAutoPlayMaxQuality.bucket: StreamQualityBucket?
    get() = when (this) {
        StreamAutoPlayMaxQuality.NO_LIMIT -> null
        StreamAutoPlayMaxQuality.UHD_4K -> StreamQualityBucket.UHD_4K
        StreamAutoPlayMaxQuality.FHD_1080 -> StreamQualityBucket.FHD_1080
        StreamAutoPlayMaxQuality.HD_720 -> StreamQualityBucket.HD_720
    }

/** Within the cap (or no cap). "Outras" (unknown quality) counts as within. */
internal fun StreamQualityBucket.isWithin(cap: StreamQualityBucket?): Boolean =
    cap == null || this == StreamQualityBucket.OTHER || ordinal >= cap.ordinal

internal data class SourceRanking(
    /** Audio language the source must have; null accepts any. */
    val language: String?,
    val maxQuality: StreamQualityBucket?,
    /** Drop sources above the cap; otherwise they come after, closest to the cap first. */
    val strictCap: Boolean,
    val preferredBingeGroup: String? = null,
    val preferredAddon: String? = null,
    /** Sources to leave out (already failed), by [sourceKey]. */
    val excludedKeys: Set<String> = emptySet(),
)

/** Identity of a source across searches: torrent hash + file, else its URL. */
internal fun Stream.sourceKey(): String? {
    val hash = (infoHash ?: clientResolve?.infoHash)?.lowercase()
    if (hash != null) return "$hash:${fileIdx ?: clientResolve?.fileIdx ?: -1}"
    return getStreamUrl() ?: externalUrl
}

/** Likely to start fast: cached on the debrid, or a direct link that isn't a torrent. */
private fun Stream.isReadyLink(): Boolean =
    debridCacheStatus?.state == StreamDebridCacheState.CACHED || (getStreamUrl() != null && !isTorrent())

/** [streams] ordered from the best match down; sources without the language are left out. */
internal fun rankSourceStreams(streams: List<Stream>, ranking: SourceRanking): List<Stream> {
    val language = ranking.language
    val cap = ranking.maxQuality
    return streams.withIndex().mapNotNull { (index, stream) ->
        if (stream.sourceKey()?.let { it in ranking.excludedKeys } == true) return@mapNotNull null
        val traits = stream.streamTraits()
        val languageRank = when {
            language == null -> 0
            language in traits.audioLanguages -> 0
            traits.audioLanguages.any { it.substringBefore('-') == language.substringBefore('-') } -> 1
            else -> return@mapNotNull null
        }
        val withinCap = traits.quality.isWithin(cap)
        if (!withinCap && ranking.strictCap) return@mapNotNull null
        val qualityRank = when {
            withinCap -> traits.quality.ordinal
            // Above the cap: the closest to it first (1440p before 4K under a 1080p cap).
            else -> StreamQualityBucket.entries.size - traits.quality.ordinal
        }
        val key = listOf(
            languageRank,
            if (withinCap) 0 else 1,
            qualityRank,
            if (ranking.preferredBingeGroup != null && stream.behaviorHints?.bingeGroup == ranking.preferredBingeGroup) 0 else 1,
            if (ranking.preferredAddon != null && stream.addonName == ranking.preferredAddon) 0 else 1,
            if (stream.isReadyLink()) 0 else 1,
            if (traits.audioLanguages.size > 1) 0 else 1,
        )
        RankedStream(stream, key, stream.behaviorHints?.videoSize ?: 0L, index)
    }.sortedWith(
        Comparator<RankedStream> { a, b ->
            a.key.zip(b.key).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
        }.thenByDescending { it.size }.thenBy { it.index }
    ).map { it.stream }
}

private class RankedStream(val stream: Stream, val key: List<Int>, val size: Long, val index: Int)

/**
 * Autoplay "best in my language": the preferred language, else the secondary one; within the cap.
 * Null when no language has a source within the cap, so the source list opens instead (a language
 * only found above the cap is not played automatically: 4K stays a manual choice). No preferred
 * language at all means any language. With [preferredBingeGroup] / [preferredAddon] (the source
 * playing, for the next episode), that source comes first within the language and the cap, before
 * a better quality elsewhere.
 */
internal fun selectBestInPreferredLanguage(
    streams: List<Stream>,
    preferredLanguages: List<String>,
    maxQuality: StreamQualityBucket?,
    preferredBingeGroup: String? = null,
    preferredAddon: String? = null,
): Stream? {
    for (language in preferredLanguages.ifEmpty { listOf(null) }) {
        // Ranked within the cap first, so the best one within it leads the list.
        val withinCap = rankSourceStreams(
            streams,
            SourceRanking(language, maxQuality, strictCap = true, preferredBingeGroup, preferredAddon),
        )
        // Only above the cap in this language: try the next one.
        if (withinCap.isEmpty()) continue
        // Same release as the episode before, else the same addon: what the user was watching.
        val continuing = preferredBingeGroup?.let { group -> withinCap.firstOrNull { it.behaviorHints?.bingeGroup == group } }
            ?: preferredAddon?.let { addon -> withinCap.firstOrNull { it.addonName == addon } }
        return continuing ?: withinCap.first()
    }
    return null
}

/**
 * The next episode keeps what was [playing]: the same release (binge group), the same quality
 * first; else the source with the same name ("AnimesDigital LEG [Blogger] - 720p") from the same
 * addon, when known; else the same addon in the same quality. Always with the same audio: every language the playing source had,
 * so a group shared by the dub and the original doesn't swap the language. A source playing
 * without a known language (resumed from a saved link, which keeps only the name) doesn't rule
 * any out. Null when the next episode has none of them.
 */
internal fun selectContinuation(streams: List<Stream>, playing: Stream): Stream? {
    val playingTraits = playing.streamTraits()
    val languages = playingTraits.audioLanguages
    val candidates = streams.map { it to it.streamTraits() }
        .filter { (_, traits) -> traits.audioLanguages.containsAll(languages) }
    fun List<Pair<Stream, StreamTraits>>.sameQualityFirst(): Stream? =
        (firstOrNull { (_, traits) -> traits.quality == playingTraits.quality } ?: firstOrNull())?.first

    playing.behaviorHints?.bingeGroup?.trim()?.takeIf { it.isNotEmpty() }?.let { group ->
        candidates.filter { (stream, _) -> stream.behaviorHints?.bingeGroup == group }
            .sameQualityFirst()?.let { return it }
    }
    val addon = playing.addonName.takeIf { it.isNotBlank() }
    playing.name?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
        candidates.filter { (stream, _) -> stream.name?.trim() == name && (addon == null || stream.addonName == addon) }
            .sameQualityFirst()?.let { return it }
    }
    if (addon == null) return null
    return candidates.firstOrNull { (stream, traits) ->
        stream.addonName == addon && traits.quality == playingTraits.quality
    }?.first
}

/**
 * True when [stream] can't be beaten by sources still loading: preferred language (no preferred
 * language: any), at the cap quality (no cap: never) and a ready link. Lets autoplay start before
 * every addon answers.
 */
internal fun isUnbeatableChoice(stream: Stream, preferredLanguage: String?, maxQuality: StreamQualityBucket?): Boolean {
    if (maxQuality == null) return false
    val traits = stream.streamTraits()
    return (preferredLanguage == null || preferredLanguage in traits.audioLanguages) &&
        traits.quality == maxQuality && stream.isReadyLink()
}

/** Preferred audio languages for automatic choices, as source-list codes (primary, secondary). */
internal fun PlayerSettings.autoPlayAudioLanguages(contentOriginalLanguage: String? = null): List<String> =
    preferredAudioTargets(this, contentOriginalLanguage)
