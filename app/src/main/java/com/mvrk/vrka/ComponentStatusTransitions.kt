package com.mvrk.vrka

internal object ComponentStatusTransitions {
    const val INSTALLED_VERSION_UNAVAILABLE = "Installed version unavailable; cannot compare update"

    /**
     * Installed-version resolution is a local read with its own flag. It never touches
     * [ComponentCheckState], so a discovery request that finishes while the version read is
     * still running keeps its UPDATE_AVAILABLE/UP_TO_DATE result.
     */
    fun resolutionStarted(status: ComponentStatus): ComponentStatus =
        status.copy(isResolvingInstalledVersion = true)

    fun resolutionFinished(status: ComponentStatus): ComponentStatus =
        status.copy(isResolvingInstalledVersion = false)

    fun beginCheck(status: ComponentStatus): ComponentStatus = status.copy(
        latestVersion = null,
        checkState = ComponentCheckState.CHECKING,
        // A new discovery supersedes a previous terminal install result; otherwise the card
        // would keep showing "Failed"/"Updated" after a successful recheck.
        updateState = if (status.updateState == ComponentUpdateState.UPDATE_SUCCESS ||
            status.updateState == ComponentUpdateState.UPDATE_FAILED
        ) {
            ComponentUpdateState.UPDATE_IDLE
        } else {
            status.updateState
        },
        message = "Checking for updates...",
        error = null,
    )

    fun installedVersionResolved(status: ComponentStatus, version: String): ComponentStatus {
        val hasVersion = version.isNotBlank() && !version.equals("Unknown", ignoreCase = true)
        val recoveredFromUnavailableVersion = hasVersion &&
            status.error == INSTALLED_VERSION_UNAVAILABLE
        return status.copy(
            installedVersion = if (hasVersion) version else status.installedVersion,
            latestVersion = if (recoveredFromUnavailableVersion) null else status.latestVersion,
            checkState = if (recoveredFromUnavailableVersion) ComponentCheckState.CHECK_IDLE else status.checkState,
            message = if (recoveredFromUnavailableVersion) "" else status.message,
            error = if (recoveredFromUnavailableVersion) null else status.error,
        )
    }

    fun checkSucceeded(
        status: ComponentStatus,
        candidateVersion: String,
        channelSwitch: Boolean = false,
        channelLabel: String? = null,
        checkedAt: Long = System.currentTimeMillis(),
    ): ComponentStatus {
        val cleanCandidate = ComponentUpdateManager.cleanVersionString(candidateVersion)
        val cleanInstalled = ComponentUpdateManager.cleanVersionString(status.installedVersion)
        val hasUpdate = channelSwitch || ComponentUpdateManager.isNewerVersion(
            candidate = candidateVersion,
            installed = status.installedVersion,
        )
        return status.copy(
            checkState = if (hasUpdate) ComponentCheckState.UPDATE_AVAILABLE else ComponentCheckState.UP_TO_DATE,
            latestVersion = cleanCandidate,
            message = when {
                channelSwitch -> "Channel switch: v$cleanCandidate (${channelLabel.orEmpty()})"
                hasUpdate -> "Update available: v$cleanCandidate"
                else -> "Up to date (v$cleanInstalled)"
            },
            error = null,
            lastChecked = checkedAt,
        )
    }

    fun checkFailed(status: ComponentStatus, error: String, checkedAt: Long = System.currentTimeMillis()): ComponentStatus =
        status.copy(
            latestVersion = null,
            checkState = ComponentCheckState.CHECK_FAILED,
            message = when {
                error.startsWith("GitHub API rate limit") -> error
                error.startsWith("Check timed out") -> error
                else -> "Check failed: $error"
            },
            error = error,
            lastChecked = checkedAt,
        )

    fun checkCancelled(status: ComponentStatus, checkedAt: Long = System.currentTimeMillis()): ComponentStatus =
        status.copy(
            latestVersion = null,
            checkState = ComponentCheckState.CHECK_IDLE,
            message = "Update check cancelled",
            error = null,
            lastChecked = checkedAt,
        )

    fun updateSucceeded(status: ComponentStatus, installedVersion: String): ComponentStatus {
        val clean = ComponentUpdateManager.cleanVersionString(installedVersion)
        return status.copy(
            lifecycleState = ComponentLifecycleState.UPDATED,
            updateState = ComponentUpdateState.UPDATE_SUCCESS,
            checkState = ComponentCheckState.UP_TO_DATE,
            installedVersion = installedVersion,
            latestVersion = clean,
            message = "Updated successfully to v$clean",
            error = null,
        )
    }
}

internal val BatchOperationState.isInProgress: Boolean
    get() = this == BatchOperationState.CHECKING || this == BatchOperationState.UPDATING
