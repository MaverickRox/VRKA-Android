package com.mvrk.vrka

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSelectionCoordinatorTest {

    @Test
    fun rapidQualitySelectionAdvancesGenerationAndCancelsPrior() = runBlocking {
        val coordinator = StreamSelectionCoordinator(scope = CoroutineScope(Dispatchers.Default))

        val targetUrl = "https://example.com/video"

        // 1. Initial selection: 720p with a slow resolver
        coordinator.selectQuality(
            url = targetUrl,
            quality = VideoQuality.P720,
            resolveAction = { gen ->
                delay(300)
                ResolvedStreamInfo(
                    generationId = gen,
                    resolvedQuality = VideoQuality.P720,
                    actualHeight = 720,
                )
            },
        )
        delay(50)

        val gen1 = coordinator.session.value.generationId
        assertEquals(VideoQuality.P720, coordinator.session.value.quality)

        // 2. User rapidly changes selection to 1080p before slow resolution completes
        coordinator.selectQuality(
            url = targetUrl,
            quality = VideoQuality.P1080,
            resolveAction = { gen ->
                ResolvedStreamInfo(
                    generationId = gen,
                    resolvedQuality = VideoQuality.P1080,
                    actualHeight = 1080,
                )
            },
        )
        delay(100)

        val gen2 = coordinator.session.value.generationId
        assertTrue(gen2 > gen1)
        assertEquals(VideoQuality.P1080, coordinator.session.value.quality)
        assertEquals(StreamSelectionState.RESOLVED, coordinator.session.value.state)
        assertEquals(1080, coordinator.session.value.resolvedInfo?.actualHeight)
    }

    @Test
    fun rejectsStaleAsynchronousCallbacksFromOlderGenerations() = runBlocking {
        val coordinator = StreamSelectionCoordinator(scope = CoroutineScope(Dispatchers.Default))

        coordinator.selectQuality(url = "https://example.com/video", quality = VideoQuality.P720)
        delay(50)
        val oldGen = coordinator.session.value.generationId

        // Move to next generation
        coordinator.selectQuality(url = "https://example.com/video", quality = VideoQuality.P2160)
        delay(50)
        val newGen = coordinator.session.value.generationId
        assertNotEquals(oldGen, newGen)

        // Simulate delayed callback arriving from oldGen
        coordinator.onResolutionComplete(
            generationId = oldGen,
            result = ResolvedStreamInfo(
                generationId = oldGen,
                resolvedQuality = VideoQuality.P720,
                actualHeight = 720,
            ),
        )
        delay(50)

        // Session must STILL reflect 2160p, NOT 720p
        assertEquals(VideoQuality.P2160, coordinator.session.value.quality)
        assertNull(coordinator.session.value.resolvedInfo)
    }

    @Test
    fun commitBoundaryCreatesImmutableSnapshotUnaffectedBySubsequentUIChanges() = runBlocking {
        val coordinator = StreamSelectionCoordinator(scope = CoroutineScope(Dispatchers.Default))

        val targetUrl = "https://example.com/watch"
        coordinator.selectQuality(url = targetUrl, quality = VideoQuality.P1080)
        delay(50)

        val baseRequest = DownloadRequest(
            url = targetUrl,
            mode = MediaMode.VIDEO,
            quality = VideoQuality.P1080,
        )

        // Commit the request
        val committedRequest = coordinator.commit(baseRequest)
        assertEquals(VideoQuality.P1080, committedRequest.quality)
        assertEquals(StreamSelectionState.QUEUED, coordinator.session.value.state)

        // Subsequent user interaction changes UI to 480p
        coordinator.selectQuality(url = targetUrl, quality = VideoQuality.P480)
        delay(50)

        // The already committed request must NOT be modified
        assertEquals(VideoQuality.P1080, committedRequest.quality)
        assertEquals(VideoQuality.P480, coordinator.session.value.quality)
    }

    @Test
    fun cancellationTransitionsToCancelledState() = runBlocking {
        val coordinator = StreamSelectionCoordinator(scope = CoroutineScope(Dispatchers.Default))

        coordinator.selectQuality(url = "https://example.com/video", quality = VideoQuality.P1440)
        delay(50)
        assertEquals(VideoQuality.P1440, coordinator.session.value.quality)

        coordinator.cancelResolution()
        delay(50)
        assertEquals(StreamSelectionState.CANCELLED, coordinator.session.value.state)
    }

    @Test
    fun commitDoesNotCarryOverResolvedMediaUrlForDifferentTargetUrl() = runBlocking {
        val coordinator = StreamSelectionCoordinator(scope = CoroutineScope(Dispatchers.Default))

        val url1 = "https://example.com/video1"
        val url2 = "https://youtube.com/watch?v=dune"

        // Set up url1 with resolved media URL
        coordinator.selectQuality(
            url = url1,
            quality = VideoQuality.P1080,
            resolveAction = { gen ->
                ResolvedStreamInfo(
                    generationId = gen,
                    resolvedQuality = VideoQuality.P1080,
                    mediaUrl = "https://example.com/stream.m3u8",
                )
            },
        )
        delay(100)
        assertEquals("https://example.com/stream.m3u8", coordinator.session.value.resolvedInfo?.mediaUrl)

        // Now commit a request for url2 (e.g. Dune trailer)
        val request2 = DownloadRequest(url = url2)
        val committed = coordinator.commit(request2)

        // committed request for url2 MUST NOT inherit url1's resolvedMediaUrl
        assertNull("Committed request for new URL must not inherit stale resolvedMediaUrl", committed.resolvedMediaUrl)
    }
}
