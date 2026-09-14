package com.mvrk.vrka.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mvrk.vrka.ComponentUpdateManager
import com.mvrk.vrka.SecureComponentUpdater
import com.mvrk.vrka.UpdatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComponentUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val componentId = inputData.getString(KEY_COMPONENT_ID)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing component ID"))
        val targetVersion = inputData.getString(KEY_TARGET_VERSION)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing target version"))
        val channelStr = inputData.getString(KEY_CHANNEL) ?: UpdatePreference.STABLE.name
        val channel = runCatching { UpdatePreference.valueOf(channelStr) }.getOrDefault(UpdatePreference.STABLE)
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256)
        val installedVersion = inputData.getString(KEY_INSTALLED_VERSION) ?: "0.0.0"

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("$componentId.state", STATE_DOWNLOADING)
            .putString("$componentId.targetVersion", targetVersion)
            .remove("$componentId.error")
            .apply()

        val updater = SecureComponentUpdater(applicationContext)

        try {
            Log.i(TAG, "Starting persistent update for $componentId to v$targetVersion...")
            setProgress(workDataOf(KEY_PROGRESS_STATE to STATE_DOWNLOADING))

            val verifiedVersion: String = if (componentId == ComponentUpdateManager.ID_YTDLP) {
                setProgress(workDataOf(KEY_PROGRESS_STATE to STATE_VERIFYING))
                val res = updater.updateYtDlp(channel, targetVersion)
                res.getOrThrow()
            } else {
                if (downloadUrl.isNullOrBlank()) {
                    throw IllegalArgumentException("Missing download URL for extension update: $componentId")
                }
                setProgress(workDataOf(KEY_PROGRESS_STATE to STATE_VERIFYING))
                val res = updater.updateExtension(
                    componentId = componentId,
                    candidateVersion = targetVersion,
                    installedVersion = installedVersion,
                    downloadUrl = downloadUrl,
                    expectedSha256 = expectedSha256,
                )
                res.getOrThrow()
            }

            Log.i(TAG, "Component $componentId successfully updated to v$verifiedVersion")
            prefs.edit()
                .putString("$componentId.state", STATE_COMPLETED)
                .putString("$componentId.installedVersion", verifiedVersion)
                .remove("$componentId.error")
                .apply()

            Result.success(
                workDataOf(
                    KEY_COMPONENT_ID to componentId,
                    KEY_INSTALLED_VERSION to verifiedVersion,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "ComponentUpdateWorker failed for $componentId: ${e.message}", e)
            prefs.edit()
                .putString("$componentId.state", STATE_FAILED)
                .putString("$componentId.error", e.message ?: "Update failed")
                .apply()

            Result.failure(
                workDataOf(
                    KEY_COMPONENT_ID to componentId,
                    KEY_ERROR to (e.message ?: "Update failed"),
                )
            )
        }
    }

    companion object {
        private const val TAG = "VRKA-ComponentWorker"
        const val PREFS_NAME = "vrka_component_update_prefs"

        fun getWorkName(componentId: String): String = "VRKA_COMPONENT_UPDATE_$componentId"

        const val KEY_COMPONENT_ID = "component_id"
        const val KEY_TARGET_VERSION = "target_version"
        const val KEY_INSTALLED_VERSION = "installed_version"
        const val KEY_CHANNEL = "channel"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_PROGRESS_STATE = "progress_state"
        const val KEY_ERROR = "error"

        const val STATE_IDLE = "IDLE"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_VERIFYING = "VERIFYING"
        const val STATE_INSTALLING = "INSTALLING"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
    }
}
