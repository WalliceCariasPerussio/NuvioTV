package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioToTraktPlanTest {

    @Test
    fun `episode watched only in the Nuvio cloud goes to Trakt history with its original date`() {
        val plan = plan(
            watched = listOf(watched("tt0898266", season = 1, episode = 7, at = 500L)),
            traktWatchedKeys = setOf(watchedKey("series", "tt0898266", 1, 6))
        )

        assertEquals(listOf(Triple("tt0898266", 1, 7)), plan.history.map { Triple(it.contentId, it.season, it.episode) })
        assertEquals(500L, plan.history.single().watchedAt)
        assertTrue(plan.inProgress.isEmpty())
    }

    @Test
    fun `items Trakt already has are not sent again`() {
        val plan = plan(
            watched = listOf(
                watched("tt0898266", season = 1, episode = 6, at = 500L),
                watched("tmdb:1418", season = 1, episode = 5, at = 400L),
                watched("tt0111161", type = "movie", at = 300L)
            ),
            traktWatchedKeys = setOf(
                watchedKey("series", "tt0898266", 1, 6),
                watchedKey("series", "tmdb:1418", 1, 5),
                watchedKey("movie", "tt0111161", null, null)
            )
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `ids differing only in case or spaces still match Trakt`() {
        val plan = plan(
            watched = listOf(
                watched(" TMDB:1418 ", season = 1, episode = 5, at = 400L),
                watched("Trakt:123", type = "movie", at = 300L)
            ),
            traktWatchedKeys = setOf(
                watchedKey("series", "tmdb:1418", 1, 5),
                watchedKey("movie", "trakt:123", null, null)
            )
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `history sent moments ago is not offered again while Trakt cannot recognise it`() {
        val item = watched("tt13293588", season = 2, episode = 24, at = 500L)

        val plan = plan(watched = listOf(item), recentlySentHistory = setOf(historyIdentity(item)))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `anime ids Trakt cannot confirm are counted and left out`() {
        val plan = plan(
            watched = listOf(watched("kitsu:5045", season = 1, episode = 3, at = 500L)),
            progress = listOf(progress("mal:21", season = 1, episode = 4, position = 600_000L, at = 600L))
        )

        assertTrue(plan.isEmpty)
        assertEquals(2, plan.skippedUnsupportedIds)
    }

    @Test
    fun `completed progress counts as watched and one row per episode is sent`() {
        val plan = plan(
            watched = listOf(watched("tt0898266", season = 1, episode = 8, at = 700L, title = "The Big Bang Theory")),
            progress = listOf(
                progress("tt0898266", season = 1, episode = 8, position = 1_400_000L, at = 690L),
                progress("tt0898266", season = 1, episode = 9, position = 1_400_000L, at = 800L)
            )
        )

        assertEquals(
            listOf(Triple(8, 700L, "The Big Bang Theory"), Triple(9, 800L, "tt0898266")),
            plan.history.map { Triple(it.episode, it.watchedAt, it.title) }
        )
        assertTrue(plan.inProgress.isEmpty())
    }

    @Test
    fun `in-progress rows newer than Trakt are resumed oldest first, one per title`() {
        val plan = plan(
            progress = listOf(
                progress("tt13293588", season = 1, episode = 23, position = 600_000L, at = 900L),
                progress("tt0898266", season = 1, episode = 7, position = 300_000L, at = 950L),
                progress("tt0898266", season = 1, episode = 8, position = 300_000L, at = 1_000L)
            ),
            latestActivity = mapOf("tt13293588" to 100L, "tt0898266" to 100L)
        )

        assertEquals(
            listOf("tt13293588" to 23, "tt0898266" to 8),
            plan.inProgress.map { it.record.contentId to it.record.episode }
        )
        assertEquals(600_000.0 * 100.0 / 1_440_000.0, plan.inProgress.first().progressPercent, 0.0001)
    }

    @Test
    fun `in-progress rows already superseded are skipped`() {
        val plan = plan(
            watched = listOf(watched("tt3", season = 1, episode = 5, at = 2_000L)),
            progress = listOf(
                // Trakt has something newer for this title.
                progress("tt1", season = 1, episode = 2, position = 600_000L, at = 500L),
                // The episode itself is already watched on Trakt.
                progress("tt2", season = 1, episode = 2, position = 600_000L, at = 900L),
                // A later episode was finished in Nuvio afterwards.
                progress("tt3", season = 1, episode = 4, position = 600_000L, at = 1_500L),
                // Outside the Continue Watching window.
                progress("tt4", season = 1, episode = 1, position = 600_000L, at = 10L)
            ),
            traktWatchedKeys = setOf(watchedKey("series", "tt2", 1, 2)),
            latestActivity = mapOf("tt1" to 800L),
            cutoff = 100L
        )

        assertTrue(plan.inProgress.isEmpty())
        assertEquals(listOf("tt3"), plan.history.map { it.contentId })
    }

    @Test
    fun `pause Trakt already holds at the same point is not sent again`() {
        val plan = plan(
            progress = listOf(
                progress("tt2085059", season = 1, episode = 1, position = 578_000L, at = 900L),
                progress("tt14476236", season = 1, episode = 1, position = 900_000L, at = 900L)
            ),
            latestActivity = mapOf("tt2085059" to 100L, "tt14476236" to 100L),
            pausedPercent = mapOf(
                pausedProgressKey("tt2085059", 1, 1) to 40.2,
                // Trakt is behind here, so the newer point still goes.
                pausedProgressKey("tt14476236", 1, 1) to 34.1
            )
        )

        assertEquals(listOf("tt14476236"), plan.inProgress.map { it.record.contentId })
    }

    @Test
    fun `accidental opens are dropped without hiding the episode really in progress`() {
        val plan = plan(
            progress = listOf(
                progress("tt13293588", season = 3, episode = 2, position = 650_000L, at = 900L),
                // Opened the next episode for a few seconds afterwards.
                progress("tt13293588", season = 3, episode = 3, position = 5_000L, at = 950L),
                progress("tt33764258", type = "movie", position = 7_000L, at = 960L)
            )
        )

        assertEquals(
            listOf(Triple("tt13293588", 3, 2)),
            plan.inProgress.map { Triple(it.record.contentId, it.record.season, it.record.episode) }
        )
    }

    @Test
    fun `progress between 80 and 90 percent is resumed below the completed line`() {
        val plan = plan(progress = listOf(progress("tt1", season = 1, episode = 1, position = 1_250_000L, at = 900L)))

        // 86.8%: still in progress for Nuvio, so it must be sent as a pause, never as watched.
        assertEquals(1, plan.inProgress.size)
        assertTrue(plan.inProgress.single().progressPercent < 90.0)
        assertTrue(plan.history.isEmpty())
    }

    @Test
    fun `only the most recent in-progress rows are kept`() {
        val plan = plan(
            progress = (1..5).map { index -> progress("tt$index", type = "movie", position = 600_000L, at = index * 100L) },
            maxInProgress = 2
        )

        assertEquals(listOf("tt4", "tt5"), plan.inProgress.map { it.record.contentId })
    }

    @Test
    fun `library titles missing on Trakt go to the watchlist, newest saved first`() {
        val plan = plan(
            library = listOf(
                saved("tt0111161", type = "movie", at = 100L),
                saved("tt0898266", type = "series", at = 300L)
            )
        )

        assertEquals(listOf("tt0898266", "tt0111161"), plan.library.map { it.id })
        assertTrue(plan.history.isEmpty() && plan.inProgress.isEmpty())
    }

    @Test
    fun `library titles Trakt holds under another id form are not sent`() {
        val plan = plan(
            library = listOf(saved("TMDB:1418", type = "series", at = 300L)),
            traktLibraryKeys = libraryMatchKeys("tt0898266", "series", tmdbId = 1418)
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `library titles saved twice or with anime ids only are handled once or skipped`() {
        val plan = plan(
            library = listOf(
                saved("tt13293588", type = "series", at = 300L),
                saved("tt13293588", type = "series", at = 200L),
                saved("kitsu:43731", type = "series", at = 100L)
            )
        )

        assertEquals(listOf("tt13293588"), plan.library.map { it.id })
        assertEquals(1, plan.skippedUnsupportedIds)
    }

    private fun plan(
        watched: List<WatchedItem> = emptyList(),
        progress: List<CloudProgressRecord> = emptyList(),
        traktWatchedKeys: Set<String> = emptySet(),
        latestActivity: Map<String, Long> = emptyMap(),
        cutoff: Long? = null,
        pausedPercent: Map<String, Double> = emptyMap(),
        recentlySentHistory: Set<String> = emptySet(),
        library: List<SavedLibraryItem> = emptyList(),
        traktLibraryKeys: Set<String> = emptySet(),
        maxInProgress: Int = NUVIO_TO_TRAKT_MAX_IN_PROGRESS
    ) = planNuvioToTraktSync(
        nuvioWatched = watched,
        nuvioProgress = progress,
        traktWatchedKeys = traktWatchedKeys,
        traktLatestActivityByContentId = latestActivity,
        inProgressCutoffEpochMs = cutoff,
        traktPausedPercentByKey = pausedPercent,
        recentlySentHistory = recentlySentHistory,
        nuvioLibrary = library,
        traktLibraryKeys = traktLibraryKeys,
        maxInProgress = maxInProgress
    )

    private fun watched(
        id: String,
        type: String = "series",
        season: Int? = null,
        episode: Int? = null,
        at: Long,
        title: String = id
    ) = WatchedItem(
        contentId = id,
        contentType = type,
        title = title,
        season = season,
        episode = episode,
        watchedAt = at
    )

    private fun progress(
        contentId: String,
        type: String = "series",
        season: Int? = null,
        episode: Int? = null,
        position: Long,
        at: Long
    ) = CloudProgressRecord(
        contentId = contentId,
        contentType = type,
        videoId = if (season != null) "$contentId:$season:$episode" else contentId,
        season = season,
        episode = episode,
        position = position,
        duration = 1_440_000L,
        lastWatched = at
    )

    private fun saved(id: String, type: String, at: Long) = SavedLibraryItem(
        id = id,
        type = type,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        addedAt = at
    )
}
