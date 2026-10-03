package com.mvrk.vrka

internal enum class ComponentStatusTone {
    SUCCESS,
    WARNING,
    ERROR,
    ACCENT,
    NEUTRAL,
}

internal data class ComponentStatusPresentation(
    val label: String,
    val tone: ComponentStatusTone,
) {
    companion object {
        fun from(status: ComponentStatus): ComponentStatusPresentation = when {
            status.isUpdating -> ComponentStatusPresentation(
                label = when (status.updateState) {
                    ComponentUpdateState.DOWNLOADING -> "Downloading"
                    ComponentUpdateState.VERIFYING -> "Verifying"
                    ComponentUpdateState.INSTALLING -> "Installing"
                    else -> "Updating"
                },
                tone = ComponentStatusTone.WARNING,
            )
            status.isChecking -> ComponentStatusPresentation("Checking", ComponentStatusTone.ACCENT)
            status.updateState == ComponentUpdateState.UPDATE_SUCCESS ->
                ComponentStatusPresentation("Updated", ComponentStatusTone.SUCCESS)
            status.updateState == ComponentUpdateState.UPDATE_FAILED ->
                ComponentStatusPresentation("Failed", ComponentStatusTone.ERROR)
            status.checkState == ComponentCheckState.UPDATE_AVAILABLE ->
                ComponentStatusPresentation("v${status.latestVersion.orEmpty()}", ComponentStatusTone.ACCENT)
            status.checkState == ComponentCheckState.CHECK_FAILED ->
                ComponentStatusPresentation("Error", ComponentStatusTone.ERROR)
            status.checkState == ComponentCheckState.UP_TO_DATE ->
                ComponentStatusPresentation("Up to date", ComponentStatusTone.SUCCESS)
            else -> ComponentStatusPresentation("Not checked", ComponentStatusTone.NEUTRAL)
        }

        fun versionLabel(installedVersion: String): String {
            val clean = ComponentUpdateManager.cleanVersionString(installedVersion)
            return when {
                clean.isBlank() || clean.equals("Unknown", ignoreCase = true) -> "Unknown"
                clean.startsWith("v", ignoreCase = true) -> clean
                else -> "v$clean"
            }
        }
    }
}
