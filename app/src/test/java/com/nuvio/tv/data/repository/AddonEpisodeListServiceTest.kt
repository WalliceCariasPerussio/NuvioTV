package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: a cancelled episode-list fetch must not leave the show stuck (Continue Watching, 2026-10-22). */
class AddonEpisodeListServiceTest {
    @Test
    fun `a fetch cancelled mid-request does not block the next one`() = runBlocking {
        val calls = AtomicInteger()
        val firstRequestStarted = CompletableDeferred<Unit>()
        val metaRepository = mockk<MetaRepository>()
        every { metaRepository.getMetaFromAllAddons(any(), any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                flow<NetworkResult<Meta>> {
                    // The refresh that owns this fetch is replaced while it waits.
                    firstRequestStarted.complete(Unit)
                    awaitCancellation()
                }
            } else {
                flowOf(NetworkResult.Success(oneEpisodeMeta()))
            }
        }
        val service = AddonEpisodeListService(metaRepository)

        var ownerFinishedNormally = false
        val owner = launch {
            service.episodes("tt0000001", "series")
            ownerFinishedNormally = true
        }
        firstRequestStarted.await()
        owner.cancel()
        owner.join()

        val episodes = withTimeout(5_000L) { service.episodes("tt0000001", "series") }

        assertEquals(1, episodes.size)
        // The cancelled owner stops instead of going on with an empty list.
        assertTrue(!ownerFinishedNormally)
    }

    private fun oneEpisodeMeta(): Meta {
        val meta = mockk<Meta>()
        every { meta.videos } returns listOf(
            Video(
                id = "tt0000001:1:1",
                title = "Episode 1",
                released = null,
                thumbnail = null,
                season = 1,
                episode = 1,
                overview = null
            )
        )
        return meta
    }
}
