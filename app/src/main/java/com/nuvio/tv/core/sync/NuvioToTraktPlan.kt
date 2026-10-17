package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem

private const val COMPLETED_FRACTION = 0.90
private const val MIN_PROGRESS_PERCENT = 1.0
private const val SAME_PROGRESS_PERCENT = 1.0
internal const val NUVIO_TO_TRAKT_MAX_IN_PROGRESS = 20

/** One in-progress row read raw from the Nuvio cloud. */
data class CloudProgressRecord(
    val contentId: String,
    val contentType: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val position: Long,
    val duration: Long,
    val lastWatched: Long
)

/** An in-progress row Trakt is missing, resumed there with a paused scrobble. */
data class NuvioToTraktProgress(
    val record: CloudProgressRecord,
    val progressPercent: Double
)

/** What the Nuvio cloud holds and Trakt lacks, e.g. watched on a device that was not connected to Trakt. */
data class NuvioToTraktPlan(
    val history: List<WatchedItem>,
    val inProgress: List<NuvioToTraktProgress>,
    val library: List<SavedLibraryItem>,
    val skippedUnsupportedIds: Int
) {
    val isEmpty: Boolean
        get() = history.isEmpty() && inProgress.isEmpty() && library.isEmpty()
}

/** Trakt reports `tmdb:`/`trakt:` lower case and trimmed; Nuvio rows must match that to be recognised. */
internal fun normalizeTraktComparableId(id: String): String {
    val trimmed = id.trim()
    val prefix = trimmed.substringBefore(':', missingDelimiterValue = "")
    return if (prefix.equals("tmdb", ignoreCase = true) || prefix.equals("trakt", ignoreCase = true)) {
        "${prefix.lowercase()}:${trimmed.substringAfter(':').trim()}"
    } else {
        trimmed
    }
}

/**
 * Only ids Trakt itself reports back can be checked against its history. Anime ids such as kitsu or
 * mal never match, so sending them would add a duplicate play on every run.
 */
internal fun isTraktComparableContentId(id: String): Boolean {
    val trimmed = id.trim()
    return trimmed.startsWith("tt") ||
        trimmed.startsWith("tmdb:", ignoreCase = true) ||
        trimmed.startsWith("trakt:", ignoreCase = true)
}

internal fun isMovieType(type: String): Boolean = type.trim().lowercase() in setOf("movie", "film")

/** Key of one watched movie or episode under a single id form; Trakt's side is expanded to every id form. */
internal fun watchedKey(type: String, id: String, season: Int?, episode: Int?): String =
    if (isMovieType(type)) "movie:${id.trim()}" else "episode:${id.trim()}:${season ?: -1}:${episode ?: -1}"

/** Identifies one paused movie or episode, matching any id form of the title. */
internal fun pausedProgressKey(contentId: String, season: Int?, episode: Int?): String =
    "${contentId.trim()}:${season ?: -1}:${episode ?: -1}"

internal fun historyIdentity(item: WatchedItem): String = watchedKey(item.contentType, item.contentId, item.season, item.episode)

/**
 * Every id form a saved title can carry, so a Nuvio library item matches the Trakt one however each
 * stored it. Empty when Trakt has no way to identify the title (anime-only ids).
 */
internal fun libraryMatchKeys(
    id: String,
    type: String,
    imdbId: String? = null,
    tmdbId: Int? = null,
    traktId: Int? = null
): Set<String> {
    val kind = if (isMovieType(type)) "movie" else "show"
    val normalized = normalizeTraktComparableId(id)
    return buildSet {
        listOfNotNull(imdbId, normalized.takeIf { it.startsWith("tt") })
            .map(String::trim)
            .filter { imdb -> imdb.startsWith("tt") }
            .forEach { imdb -> add("$kind:${imdb.substringBefore(':')}") }
        listOfNotNull(tmdbId, normalized.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull())
            .forEach { tmdb -> add("$kind:tmdb:$tmdb") }
        listOfNotNull(traktId, normalized.takeIf { it.startsWith("trakt:") }?.substringAfter(':')?.toIntOrNull())
            .forEach { trakt -> add("$kind:trakt:$trakt") }
    }
}

/**
 * Picks the Nuvio cloud rows Trakt is missing. Watched episodes and movies (and completed progress) go to
 * Trakt history with their original date, so they never jump ahead in Continue Watching. In-progress rows
 * are resumed only when newer than anything Trakt has for that title, oldest first, because Trakt stamps a
 * paused scrobble with the time it is sent.
 */
