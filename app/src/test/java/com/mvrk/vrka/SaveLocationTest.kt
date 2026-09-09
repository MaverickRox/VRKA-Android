package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveLocationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveLocationModeEnumContainsExpectedOptions() {
        val modes = SaveLocationMode.entries.map { it.name }
        assertEquals(listOf("REMEMBER_LOCATION", "ASK_EVERY_TIME"), modes)
    }

    @Test
    fun downloadRequestPreservesDestinationTreeUri() {
        val request = DownloadRequest(
            url = "https://example.com/video",
            destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
        )
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            request.destinationTreeUri,
        )
    }

    @Test
    fun jobStorePersistsAndRestoresDestinationTreeUri() {
        val file = File(tempFolder.root, "jobs_saf.json")
        val store = JobStore(file)

        val job = DownloadJob(
            id = "saf-job-1",
            request = DownloadRequest(
                url = "https://example.com/video",
                destinationTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
            ),
            state = JobState.DONE,
            title = "SAF Test Job",
        )

        store.save(listOf(job))
        val loaded = store.load()

        assertEquals(1, loaded.size)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADownloads",
            loaded.first().request.destinationTreeUri,
        )
    }

    @Test
    fun jobStoreHandlesNullDestinationTreeUriGracefully() {
        val file = File(tempFolder.root, "jobs_no_saf.json")
        val store = JobStore(file)

        val job = DownloadJob(
            id = "saf-job-2",
            request = DownloadRequest(
                url = "https://example.com/video",
                destinationTreeUri = null,
            ),
            state = JobState.DONE,
            title = "No SAF Job",
        )

        store.save(listOf(job))
        val loaded = store.load()

        assertEquals(1, loaded.size)
        assertNull(loaded.first().request.destinationTreeUri)
    }
}
