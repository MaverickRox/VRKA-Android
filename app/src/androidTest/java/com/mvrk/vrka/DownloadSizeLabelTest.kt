package com.mvrk.vrka

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Runtime check that queue cards surface localized byte counts in every transfer state. */
@RunWith(AndroidJUnit4::class)
class DownloadSizeLabelTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun job(
        state: JobState,
        downloaded: Long?,
        total: Long?,
        exact: Boolean = true,
    ) = DownloadJob(
        id = "size-label",
        request = DownloadRequest(url = "https://example.com/video.mp4"),
        state = state,
        downloadedBytes = downloaded,
        totalBytes = total,
        totalIsExact = exact,
    )

    private val mib = 1024L * 1024L

    @Test
    fun activeJobShowsDownloadedOfTotal() {
        assertEquals(
            "Downloaded 12.4 MiB / 48.2 MiB",
            downloadSizeLabel(context, job(JobState.DOWNLOADING, (12.4 * mib).toLong(), (48.2 * mib).toLong())),
        )
    }

    @Test
    fun approximateTotalIsMarkedApproximate() {
        assertEquals(
            "Downloaded 12.4 MiB / ~48.2 MiB",
            downloadSizeLabel(
                context,
                job(JobState.DOWNLOADING, (12.4 * mib).toLong(), (48.2 * mib).toLong(), exact = false),
            ),
        )
    }

    @Test
    fun unknownTotalKeepsDownloadedBytes() {
        assertEquals(
            "Downloaded 12.4 MiB / calculating size",
            downloadSizeLabel(context, job(JobState.DOWNLOADING, (12.4 * mib).toLong(), null)),
        )
    }

    @Test
    fun completedJobShowsFinalSize() {
        assertEquals(
            "Completed · 48.2 MiB",
            downloadSizeLabel(context, job(JobState.DONE, (48.2 * mib).toLong(), (48.2 * mib).toLong())),
        )
    }

    @Test
    fun unknownSizeHidesTheRowInsteadOfGuessing() {
        assertNull(downloadSizeLabel(context, job(JobState.FAILED, null, null)))
    }
}
