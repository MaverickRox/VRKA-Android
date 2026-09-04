package com.mvrk.vrka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val openQueueRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            VrkaRoot(vrkaApplication.downloads, openQueueRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_QUEUE, false) == true) {
            openQueueRequests.value = System.currentTimeMillis()
            intent.removeExtra(EXTRA_OPEN_QUEUE)
        }
        val urlToEnqueue = intent?.getStringExtra(EXTRA_URL)
            ?: (if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null)
            ?: (if (intent?.action == Intent.ACTION_VIEW) intent.dataString else null)
        if (!urlToEnqueue.isNullOrBlank()) {
            vrkaApplication.downloads.enqueue(DownloadRequest(url = urlToEnqueue.trim()))
            openQueueRequests.value = System.currentTimeMillis()
            intent?.removeExtra(EXTRA_URL)
        }
    }

    companion object {
        const val EXTRA_OPEN_QUEUE = "com.mvrk.vrka.OPEN_QUEUE"
        const val EXTRA_URL = "com.mvrk.vrka.URL"
    }
}

