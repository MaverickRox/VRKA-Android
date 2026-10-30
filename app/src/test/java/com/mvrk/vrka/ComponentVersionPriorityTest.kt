package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComponentVersionPriorityTest {

    @Test
    fun sanitizeStripsTheToolPrefixAndRejectsPlaceholders() {
        assertEquals("2026.08.19", ComponentVersionPriority.sanitize("yt-dlp 2026.08.19"))
        assertEquals("2026.08.19", ComponentVersionPriority.sanitize("  2026.08.19  "))
        assertEquals("1.64.2", ComponentVersionPriority.sanitize("1.64.2"))
        assertNull(ComponentVersionPriority.sanitize(null))
        assertNull(ComponentVersionPriority.sanitize(""))
        assertNull(ComponentVersionPriority.sanitize("   "))
        assertNull(ComponentVersionPriority.sanitize("Unknown"))
        assertNull(ComponentVersionPriority.sanitize("unknown"))
    }

    @Test
    fun resolveReturnsTheFirstReadableArtifactVersion() {
        assertEquals(
            "2026.08.19",
            ComponentVersionPriority.resolve(
                { "Unknown" },
                { "  " },
                { null },
                { "2026.08.19" },
                { "2026.06.30" },
            ),
        )
    }

    @Test
    fun resolveIsLazySoExpensiveSourcesAreNeverTouchedOnceAnswered() {
        var bundledReads = 0
        var probeRuns = 0

        val resolved = ComponentVersionPriority.resolve(
            { "2026.08.19" },
            { bundledReads++; "2026.08.19" },
            { probeRuns++; "2026.08.19" },
        )

        assertEquals("2026.08.19", resolved)
        assertEquals("A readable installed artifact must short-circuit later sources", 0, bundledReads)
        assertEquals(0, probeRuns)
    }

    @Test
    fun resolveContinuesPastSourcesThatThrow() {
        assertEquals(
            "2026.08.19",
            ComponentVersionPriority.resolve(
                { throw IllegalStateException("artifact unreadable") },
                { "2026.08.19" },
            ),
        )
    }

    @Test
    fun resolveReportsNullWhenEverySourceIsUnavailable() {
        assertNull(
            ComponentVersionPriority.resolve(
                { null },
                { "" },
                { "Unknown" },
                { throw java.io.IOException("missing") },
            ),
        )
    }

    @Test
    fun unknownIsNeverAPriorityResult() {
        assertNull(
            ComponentVersionPriority.resolve(
                { "Unknown" },
                { null },
            ),
        )
    }
}
