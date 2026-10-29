package com.mvrk.vrka

import android.content.Context

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
        fun from(context: Context, status: ComponentStatus): ComponentStatusPresentation {
            val state = when {
                status.isUpdating -> ComponentStatusPresentation(
                    label = when (status.updateState) {
                        ComponentUpdateState.DOWNLOADING -> context.getString(R.string.status_downloading)
                        ComponentUpdateState.VERIFYING -> context.getString(R.string.comp_status_checking)
                        ComponentUpdateState.INSTALLING -> context.getString(R.string.btn_install)
                        else -> context.getString(R.string.btn_updating_components)
                    },
                    tone = ComponentStatusTone.WARNING,
                )
                status.isChecking -> ComponentStatusPresentation(context.getString(R.string.comp_status_checking), ComponentStatusTone.ACCENT)
                status.updateState == ComponentUpdateState.UPDATE_SUCCESS ->
                    ComponentStatusPresentation(context.getString(R.string.comp_status_updated), ComponentStatusTone.SUCCESS)
                status.updateState == ComponentUpdateState.UPDATE_FAILED ->
                    ComponentStatusPresentation(context.getString(R.string.comp_status_failed), ComponentStatusTone.ERROR)
                status.checkState == ComponentCheckState.UPDATE_AVAILABLE ->
                    ComponentStatusPresentation(
                        label = status.latestVersion?.let { versionLabel(it) } ?: context.getString(R.string.comp_status_update_available),
                        tone = ComponentStatusTone.ACCENT,
                    )
                status.checkState == ComponentCheckState.CHECK_FAILED ->
                    ComponentStatusPresentation(context.getString(R.string.comp_status_error), ComponentStatusTone.ERROR)
                status.checkState == ComponentCheckState.UP_TO_DATE ->
                    ComponentStatusPresentation(context.getString(R.string.comp_status_ready), ComponentStatusTone.SUCCESS)
                else -> ComponentStatusPresentation(context.getString(R.string.comp_status_not_checked), ComponentStatusTone.NEUTRAL)
            }
            return state.copy(
                installedText = context.getString(R.string.status_installed, versionLabel(status.installedVersion)),
                secondaryText = localizedSecondaryText(context, status),
            )
        }

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

        private fun localizedSecondaryText(context: Context, status: ComponentStatus): String? {
            if (status.updateState == ComponentUpdateState.UPDATE_SUCCESS) {
                return context.getString(R.string.status_updated_successfully, versionLabel(status.installedVersion))
            }
            if (status.message.isNotBlank() && !status.message.startsWith("Updated successfully", ignoreCase = true)) {
                return status.message
            }
            return when {
                status.checkState == ComponentCheckState.UP_TO_DATE ->
                    status.latestVersion?.let { context.getString(R.string.status_up_to_date, versionLabel(it)) }
                status.checkState == ComponentCheckState.UPDATE_AVAILABLE ->
                    status.latestVersion?.let { context.getString(R.string.status_update_available_version, versionLabel(it)) }
                status.checkState == ComponentCheckState.CHECK_FAILED || status.updateState == ComponentUpdateState.UPDATE_FAILED ->
                    status.error?.let { context.getString(R.string.status_check_failed, it) }
                else -> null
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
