package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonDto
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

        val service = TraktEpisodeMappingService(traktApi, authService, addonWithOneSeason())

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
        val service = TraktEpisodeMappingService(traktApi, authService, addonWithOneSeason())

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
