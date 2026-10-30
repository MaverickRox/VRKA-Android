package com.mvrk.vrka

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import java.util.UUID

/**
 * Transparent, task-isolated share target. It is deliberately not the launcher activity:
 * the originating application stays visible behind the configuration card, the download
 * is handed to the app-wide [VrkaDownloadManager], and this activity is removed when the
 * card is submitted or dismissed.
 */
class ShareReceiverActivity : ComponentActivity() {
    private var sharedLink by mutableStateOf<SharedLinkRequest?>(null)
    private var pendingRequest: DownloadRequest? = null

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
        val notificationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            val request = pendingRequest
            pendingRequest = null
            if (request != null) manager.enqueue(request)
            finishAndRemoveTask()
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
                        onEstimateSize = { downloadRequest -> manager.estimateSize(downloadRequest) },
                        onEnqueue = { downloadRequest ->
                            val needsPermission =
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        this@ShareReceiverActivity,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                            if (needsPermission) {
                                pendingRequest = downloadRequest
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                manager.enqueue(downloadRequest)
                            }
                        },
                        onDismiss = {
                            if (pendingRequest == null) finishAndRemoveTask()
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
