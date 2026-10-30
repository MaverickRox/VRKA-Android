package com.mvrk.vrka

import org.mozilla.geckoview.ContentBlocking

/**
 * Privacy contract for the bundled GeckoView engine. Kept separate from the runtime
 * construction so the chosen levels can be asserted without starting GeckoView.
 */
object GeckoPrivacyDefaults {
    const val ANTI_TRACKING = ContentBlocking.AntiTracking.STRICT
    const val COOKIE_BEHAVIOR = ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
    const val ETP_LEVEL = ContentBlocking.EtpLevel.STRICT
    const val SAFE_BROWSING = ContentBlocking.SafeBrowsing.DEFAULT
    const val BOUNCE_TRACKING_MODE =
        ContentBlocking.BounceTrackingProtectionMode.BOUNCE_TRACKING_PROTECTION_MODE_ENABLED

    const val STRICT_SOCIAL_TRACKING = true
    const val COOKIE_PURGING = true
    const val QUERY_PARAMETER_STRIPPING = true
    const val EMAIL_TRACKER_BLOCKING_PRIVATE_MODE = true
    const val GLOBAL_PRIVACY_CONTROL = true

    const val REMOTE_DEBUGGING = false
    const val ABOUT_CONFIG = false
    const val DEBUG_LOGGING = false
    const val CONSOLE_OUTPUT = false
    const val LOGIN_AUTOFILL = false
    const val TRANSLATION_POPUP = false
    const val WEB_MANIFEST = false
    const val TELEMETRY = false
    const val EXPERIMENTS = false
}
