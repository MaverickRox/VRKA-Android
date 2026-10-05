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
    val installedText: String = "",
    val secondaryText: String? = null,
) {
    companion object {
        fun from(status: ComponentStatus): ComponentStatusPresentation {
            val state = when {
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
                    ComponentStatusPresentation(
                    label = status.latestVersion?.let { versionLabel(it) } ?: "Update available",
                    tone = ComponentStatusTone.ACCENT,
                    )
                status.checkState == ComponentCheckState.CHECK_FAILED ->
                    ComponentStatusPresentation("Error", ComponentStatusTone.ERROR)
                status.checkState == ComponentCheckState.UP_TO_DATE ->
                    ComponentStatusPresentation("Ready", ComponentStatusTone.SUCCESS)
                else -> ComponentStatusPresentation("Not checked", ComponentStatusTone.NEUTRAL)
            }
            return state.copy(
                installedText = "Installed: ${versionLabel(status.installedVersion)}",
                secondaryText = status.message.takeIf(String::isNotBlank) ?: defaultSecondaryText(status),
            )
        }

        fun versionLabel(installedVersion: String): String {
            val clean = ComponentUpdateManager.cleanVersionString(installedVersion)
            return when {
                clean.isBlank() || clean.equals("Unknown", ignoreCase = true) -> "Unknown"
                clean.startsWith("v", ignoreCase = true) -> clean
                else -> "v$clean"
            }
        }

        private fun defaultSecondaryText(status: ComponentStatus): String? = when {
            status.checkState == ComponentCheckState.UP_TO_DATE ->
                status.latestVersion?.let { "Up to date (${versionLabel(it)})" }
            status.checkState == ComponentCheckState.UPDATE_AVAILABLE ->
                status.latestVersion?.let { "Update available: ${versionLabel(it)}" }
            status.updateState == ComponentUpdateState.UPDATE_SUCCESS ->
                "Updated successfully to ${versionLabel(status.installedVersion)}"
            status.checkState == ComponentCheckState.CHECK_FAILED || status.updateState == ComponentUpdateState.UPDATE_FAILED ->
                status.error?.let { "Check failed: $it" }
            else -> null
        }
    }
}
