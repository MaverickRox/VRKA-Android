package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.geckoview.ContentBlocking

class GeckoPrivacyDefaultsTest {
    @Test
    fun trackingProtectionKeepsTrackersButNotContentBlocking() {
        val antiTracking = GeckoPrivacyDefaults.ANTI_TRACKING
        assertEquals(0, antiTracking and ContentBlocking.AntiTracking.CONTENT)
        assertTrue(antiTracking and ContentBlocking.AntiTracking.AD != 0)
        assertTrue(antiTracking and ContentBlocking.AntiTracking.ANALYTIC != 0)
        assertTrue(antiTracking and ContentBlocking.AntiTracking.SOCIAL != 0)
        assertTrue(antiTracking and ContentBlocking.AntiTracking.CRYPTOMINING != 0)
        assertTrue(antiTracking and ContentBlocking.AntiTracking.FINGERPRINTING != 0)
        assertEquals(ContentBlocking.EtpLevel.DEFAULT, GeckoPrivacyDefaults.ETP_LEVEL)
        assertTrue(GeckoPrivacyDefaults.ETP_LEVEL != ContentBlocking.EtpLevel.NONE)
    }

    @Test
    fun cookiesAreFirstPartyAndPartitioned() {
        assertEquals(
            ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS,
            GeckoPrivacyDefaults.COOKIE_BEHAVIOR,
        )
        assertTrue(GeckoPrivacyDefaults.COOKIE_PURGING)
        assertTrue(GeckoPrivacyDefaults.QUERY_PARAMETER_STRIPPING)
    }

    @Test
    fun bounceTrackingProtectionIsEnabled() {
        assertEquals(
            ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED,
            GeckoPrivacyDefaults.BOUNCE_TRACKING_MODE,
        )
    }

    @Test
    fun safeBrowsingIsRetained() {
        assertEquals(ContentBlocking.SafeBrowsing.DEFAULT, GeckoPrivacyDefaults.SAFE_BROWSING)
        assertTrue(GeckoPrivacyDefaults.SAFE_BROWSING != ContentBlocking.SafeBrowsing.NONE)
    }

    @Test
    fun telemetryAndExperimentsAreOff() {
        assertFalse(GeckoPrivacyDefaults.TELEMETRY)
        assertFalse(GeckoPrivacyDefaults.EXPERIMENTS)
        assertFalse(GeckoPrivacyDefaults.DEBUG_LOGGING)
        assertFalse(GeckoPrivacyDefaults.REMOTE_DEBUGGING)
        assertFalse(GeckoPrivacyDefaults.CONSOLE_OUTPUT)
    }

    @Test
    fun globalPrivacyControlIsOn() {
        assertTrue(GeckoPrivacyDefaults.GLOBAL_PRIVACY_CONTROL)
    }

    @Test
    fun bundledEngineIsTheVerifiedStableLine() {
        assertTrue(
            "GeckoView should stay on the verified stable release line",
            BuildConfig.GECKOVIEW_VERSION.startsWith("159."),
        )
    }
}
