package com.mvrk.vrka

/**
 * Presentation states for the Settings browser subsystem rows. "Active" is only ever
 * reported from a runtime signal (GeckoView runtime created, extension registered, session
 * opened). Artifact presence alone produces Bundled/Installed/Ready, never Active.
 */
internal enum class BrowserSubsystemState {
    BUNDLED,
    ACTIVE,
    INSTALLED_IDLE,
    READY,
    IDLE,
    UNAVAILABLE,
}

internal object BrowserSubsystemStatus {
    fun engine(runtimeStarted: Boolean): BrowserSubsystemState =
        if (runtimeStarted) BrowserSubsystemState.ACTIVE else BrowserSubsystemState.BUNDLED

    fun adBlocker(active: Boolean, installedVersion: String?): BrowserSubsystemState = when {
        active -> BrowserSubsystemState.ACTIVE
        installedVersion.isNullOrBlank() || installedVersion.equals("Unknown", ignoreCase = true) ->
            BrowserSubsystemState.UNAVAILABLE
        else -> BrowserSubsystemState.INSTALLED_IDLE
    }

    fun mediaDetection(active: Boolean, bundled: Boolean): BrowserSubsystemState = when {
        active -> BrowserSubsystemState.ACTIVE
        bundled -> BrowserSubsystemState.READY
        else -> BrowserSubsystemState.UNAVAILABLE
    }

    fun session(active: Boolean): BrowserSubsystemState =
        if (active) BrowserSubsystemState.ACTIVE else BrowserSubsystemState.IDLE
}
