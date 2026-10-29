package com.mvrk.vrka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val openQueueRequests = MutableStateFlow(0L)
    private val sharedLinks = MutableStateFlow<SharedLinkRequest?>(null)
    private val shareIntentTracker = ShareIntentTracker()

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            shareIntentTracker.restore(
                savedInstanceState.getString(STATE_SHARE_FINGERPRINT),
                savedInstanceState.getLong(STATE_SHARE_SEEN_AT),
            )
        }
        handleIntent(intent)
        if (sharedLinks.value == null) {
            restorePendingShare(savedInstanceState)
        }
        enableEdgeToEdge()
        setContent {
            VrkaRoot(
                manager = vrkaApplication.downloads,
                openQueueRequests = openQueueRequests,
                sharedLink = sharedLinks,
                onSharedLinkConsumed = { id ->
                    if (sharedLinks.value?.id == id) sharedLinks.value = null
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHARE_FINGERPRINT, shareIntentTracker.lastFingerprint())
        outState.putLong(STATE_SHARE_SEEN_AT, shareIntentTracker.lastSeenAt())
        sharedLinks.value?.let { link ->
            outState.putString(STATE_SHARE_URL, link.url)
            outState.putString(STATE_SHARE_ID, link.id)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == ACTION_OPEN_APP_UPDATE || intent.getBooleanExtra(EXTRA_OPEN_APP_UPDATE, false)) {
            vrkaApplication.downloads.appUpdateManager.restoreReadyUpdateFlow()
            intent.removeExtra(EXTRA_OPEN_APP_UPDATE)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            openQueueRequests.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_OPEN_QUEUE)
        }

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val rawText = when (intent.action) {
            Intent.ACTION_SEND -> sharedText
            Intent.ACTION_VIEW -> intent.dataString
            else -> intent.getStringExtra(EXTRA_URL)
        }
        val extracted = ShareLinkValidator.extract(rawText)
        if (extracted == null || !ShareLinkValidator.isValid(extracted)) return

        val fingerprint = ShareIntentTracker.fingerprint(intent.action, intent.dataString, sharedText)
        if (!shareIntentTracker.accept(fingerprint, System.currentTimeMillis())) return

        sharedLinks.value = SharedLinkRequest(url = extracted, id = UUID.randomUUID().toString())
        intent.removeExtra(EXTRA_URL)
        intent.removeExtra(Intent.EXTRA_TEXT)
    }

    private fun restorePendingShare(savedInstanceState: Bundle?) {
        val url = savedInstanceState?.getString(STATE_SHARE_URL) ?: return
        val id = savedInstanceState.getString(STATE_SHARE_ID) ?: return
        if (ShareLinkValidator.isValid(url)) {
            sharedLinks.value = SharedLinkRequest(url = url, id = id)
        }
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "com.mvrk.vrka.OPEN_QUEUE"
        const val EXTRA_URL = "com.mvrk.vrka.URL"
        const val ACTION_OPEN_APP_UPDATE = "com.mvrk.vrka.action.OPEN_APP_UPDATE"
        const val EXTRA_OPEN_APP_UPDATE = "com.mvrk.vrka.OPEN_APP_UPDATE"

        private const val STATE_SHARE_URL = "com.mvrk.vrka.state.SHARE_URL"
        private const val STATE_SHARE_ID = "com.mvrk.vrka.state.SHARE_ID"
        private const val STATE_SHARE_FINGERPRINT = "com.mvrk.vrka.state.SHARE_FINGERPRINT"
        private const val STATE_SHARE_SEEN_AT = "com.mvrk.vrka.state.SHARE_SEEN_AT"
    }
}

object UrlExtractor {
    fun extractUrl(text: String?): String? = ShareLinkValidator.extract(text)
}
