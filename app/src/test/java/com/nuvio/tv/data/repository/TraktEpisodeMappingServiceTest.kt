package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TraktEpisodeMappingServiceTest {
    @Test
    fun `episodes the addon has keep their numbers when another season's count differs`() = runTest {
        // The Daily Show: Trakt and Cinemeta disagree on season 1's episode count, which used
        // to shift every later episode through the whole-show index fallback.
        val traktApi = traktApi(traktSeasons = listOf(61, 160))
        val service = service(traktApi, addonSeasons = listOf(65, 160))

        assertNull(service.resolveAddonEpisodeMapping("tt0115147", "series", season = 2, episode = 1))
        coVerify(exactly = 0) { traktApi.getShowSeasons(any(), any(), any()) }
    }

    @Test
    fun `episodes missing from the addon are still remapped`() = runTest {
        val service = service(traktApi(traktSeasons = listOf(48)), addonSeasons = listOf(24, 24))

        val mapped = service.resolveAddonEpisodeMapping("tt1", "series", season = 1, episode = 35)

        assertEquals(2, mapped?.season)
        assertEquals(11, mapped?.episode)
    }

    @Test
    fun `written episodes keep their numbers when Trakt has them and an earlier season's count differs`() = runTest {
        // The Block: Trakt lists two more episodes than the addon before the current season and
        // room-week titles repeat every season, so the whole-show index used to mark the
        // episode two places earlier on Trakt (S2E15 -> S2E13 here).
        val roomWeek = { _: Int, episode: Int -> "Room Week ($episode)" }
        val service = service(
            traktApi(traktSeasons = listOf(15, 50), title = roomWeek),
            addonSeasons = listOf(13, 50),
            addonTitle = roomWeek
        )

        val mapped = service.resolveEpisodeMapping("tt0418372", "series", videoId = null, season = 2, episode = 15)

        assertEquals(2, mapped?.season)
        assertEquals(15, mapped?.episode)
    }

    @Test
    fun `written episodes Trakt lacks are still remapped`() = runTest {
        val service = service(traktApi(traktSeasons = listOf(48)), addonSeasons = listOf(24, 24))

        val mapped = service.resolveEpisodeMapping("tt1", "series", videoId = null, season = 2, episode = 11)

        assertEquals(1, mapped?.season)
        assertEquals(35, mapped?.episode)
    }

    @Test
    fun `a unique title match still wins over the same number when writing`() = runTest {
        // Seasons split differently: the addon's S2E3 is Trakt's S2E1, and Trakt's S2E3 is a
        // different episode.
        val service = service(
            traktApi(traktSeasons = listOf(7, 3), title = chapterTitles(listOf(7, 3))),
            addonSeasons = listOf(5, 5),
            addonTitle = chapterTitles(listOf(5, 5))
        )

        val mapped = service.resolveEpisodeMapping("tt2", "series", videoId = null, season = 2, episode = 3)

        assertEquals(2, mapped?.season)
        assertEquals(1, mapped?.episode)
    }

    private fun chapterTitles(seasonCounts: List<Int>): (Int, Int) -> String = { season, episode ->
        "Chapter ${seasonCounts.take(season - 1).sum() + episode}"
    }

    private fun service(
        traktApi: TraktApi,
        addonSeasons: List<Int>,
        addonTitle: (season: Int, episode: Int) -> String? = { _, _ -> null }
    ): TraktEpisodeMappingService {
        val authService = mockk<TraktAuthService> {
            coEvery { getCurrentAuthState() } returns TraktAuthState(accessToken = "a", refreshToken = "r")
            coEvery { executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("Bearer a")
            }
        }
        val addonEpisodeListService = mockk<AddonEpisodeListService> {
            coEvery { episodes(any(), any()) } returns addonSeasons.flatMapIndexed { index, count ->
                (1..count).map { episode ->
                    EpisodeMappingEntry(season = index + 1, episode = episode, title = addonTitle(index + 1, episode))
                }
            }
        }
        return TraktEpisodeMappingService(traktApi, authService, addonEpisodeListService)
    }

    private fun traktApi(
        traktSeasons: List<Int>,
        title: (season: Int, episode: Int) -> String? = { _, _ -> null }
    ) = mockk<TraktApi> {
        coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(
            traktSeasons.mapIndexed { index, count ->
                TraktSeasonDto(
                    number = index + 1,
                    episodes = (1..count).map { episode ->
                        TraktEpisodeDto(title = title(index + 1, episode), season = index + 1, number = episode)
                    }
                )
            }
        )
    }

    @Test
    fun `a seasons fetch cancelled mid-request does not block the next mapping`() = runBlocking {
        val seasonsCalls = AtomicInteger()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val traktApi = mockk<TraktApi>()
        coEvery { traktApi.getShowSeasons(any(), any(), any()) } returns Response.success(
            listOf(traktSeason(1), traktSeason(2))
        )

        val authService = mockk<TraktAuthService>()
        coEvery { authService.getCurrentAuthState() } returns
            TraktAuthState(accessToken = "token", refreshToken = "refresh")
        coEvery { authService.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            if (seasonsCalls.incrementAndGet() == 1) {
                // The refresh or pipeline that owns this fetch is replaced while it waits.
                firstRequestStarted.complete(Unit)
                awaitCancellation()
            }
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer test")
        }

        val service = TraktEpisodeMappingService(traktApi, authService, AddonEpisodeListService(addonWithOneSeason()))

        val owner = launch {
            service.resolveAddonEpisodeMapping(SHOW_ID, "series", season = 2, episode = 1)
        }
        firstRequestStarted.await()
        owner.cancel()
        owner.join()

        val mapped = withTimeout(TIMEOUT_MS) {
            service.resolveAddonEpisodeMapping(SHOW_ID, "series", season = 2, episode = 1)
        }

        assertEquals(1, mapped?.season)
        assertEquals(13, mapped?.episode)
        assertEquals(2, seasonsCalls.get())
    }

    @Test
    fun `a mapping waiting on a fetch whose owner is cancelled is released`() = runBlocking {
        val seasonsCalls = AtomicInteger()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val traktApi = mockk<TraktApi>()
        coEvery { traktApi.getShowSeasons(any(), any(), any()) } returns Response.success(
            listOf(traktSeason(1), traktSeason(2))
        )
        val authService = mockk<TraktAuthService>()
        coEvery { authService.getCurrentAuthState() } returns
            TraktAuthState(accessToken = "token", refreshToken = "refresh")
        coEvery { authService.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            seasonsCalls.incrementAndGet()
            firstRequestStarted.complete(Unit)
            awaitCancellation()
        }
        val service = TraktEpisodeMappingService(traktApi, authService, AddonEpisodeListService(addonWithOneSeason()))

        val owner = launch { service.resolveAddonEpisodeMapping(SHOW_ID, "series", season = 2, episode = 1) }
        firstRequestStarted.await()
        val waiter = async { service.resolveAddonEpisodeMapping(SHOW_ID, "series", season = 2, episode = 1) }
        yield()
        owner.cancel()

        // The waiter gives up on the mapping for this round instead of hanging.
        assertNull(withTimeout(TIMEOUT_MS) { waiter.await() })
        assertEquals(1, seasonsCalls.get())
    }

    // The addon numbers the show as one season of 24 episodes; Trakt splits it in two.
    private fun addonWithOneSeason(): MetaRepository {
        val meta = mockk<Meta>()
        every { meta.videos } returns (1..24).map { episode ->
            Video(
                id = "kitsu:1:$episode",
                title = "Episode $episode",
                released = null,
                thumbnail = null,
                season = 1,
                episode = episode,
                overview = null
            )
        }
        val metaRepository = mockk<MetaRepository>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } returns
            flowOf(NetworkResult.Success(meta))
        return metaRepository
    }

    private fun traktSeason(number: Int) = TraktSeasonDto(
        number = number,
        episodes = (1..12).map { episode ->
            TraktEpisodeDto(title = "Episode ${(number - 1) * 12 + episode}", season = number, number = episode)
        }
    )

    private companion object {
        const val SHOW_ID = "tt0000001"
        const val TIMEOUT_MS = 5_000L
    }
}
