package com.mvrk.vrka

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Transparent, task-isolated share target. It is deliberately not the launcher activity:
 * the originating application stays visible behind the configuration card, the download
 * is handed to the app-wide [VrkaDownloadManager], and this activity is removed when the
 * card is submitted or dismissed.
 */
class ShareReceiverActivity : ComponentActivity() {
    private var sharedLink by mutableStateOf<SharedLinkRequest?>(null)
    private var pendingDownload: PendingDownload? = null

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            shareTracker.restore(
                savedInstanceState.getString(STATE_SHARE_FINGERPRINT),
                savedInstanceState.getLong(STATE_SHARE_SEEN_AT),
            )
        }
        handleIntent(intent)
        if (sharedLink == null) {
            restorePendingShare(savedInstanceState)
        }
        if (sharedLink == null) {
            finishAndRemoveTask()
            return
        }
        enableEdgeToEdge()
        setContent {
            ShareReceiverContent()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHARE_FINGERPRINT, shareTracker.lastFingerprint())
        outState.putLong(STATE_SHARE_SEEN_AT, shareTracker.lastSeenAt())
        sharedLink?.let { link ->
            outState.putString(STATE_SHARE_URL, link.url)
            outState.putString(STATE_SHARE_ID, link.id)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val rawText = when (intent.action) {
            Intent.ACTION_SEND -> sharedText
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        val extracted = ShareLinkValidator.extract(rawText)
        if (extracted == null || !ShareLinkValidator.isValid(extracted)) {
            return
        }
        val fingerprint = ShareIntentTracker.fingerprint(intent.action, intent.dataString, sharedText)
        if (!shareTracker.accept(fingerprint, System.currentTimeMillis())) return

        sharedLink = SharedLinkRequest(url = extracted, id = UUID.randomUUID().toString())
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun restorePendingShare(savedInstanceState: Bundle?) {
        val url = savedInstanceState?.getString(STATE_SHARE_URL) ?: return
        val id = savedInstanceState.getString(STATE_SHARE_ID) ?: return
        if (ShareLinkValidator.isValid(url)) {
            sharedLink = SharedLinkRequest(url = url, id = id)
        }
    }

    @Composable
    private fun ShareReceiverContent() {
        val manager = vrkaApplication.downloads
        val settings by manager.settingsRepository.settings.collectAsStateWithLifecycle()
        val link = sharedLink
        fun handleEnqueueAction(req: DownloadRequest, est: SizeEstimate?) {
            val isPurePlaylist = PlaylistRouting.isPurePlaylistUrl(req.url)
            val targetReq = when {
                isPurePlaylist -> req.copy(isPlaylist = true)
                !req.isPlaylist && PlaylistRouting.isVideoWithPlaylistContext(req.url) ->
                    req.copy(url = PlaylistRouting.stripPlaylistContext(req.url))
                else -> req
            }
            lifecycleScope.launch {
                if (targetReq.isPlaylist) {
                    val items = manager.expandPlaylist(targetReq)
                    if (!items.isNullOrEmpty()) {
                        val playlistGroupId = UUID.randomUUID().toString()
                        val batchItems = items.map { item ->
                            targetReq.copy(
                                url = item.url,
                                isPlaylist = false,
                                playlistItemIndex = item.playlistItemIndex,
                                playlistItemCount = item.playlistItemCount,
                                playlistItemId = item.playlistItemId,
                                playlistGroupId = playlistGroupId,
                            ) to item.estimate
                        }
                        when (val result = manager.enqueueBatch(batchItems, playlistGroupId)) {
                            is BatchEnqueueResult.Rejected -> Toast.makeText(this@ShareReceiverActivity, result.message, Toast.LENGTH_LONG).show()
                            is BatchEnqueueResult.Accepted -> {}
                        }
                        return@launch
                    } else {
                        Toast.makeText(this@ShareReceiverActivity, "Unable to load playlist items. Please check network connection.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                val result = manager.enqueue(targetReq, est)
                if (result is EnqueueResult.Rejected) {
                    Toast.makeText(this@ShareReceiverActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }

        val notificationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            val download = pendingDownload
            pendingDownload = null
            if (download != null) {
                handleEnqueueAction(download.request, download.estimate)
            }
            finishAndRemoveTask()
        }

        var pendingPlaylistChoiceRequest by remember { mutableStateOf<Pair<DownloadRequest, SizeEstimate?>?>(null) }

        fun dispatchDownload(downloadRequest: DownloadRequest, estimate: SizeEstimate?) {
            val needsPermission =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@ShareReceiverActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pendingDownload = PendingDownload(downloadRequest, estimate)
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                handleEnqueueAction(downloadRequest, estimate)
            }
        }

        VrkaTheme(
            themeMode = settings.themeMode,
            amoled = settings.amoled,
            fontPreference = settings.fontPreference,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
            ) {
                link?.let { request ->
                    ShareConfigSheet(
                        link = request,
                        settings = settings,
                        repository = manager.settingsRepository,
                        onEstimateSize = { downloadRequest, force -> manager.estimateSize(downloadRequest, force) },
                        onEnqueue = { downloadRequest, estimate ->
                            if (!downloadRequest.isPlaylist && PlaylistRouting.isVideoWithPlaylistContext(downloadRequest.url)) {
                                pendingPlaylistChoiceRequest = downloadRequest to estimate
                            } else {
                                dispatchDownload(downloadRequest, estimate)
                            }
                        },
                        onDismiss = {
                            if (pendingDownload == null && pendingPlaylistChoiceRequest == null) finishAndRemoveTask()
                        },
                    )
                }

                pendingPlaylistChoiceRequest?.let { (req, est) ->
                    PlaylistChoiceDialog(
                        url = req.url,
                        onSelectPlaylist = {
                            val playlistUrl = PlaylistRouting.extractPlaylistUrl(req.url) ?: req.url
                            pendingPlaylistChoiceRequest = null
                            dispatchDownload(req.copy(url = playlistUrl, isPlaylist = true), null)
                        },
                        onSelectSingleVideo = {
                            val singleUrl = PlaylistRouting.stripPlaylistContext(req.url)
                            pendingPlaylistChoiceRequest = null
                            dispatchDownload(req.copy(url = singleUrl, isPlaylist = false), est)
                        },
                        onDismiss = {
                            pendingPlaylistChoiceRequest = null
                            finishAndRemoveTask()
                        },
                    )
                }
            }
        }
    }

    companion object {
        private const val STATE_SHARE_URL = "com.mvrk.vrka.state.SHARE_URL"
        private const val STATE_SHARE_ID = "com.mvrk.vrka.state.SHARE_ID"
        private const val STATE_SHARE_FINGERPRINT = "com.mvrk.vrka.state.SHARE_FINGERPRINT"
        private const val STATE_SHARE_SEEN_AT = "com.mvrk.vrka.state.SHARE_SEEN_AT"

        // Process-wide so two quick deliveries from the sharesheet stay one logical request.
        private val shareTracker = ShareIntentTracker()
    }
}
