package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNotificationTest {
    @Test
    fun notificationIdsAreStablePerJob() {
        val id = notificationIdFor("job-alpha")

        assertEquals(id, notificationIdFor("job-alpha"))
        assertTrue(id in NOTIFICATION_ID_BASE..(NOTIFICATION_ID_BASE + 0xFFFF))
        assertNotEquals(PENDING_NOTIFICATION_ID, id)
        assertNotEquals(INTERRUPTED_NOTIFICATION_ID, id)
    }

    @Test
    fun differentJobsGetDifferentNotificationIds() {
        val ids = listOf("job-alpha", "job-beta", "job-gamma", "share-42")
            .map(::notificationIdFor)

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun promotionIsOnlyRequestedOnLiveUpdatePlatformsWithPermission() {
        assertFalse(shouldRequestPromotion(35, 3_500_000, canPostPromoted = true))
        assertFalse(shouldRequestPromotion(36, 3_600_000, canPostPromoted = true))
        assertFalse(shouldRequestPromotion(36, 3_600_001, canPostPromoted = false))
        assertTrue(shouldRequestPromotion(36, 3_600_001, canPostPromoted = true))
        assertTrue(shouldRequestPromotion(37, 3_700_000, canPostPromoted = true))
    }

    @Test
    fun progressIsDeterminateOnlyWhileTransferring() {
        assertEquals(42, determinateProgressPercent(JobState.DOWNLOADING, 42.7f))
        assertEquals(100, determinateProgressPercent(JobState.DOWNLOADING, 150f))
        assertEquals(0, determinateProgressPercent(JobState.DOWNLOADING, 0.4f))
        assertEquals(null, determinateProgressPercent(JobState.DOWNLOADING, 0f))
        assertEquals(null, determinateProgressPercent(JobState.PREPARING, 30f))
        assertEquals(null, determinateProgressPercent(JobState.POSTPROCESSING, 99f))
        assertEquals(null, determinateProgressPercent(JobState.DOWNLOADING, -5f))
        assertEquals(null, determinateProgressPercent(JobState.DOWNLOADING, Float.NaN))
        assertEquals(null, determinateProgressPercent(JobState.DOWNLOADING, Float.POSITIVE_INFINITY))
    }
}
