package com.mvrk.vrka

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BrowserFallbackScreen(
    job: DownloadJob,
    adBlocking: Boolean,
    onHandoff: (BrowserHandoff) -> Unit,
    onClose: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val observer = remember(job.id) { BrowserMediaObserver() }
    val candidates by observer.candidates.collectAsStateWithLifecycle()
    var pageUrl by remember(job.id) { mutableStateOf(job.request.url) }
    var pageTitle by remember(job.id) { mutableStateOf("Browser verification") }
    var progress by remember(job.id) { mutableIntStateOf(0) }
    var pageLoading by remember(job.id) { mutableStateOf(true) }
    var webView by remember(job.id) { mutableStateOf<WebView?>(null) }
    var handedOff by remember(job.id) { mutableStateOf(false) }
    var webViewGeneration by remember(job.id) { mutableIntStateOf(0) }
    var recreateAfterPause by remember(job.id) { mutableStateOf(false) }
    var browserError by remember(job.id) { mutableStateOf<String?>(null) }

    fun handoff(candidate: BrowserCandidate) {
        if (handedOff) return
        handedOff = true
        val cookieManager = CookieManager.getInstance()
        val cookies = listOfNotNull(
            cookieManager.getCookie(job.request.url),
            cookieManager.getCookie(candidate.url),
        ).filter(String::isNotBlank).distinct().joinToString("; ")
        onHandoff(
            BrowserHandoff(
                candidate = candidate,
                cookies = cookies,
                userAgent = webView?.settings?.userAgentString.orEmpty(),
                referer = pageUrl,
                title = pageTitle,
            ),
        )
    }

    LaunchedEffect(candidates.firstOrNull()?.url) {
        val best = candidates.firstOrNull() ?: return@LaunchedEffect
        if (best.score >= 175 && !handedOff) {
            delay(1_500)
            if (observer.candidates.value.firstOrNull()?.url == best.url) handoff(best)
        }
    }

    LaunchedEffect(pageLoading, webViewGeneration) {
        if (!pageLoading) return@LaunchedEffect
        delay(20_000)
        if (pageLoading) {
            webView?.stopLoading()
            browserError =
                "The page did not respond within 20 seconds. Check the Android network path and retry."
            pageLoading = false
            Log.w("VRKA", "Browser main-frame load timed out")
        }
    }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pageTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Complete legitimate age, consent, login, or CAPTCHA checks yourself. " +
                            "Play the intended media; VRKA will detect it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        key(webViewGeneration) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                WebView(context).apply {
                    webView = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(true)
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    WebViewCompat.addWebMessageListener(
                        this,
                        "vrkaObserver",
                        setOf("*"),
                    ) { _, message, _, _, _ ->
                        runCatching {
                            val payload = JSONObject(message.data ?: return@runCatching)
                            if (payload.optString("type") == "media") {
                                observer.observe(
                                    url = payload.optString("url"),
                                    source = payload.optString("source", "document"),
                                    headers = mapOf("Referer" to pageUrl),
                                )
                            }
                        }
                    }
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        BrowserMediaObserver.documentStartScript,
                        setOf("*"),
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?,
                        ) {
                            pageLoading = true
                            browserError = null
                            url?.let { pageUrl = it }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageLoading = false
                            pageTitle = view?.title?.take(100) ?: "Browser verification"
                            if (adBlocking) {
                                view?.evaluateJavascript(
                                    BrowserMediaObserver.cosmeticScript,
                                    null,
                                )
                            }
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: RenderProcessGoneDetail?,
                        ): Boolean {
                            pageLoading = true
                            webView = null
                            webViewGeneration += 1
                            Log.w("VRKA", "Browser render process ended; reconstructing session")
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame != true) return
                            val code = error?.errorCode ?: WebViewClient.ERROR_UNKNOWN
                            val description = error?.description
                                ?.toString()
                                ?.take(160)
                                ?.ifBlank { "Unknown WebView error" }
                                ?: "Unknown WebView error"
                            browserError = "$description (WebView error $code)"
                            pageLoading = false
                            Log.w(
                                "VRKA",
                                "Browser main-frame load failed: code=$code host=${request.url.host.orEmpty()}",
                            )
                        }
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val url = request.url.toString()
                            val blocked = request.isForMainFrame &&
                                observer.shouldBlockPopup(url, pageUrl, request.hasGesture())
                            return blocked
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            val url = request.url.toString()
                            observer.observe(
                                url = url,
                                source = if (request.isForMainFrame) "navigation" else "request",
                                headers = request.requestHeaders,
                            )
                            val block = adBlocking &&
                                observer.isConfidentJunk(url) &&
                                !observer.isProtected(url)
                            return if (block) emptyResponse() else null
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            val bucket = (newProgress / 10) * 10
                            if (bucket != progress) progress = bucket
                        }

                        override fun onCreateWindow(
                            view: WebView,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: Message,
                        ): Boolean {
                            val popup = WebView(context)
                            popup.settings.javaScriptEnabled = false
                            popup.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    popupView: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val url = request.url.toString()
                                    val blocked = observer.shouldBlockPopup(
                                        url,
                                        view.url.orEmpty(),
                                        isUserGesture || request.hasGesture(),
                                    )
                                    if (!blocked) view.loadUrl(url)
                                    popupView.destroy()
                                    return true
                                }
                            }
                            val transport = resultMsg.obj as WebView.WebViewTransport
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    loadUrl(pageUrl.ifBlank { job.request.url })
                }
            },
            update = { view ->
                webView = view
            },
            onRelease = { view ->
                if (webView === view) webView = null
                view.stopLoading()
                view.removeAllViews()
                view.destroy()
            },
        )
            if (browserError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Browser page could not load",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        browserError.orEmpty(),
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.padding(top = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = onClose) { Text("Close") }
                        Button(onClick = {
                            browserError = null
                            pageLoading = true
                            progress = 0
                            webViewGeneration += 1
                        }) { Text("Retry") }
                    }
                }
            } else if (pageLoading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Loading browser session…",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        }
        if (candidates.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            ) {
                Text(
                    "Detected media",
                    style = MaterialTheme.typography.titleSmall,
                )
                LazyColumn {
                    items(candidates.take(3), key = BrowserCandidate::url) { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { handoff(candidate) }
                                .padding(vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.kind)
                                Text(
                                    Uri.parse(candidate.url).host.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { handoff(candidate) }) { Text("Use") }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, job.id, webViewGeneration) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (recreateAfterPause) {
                        recreateAfterPause = false
                        pageLoading = true
                        webViewGeneration += 1
                    } else {
                        webView?.onResume()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    recreateAfterPause = true
                    webView?.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }

    DisposableEffect(job.id) {
        onDispose { observer.clear() }
    }
}

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        ByteArrayInputStream(ByteArray(0)),
    )
