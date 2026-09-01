package com.mvrk.vrka

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import java.util.UUID

class GeckoMediaBridge : WebExtension.MessageDelegate, WebExtension.PortDelegate {

    private val _candidates = MutableStateFlow<List<MediaStreamCandidate>>(emptyList())
    val candidates: StateFlow<List<MediaStreamCandidate>> = _candidates.asStateFlow()

    private var activePort: WebExtension.Port? = null
    private val seenUrls = mutableSetOf<String>()

    override fun onMessage(
        nativeApp: String,
        message: Any,
        sender: WebExtension.MessageSender
    ): GeckoResult<Any>? {
        handlePayload(message)
        val response = JSONObject().put("status", "ok")
        return GeckoResult.fromValue(response)
    }

    override fun onConnect(port: WebExtension.Port) {
        Log.d(TAG, "Media detector extension port connected")
        activePort = port
        port.setDelegate(this)
    }

    override fun onPortMessage(message: Any, port: WebExtension.Port) {
        handlePayload(message)
    }

    override fun onDisconnect(port: WebExtension.Port) {
        Log.d(TAG, "Media detector extension port disconnected")
        if (activePort == port) {
            activePort = null
        }
    }

    private fun handlePayload(message: Any) {
        try {
            val json = when (message) {
                is JSONObject -> message
                is String -> JSONObject(message)
                else -> {
                    val str = message.toString()
                    if (str.startsWith("{")) JSONObject(str) else null
                }
            } ?: return

            val type = json.optString("type")
            if (type == "MEDIA_DETECTED") {
                val candidateObj = json.optJSONObject("candidate") ?: return
                val url = candidateObj.optString("url").trim()
                if (url.isBlank() || !url.startsWith("http")) return

                // Deduplicate
                synchronized(seenUrls) {
                    if (url in seenUrls) return
                    seenUrls.add(url)
                    if (seenUrls.size > 250) {
                        seenUrls.clear()
                    }
                }

                val pageUrl = candidateObj.optString("pageUrl")
                val title = candidateObj.optString("title")
                val kind = candidateObj.optString("kind", "Video")
                val resolution = candidateObj.optString("resolution")
                val mimeType = candidateObj.optString("mimeType")
                val source = candidateObj.optString("source", "network")

                val headersMap = mutableMapOf<String, String>()
                val headersObj = candidateObj.optJSONObject("headers")
                if (headersObj != null) {
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = headersObj.optString(key)
                        if (value.isNotBlank() && isAllowedHeader(key)) {
                            headersMap[key] = value
                        }
                    }
                }

                val candidate = MediaStreamCandidate(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    pageUrl = pageUrl,
                    title = title,
                    kind = kind,
                    resolution = resolution,
                    mimeType = mimeType,
                    headers = headersMap,
                    source = source,
                )

                Log.i(TAG, "Discovered media candidate: ${candidate.kind} - ${candidate.displayTitle} (${candidate.url})")

                _candidates.value = (_candidates.value + candidate).takeLast(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling media detector payload: ${e.message}", e)
        }
    }

    fun clear() {
        synchronized(seenUrls) {
            seenUrls.clear()
        }
        _candidates.value = emptyList()
    }

    private fun isAllowedHeader(name: String): Boolean {
        val lower = name.lowercase()
        return lower in listOf(
            "user-agent",
            "referer",
            "origin",
            "range",
            "cookie",
            "x-requested-with",
        )
    }

    companion object {
        private const val TAG = "VRKA-MediaBridge"
    }
}
