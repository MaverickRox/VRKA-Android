package com.mvrk.vrka.update.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime

class ReleaseManifestTimestampTest {

    @Test
    fun validUtcAndOffsetTimestampsAreAccepted() {
        assertEquals(
            Instant.parse("2026-11-01T06:40:14Z").toEpochMilli(),
            ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14Z"),
        )
        assertEquals(
            Instant.parse("2026-11-01T06:40:14Z").toEpochMilli(),
            ReleaseManifestVerifier.parseIso8601("2026-11-01T12:10:14+05:30"),
        )
        assertEquals(
            Instant.parse("2026-11-01T06:40:14.250Z").toEpochMilli(),
            ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14.250Z"),
        )
        assertNotNull(ReleaseManifestVerifier.parseIso8601(" 2026-11-01T06:40:14Z "))
    }

    @Test
    fun impossibleCalendarDatesAreRejected() {
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-02-30T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-13-01T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-00-10T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-04-31T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-01-01T24:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-01-01T23:60:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-01-01T23:59:60Z"))
    }

    @Test
    fun leapYearRulesAreEnforced() {
        assertNotNull(ReleaseManifestVerifier.parseIso8601("2024-02-29T00:00:00Z"))
        assertNotNull(ReleaseManifestVerifier.parseIso8601("2000-02-29T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-02-29T00:00:00Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("1900-02-29T00:00:00Z"))
    }

    @Test
    fun monthBoundariesAreHandled() {
        assertNotNull(ReleaseManifestVerifier.parseIso8601("2026-01-31T23:59:59Z"))
        assertNotNull(ReleaseManifestVerifier.parseIso8601("2026-12-01T00:00:00Z"))
    }

    @Test
    fun incompleteTimestampsAndTrailingTextAreRejected() {
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14,5Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01 06:40:14Z"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14Zjunk"))
        assertNull(ReleaseManifestVerifier.parseIso8601("2026-11-01T06:40:14Z extra"))
        assertNull(ReleaseManifestVerifier.parseIso8601("invalid-timestamp"))
        assertNull(ReleaseManifestVerifier.parseIso8601(""))
    }

    @Test
    fun exportedEpochMatchesJavaTimeForOffsets() {
        val parsed = ReleaseManifestVerifier.parseIso8601("2026-06-15T18:30:00-04:00")
        assertEquals(
            OffsetDateTime.parse("2026-06-15T18:30:00-04:00").toInstant().toEpochMilli(),
            parsed,
        )
    }
}
