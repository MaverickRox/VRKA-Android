package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationActualResolutionTest {

    @Test
    fun singleVideoShowsActualHeightAndRequestedCap() {
        val job = DownloadJob(
            id = "single",
            request = DownloadRequest(url = "https://example.test/v", quality = VideoQuality.P2160),
            actualResolution = "1080p",
        )
        assertEquals("Video • 1080p (requested: 2160p)", jobMediaSummary(job))
    }

    @Test
    fun bestAvailableReportsTheActualHeight() {
        val job = DownloadJob(
            id = "best",
            request = DownloadRequest(url = "https://example.test/v"),
            actualResolution = "720p",
        )
        assertEquals("Video • 720p (Best available)", jobMediaSummary(job))
    }

    @Test
    fun mixedPlaylistResolutionIsNeverReportedAsOneItem() {
        val job = DownloadJob(
            id = "playlist",
            request = DownloadRequest(
                url = "https://example.test/list",
                quality = VideoQuality.P2160,
                isPlaylist = true,
            ),
            actualResolution = "360p–2160p",
        )
        assertEquals("Video • 360p–2160p (requested: 2160p)", jobMediaSummary(job))
    }
}
