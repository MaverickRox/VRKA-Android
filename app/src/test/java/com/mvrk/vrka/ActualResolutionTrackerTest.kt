package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ActualResolutionTrackerTest {

    @Test
    fun markerIsParsedFromRealBeforeDlOutputShapes() {
        assertEquals(1080, ActualResolutionTracker.parseMarker("__VRKA_RES__1080"))
        assertEquals(2160, ActualResolutionTracker.parseMarker("__vrka_res__2160\n"))
        assertEquals(720, ActualResolutionTracker.parseMarker("[youtube] __VRKA_RES__720 extra"))
        assertNull(ActualResolutionTracker.parseMarker("__VRKA_RES__NA"))
        assertNull(ActualResolutionTracker.parseMarker("[download] 12.4% of 10MiB"))
    }

    @Test
    fun singleVideoReportsTheActuallyDownloadedHeight() {
        val tracker = ActualResolutionTracker()
        tracker.record(1080)
        assertEquals("1080p", tracker.summary(isPlaylist = false))
    }

    @Test
    fun playlistWithUniformResolutionDoesNotLookMixed() {
        val tracker = ActualResolutionTracker()
        tracker.record(720)
        tracker.record(720)
        assertEquals("720p", tracker.summary(isPlaylist = true))
    }

    @Test
    fun playlistWithMixedResolutionsReportsAnHonestRange() {
        val tracker = ActualResolutionTracker()
        tracker.record(2160)
        tracker.record(1080)
        tracker.record(360)
        assertEquals("360p–2160p", tracker.summary(isPlaylist = true))
        assertEquals(setOf(2160, 1080, 360), tracker.heights())
    }

    @Test
    fun invalidHeightsAndMissingMetadataDoNotInventAResolution() {
        val tracker = ActualResolutionTracker()
        tracker.record(null as Int?)
        tracker.record(0)
        tracker.record(-1)
        tracker.record("NA")
        assertNull(tracker.summary(isPlaylist = false))
    }

    @Test
    fun downloadCommandPrintsTheResolutionMarkerForVideoOnly() {
        val staging = File(System.getProperty("java.io.tmpdir"), "vrka-resolution-marker-test").apply { mkdirs() }
        val videoJob = DownloadJob(
            id = "video",
            request = DownloadRequest(url = "https://example.test/video", quality = VideoQuality.P1080),
        )
        val videoCommand = DownloadRequestFactory.download(videoJob, staging).buildCommand().toList()
        assertTrue(
            "video transfer must print the actual height",
            videoCommand.windowed(2).any { (option, value) ->
                option == "--print" && value == "before_dl:${ActualResolutionTracker.MARKER}%(height)s"
            },
        )

        val audioJob = DownloadJob(
            id = "audio",
            request = DownloadRequest(
                url = "https://example.test/audio",
                mode = MediaMode.AUDIO,
                audioFormat = AudioFormat.OPUS,
            ),
        )
        val audioCommand = DownloadRequestFactory.download(audioJob, staging).buildCommand().toList()
        assertTrue(
            "audio-only transfers have no video height marker",
            audioCommand.none { it.contains(ActualResolutionTracker.MARKER) },
        )
    }
}
