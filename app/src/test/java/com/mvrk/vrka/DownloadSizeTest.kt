package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSizeTest {
    private fun job(
        state: JobState = JobState.DOWNLOADING,
        downloaded: Long? = null,
        reported: Long? = null,
        exact: Boolean = false,
        estimated: Long? = null,
        estimateAccuracy: SizeEstimateKind = SizeEstimateKind.APPROXIMATE,
        actualFinal: Long? = null,
    ) = DownloadJob(
        id = "job",
        request = DownloadRequest(url = "https://example.com/video"),
        state = state,
        downloadedBytes = downloaded,
        estimatedTotalBytes = estimated,
        estimatedSizeAccuracy = estimateAccuracy,
        reportedTotalBytes = reported,
        reportedTotalIsExact = exact,
        actualFinalFileBytes = actualFinal,
    )

    @Test
    fun parsesExactProgressLine() {
        val update = DownloadSizeParser.parseProgressLine(
            "[download]  25.0% of  48.20MiB at   5.00MiB/s ETA 00:07",
        )

        assertEquals(48.2 * 1024 * 1024, update!!.totalBytes.toDouble(), 1.0)
        assertEquals(update.totalBytes / 4.0, update.downloadedBytes.toDouble(), 2.0)
        assertTrue(update.totalIsExact)
    }

    @Test
    fun parsesApproximateTotalAndMarksIt() {
        val update = DownloadSizeParser.parseProgressLine(
            "[download]  10.0% of ~ 100.00MiB at  Unknown B/s ETA Unknown (frag 1/10)",
        )!!

        assertEquals(100L * 1024 * 1024, update.totalBytes)
        assertFalse(update.totalIsExact)
    }

    @Test
    fun parsesCompletedLineWithoutSpeed() {
        val update = DownloadSizeParser.parseProgressLine("[download] 100% of 10.00MiB in 00:00:03 at 3.33MiB/s")!!

        assertEquals(10L * 1024 * 1024, update.downloadedBytes)
        assertEquals(update.totalBytes, update.downloadedBytes)
        assertTrue(update.totalIsExact)
    }

    @Test
    fun ignoresNonProgressLines() {
        assertNull(DownloadSizeParser.parseProgressLine("[download] Destination: video.mp4"))
        assertNull(DownloadSizeParser.parseProgressLine("[debug] Command-line config: ['-f', 'bv']"))
        assertNull(DownloadSizeParser.parseProgressLine("__VRKA_TITLE__ Sample title"))
        assertNull(DownloadSizeParser.parseProgressLine("[download]  50.0% of 0MiB"))
    }

    @Test
    fun byteSizesAreClampedToTheirTotal() {
        val update = DownloadSizeParser.parseProgressLine("[download] 250% of 1.00MiB")!!

        assertEquals(update.totalBytes, update.downloadedBytes)
    }

    @Test
    fun parsesHumanReadableSizes() {
        assertEquals(1024L, DownloadSizeParser.parseSize("1.00KiB"))
        assertEquals(5L * 1024 * 1024, DownloadSizeParser.parseSize("5MiB"))
        assertEquals(2L * 1000 * 1000, DownloadSizeParser.parseSize("2 MB"))
        assertEquals(1L, DownloadSizeParser.parseSize("1B"))
        assertNull(DownloadSizeParser.parseSize("unknown"))
    }

    @Test
    fun formatsBinaryUnitsWithOneDecimalForMiB() {
        assertEquals("48.2 MiB", formatDownloadBytes((48.2 * 1024 * 1024).toLong()))
        assertEquals("1.50 GiB", formatDownloadBytes((1.5 * 1024 * 1024 * 1024).toLong()))
        assertEquals("512 KiB", formatDownloadBytes(512L * 1024))
        assertEquals("999 B", formatDownloadBytes(999))
        assertEquals("0 B", formatDownloadBytes(0))
    }

    @Test
    fun displayShowsDownloadedOfTotalWhileActive() {
        val display = downloadSizeDisplay(job(downloaded = 12_400_000, reported = 48_200_000, exact = true))

        assertTrue(display is DownloadSizeDisplay.DownloadedOfTotal)
        display as DownloadSizeDisplay.DownloadedOfTotal
        assertEquals(12_400_000L, display.downloadedBytes)
        assertEquals(48_200_000L, display.totalBytes)
        assertTrue(display.exact)
    }

    @Test
    fun displayKeepsDownloadedBytesWhenTotalIsUnknown() {
        val display = downloadSizeDisplay(job(downloaded = 3_000_000))

        assertTrue(display is DownloadSizeDisplay.CalculatingTotal)
        assertEquals(3_000_000L, (display as DownloadSizeDisplay.CalculatingTotal).downloadedBytes)
    }

    @Test
    fun displaySaysCalculatingBeforeAnyBytes() {
        assertTrue(downloadSizeDisplay(job()) is DownloadSizeDisplay.Calculating)
    }

    @Test
    fun queuedJobWithoutEstimateSaysUnavailableInsteadOfCalculating() {
        assertTrue(downloadSizeDisplay(job(state = JobState.QUEUED)) is DownloadSizeDisplay.Unavailable)
    }

    @Test
    fun queuedJobShowsThePreflightEstimateBeforeAnyBytesMove() {
        val display = downloadSizeDisplay(
            job(state = JobState.QUEUED, estimated = 171L * 1024 * 1024),
        )

        assertTrue(display is DownloadSizeDisplay.Total)
        display as DownloadSizeDisplay.Total
        assertEquals(171L * 1024 * 1024, display.bytes)
        assertFalse(display.exact)
    }

    @Test
    fun exactEstimateIsNotMarkedApproximate() {
        val display = downloadSizeDisplay(
            job(
                state = JobState.QUEUED,
                estimated = 80_000_000L,
                estimateAccuracy = SizeEstimateKind.EXACT,
            ),
        )

        assertTrue((display as DownloadSizeDisplay.Total).exact)
    }

    @Test
    fun activeTransferPrefersReportedTotalOverTheEstimate() {
        val display = downloadSizeDisplay(
            job(downloaded = 40_000_000, reported = 80_000_000, exact = true, estimated = 171_000_000),
        )

        display as DownloadSizeDisplay.DownloadedOfTotal
        assertEquals(80_000_000L, display.totalBytes)
        assertTrue(display.exact)
    }

    @Test
    fun completedJobShowsExactSize() {
        val display = downloadSizeDisplay(job(state = JobState.DONE, downloaded = 48_200_000, reported = 48_200_000, exact = true))

        assertEquals(48_200_000L, (display as DownloadSizeDisplay.Completed).bytes)
    }

    @Test
    fun failedJobKeepsTransferredBytes() {
        val display = downloadSizeDisplay(job(state = JobState.FAILED, downloaded = 9_000_000))

        assertEquals(9_000_000L, (display as DownloadSizeDisplay.Downloaded).bytes)
    }

    @Test
    fun completedJobWithoutDataHidesTheRow() {
        assertTrue(downloadSizeDisplay(job(state = JobState.DONE)) is DownloadSizeDisplay.Hidden)
    }

    @Test
    fun completedJobReportsThePublishedSizeNotThePreMergeEstimate() {
        val estimated = 171L * 1024 * 1024
        val published = 80L * 1024 * 1024

        val display = downloadSizeDisplay(
            job(
                state = JobState.DONE,
                downloaded = estimated,
                reported = estimated,
                estimated = estimated,
                actualFinal = published,
            ),
        )

        assertEquals(published, (display as DownloadSizeDisplay.Completed).bytes)
    }

    @Test
    fun completedJobFallsBackToTransferTotalWhenTheProviderSizeIsMissing() {
        val display = downloadSizeDisplay(
            job(state = JobState.DONE, downloaded = 12_000_000, reported = 12_000_000, exact = true),
        )

        assertEquals(12_000_000L, (display as DownloadSizeDisplay.Completed).bytes)
    }

    @Test
    fun mergedStreamTotalIsTheSumAndNeverShrinksBackToTheCurrentStream() {
        val tracker = DownloadSizeTracker()
        val videoTotal = 171L * 1024 * 1024
        val audioTotal = 4L * 1024 * 1024

        val videoStart = tracker.update(ProgressSizeUpdate(videoTotal / 2, videoTotal, true))
        assertEquals(videoTotal, videoStart.totalBytes)

        val videoMore = tracker.update(ProgressSizeUpdate(videoTotal, videoTotal, true))
        assertEquals(videoTotal, videoMore.totalBytes)

        val audioStart = tracker.update(ProgressSizeUpdate(audioTotal / 2, audioTotal, true))
        assertEquals(videoTotal + audioTotal, audioStart.totalBytes)
        assertEquals(videoTotal + audioTotal / 2, audioStart.downloadedBytes)
        assertFalse("A merged container cannot be byte-exact", audioStart.totalIsExact)
    }

    @Test
    fun repeatedSamplesOfOneStreamAreNotCountedTwice() {
        val tracker = DownloadSizeTracker()
        val videoTotal = 100L * 1024 * 1024

        tracker.update(ProgressSizeUpdate(videoTotal / 4, videoTotal, true))
        tracker.update(ProgressSizeUpdate(videoTotal / 2, videoTotal, true))
        val done = tracker.update(ProgressSizeUpdate(videoTotal, videoTotal, true))

        assertEquals(videoTotal, done.totalBytes)
        assertEquals(videoTotal, done.downloadedBytes)
        assertTrue(done.totalIsExact)
    }

    @Test
    fun trackerResetsForANewAttempt() {
        val tracker = DownloadSizeTracker()
        tracker.update(ProgressSizeUpdate(50L * 1024 * 1024, 100L * 1024 * 1024, true))
        tracker.reset()

        val afterReset = tracker.update(ProgressSizeUpdate(10_000_000, 20_000_000, true))

        assertEquals(20_000_000L, afterReset.totalBytes)
        assertEquals(10_000_000L, afterReset.downloadedBytes)
        assertTrue(afterReset.totalIsExact)
    }
}