internal fun planNuvioToTraktSync(
    nuvioWatched: Collection<WatchedItem>,
    nuvioProgress: Collection<CloudProgressRecord>,
    traktWatchedKeys: Set<String>,
    traktLatestActivityByContentId: Map<String, Long>,
    inProgressCutoffEpochMs: Long?,
    traktPausedPercentByKey: Map<String, Double> = emptyMap(),
    recentlySentHistory: Set<String> = emptySet(),
    nuvioLibrary: Collection<SavedLibraryItem> = emptyList(),
    traktLibraryKeys: Set<String> = emptySet(),
    maxInProgress: Int = NUVIO_TO_TRAKT_MAX_IN_PROGRESS
): NuvioToTraktPlan {
    fun isWatchedOnTrakt(type: String, id: String, season: Int?, episode: Int?): Boolean =
        watchedKey(type, id, season, episode) in traktWatchedKeys

    val normalizedWatched = nuvioWatched.map { item -> item.copy(contentId = normalizeTraktComparableId(item.contentId)) }
    val normalizedProgress = nuvioProgress.map { record ->
        record.copy(contentId = normalizeTraktComparableId(record.contentId))
    }
    val unsupported = linkedSetOf<String>()
    val historyByIdentity = linkedMapOf<String, WatchedItem>()
    val completedProgressItems = normalizedProgress
        .filter { record -> isCompleted(record) }
        .map { record ->
            WatchedItem(
                contentId = record.contentId,
                contentType = record.contentType,
                title = record.contentId,
                season = record.season,
                episode = record.episode,
                watchedAt = record.lastWatched
            )
        }
    (normalizedWatched + completedProgressItems).forEach { item ->
        if (!isMovieType(item.contentType) && (item.season == null || item.episode == null)) return@forEach
        val identity = historyIdentity(item)
        if (!isTraktComparableContentId(item.contentId)) {
            unsupported += identity
            return@forEach
        }
        if (isWatchedOnTrakt(item.contentType, item.contentId, item.season, item.episode)) return@forEach
        // Sent moments ago but not recognised in Trakt's reply (e.g. other episode numbering):
        // sending it again would only add another play.
        if (identity in recentlySentHistory) return@forEach
        // A watched mark carries the title; keep it over the completed-progress copy.
        historyByIdentity.putIfAbsent(identity, item)
    }

    val latestByContentId = traktLatestActivityByContentId.toMutableMap()
    (normalizedWatched + completedProgressItems).forEach { item ->
        val id = item.contentId.trim()
        latestByContentId[id] = maxOf(latestByContentId[id] ?: Long.MIN_VALUE, item.watchedAt)
    }

    val inProgress = normalizedProgress
        .asSequence()
        .filter { record -> record.position > 0L && record.duration > 0L }
        .filter { record -> !isCompleted(record) }
        .filter { record -> inProgressCutoffEpochMs == null || record.lastWatched >= inProgressCutoffEpochMs }
        .filter { record ->
            val supported = isTraktComparableContentId(record.contentId)
            if (!supported) unsupported += "progress:${record.contentId}:${record.season}:${record.episode}"
            supported
        }
        .filterNot { record -> isWatchedOnTrakt(record.contentType, record.contentId, record.season, record.episode) }
        // Something newer for the title (on Trakt or finished in Nuvio) makes this row stale.
        .filter { record -> record.lastWatched > (latestByContentId[record.contentId.trim()] ?: Long.MIN_VALUE) }
        .map { record ->
            NuvioToTraktProgress(
                record = record,
                progressPercent = (record.position.toDouble() * 100.0 / record.duration.toDouble()).coerceAtMost(99.0)
            )
        }
        // Opened by accident (a trailer, a wrong episode): not worth a Continue Watching slot.
        .filter { progress -> progress.progressPercent >= MIN_PROGRESS_PERCENT }
        .groupBy { progress -> progress.record.contentId.trim() }
        .values
        .map { rows -> rows.maxBy { progress -> progress.record.lastWatched } }
        // Trakt keeps the old pause date when the same point is sent again, so this would never settle.
        .filterNot { progress ->
            val record = progress.record
            val pausedPercent = traktPausedPercentByKey[pausedProgressKey(record.contentId, record.season, record.episode)]
            pausedPercent != null && pausedPercent >= progress.progressPercent - SAME_PROGRESS_PERCENT
        }
        .sortedBy { progress -> progress.record.lastWatched }
        .takeLast(maxInProgress)

    // Saved titles go to the Trakt watchlist; adding there is idempotent, so a missed match only re-offers it.
    val libraryByKey = linkedMapOf<String, SavedLibraryItem>()
    nuvioLibrary.sortedByDescending(SavedLibraryItem::addedAt).forEach { item ->
        val keys = libraryMatchKeys(item.id, item.type)
        if (keys.isEmpty()) {
            unsupported += "library:${item.id.trim()}"
            return@forEach
        }
        if (keys.any(traktLibraryKeys::contains)) return@forEach
        // The same title saved twice in Nuvio (e.g. under an imdb and a tmdb id) is sent once.
        if (keys.any(libraryByKey::containsKey)) return@forEach
        keys.forEach { key -> libraryByKey[key] = item }
    }

    return NuvioToTraktPlan(
        history = historyByIdentity.values.sortedBy(WatchedItem::watchedAt),
        inProgress = inProgress,
        library = libraryByKey.values.distinct(),
        skippedUnsupportedIds = unsupported.size
    )
}

private fun isCompleted(record: CloudProgressRecord): Boolean =
    record.duration > 0L && record.position.toDouble() / record.duration.toDouble() >= COMPLETED_FRACTION
