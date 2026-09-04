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
        Log.d(TAG, "onMessage received from $nativeApp: $message")
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

                val pageUrl = candidateObj.optString("pageUrl")
                val title = candidateObj.optString("title")
                val kind = candidateObj.optString("kind", "Video")
                val resolution = candidateObj.optString("resolution")
                val mimeType = candidateObj.optString("mimeType")
                val source = candidateObj.optString("source", "network")
                val nuisanceScore = candidateObj.optInt("nuisanceScore", 0)
                val isNuisance = nuisanceScore > 0
                val userStarted = if (candidateObj.has("userStarted")) {
                    candidateObj.optBoolean("userStarted")
                } else {
                    !isNuisance
                }
                val primaryPlayer = if (candidateObj.has("primaryPlayer")) {
                    candidateObj.optBoolean("primaryPlayer")
                } else {
                    !isNuisance
                }
                val playing = if (candidateObj.has("playing")) {
                    candidateObj.optBoolean("playing")
                } else if (!isNuisance) {
                    true
                } else null
                val width = if (candidateObj.has("width")) candidateObj.optInt("width").takeIf { it > 0 } else null
                val height = if (candidateObj.has("height")) candidateObj.optInt("height").takeIf { it > 0 } else null
                val durationSeconds = if (candidateObj.has("duration")) candidateObj.optDouble("duration").takeIf { it > 0 } else null

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
                    userStarted = userStarted,
                    primaryPlayer = primaryPlayer,
                    playing = playing,
                    width = width,
                    height = height,
                    durationSeconds = durationSeconds,
                    nuisanceScore = nuisanceScore,
                )

                Log.i(TAG, "Discovered media candidate: ${candidate.kind} - ${candidate.displayTitle} (userStarted=$userStarted, primary=$primaryPlayer, url=${candidate.url})")

                synchronized(seenUrls) {
                    val currentList = _candidates.value
                    val existingIndex = currentList.indexOfFirst { it.url == url }
                    val updatedCandidate = if (existingIndex >= 0) {
                        val existing = currentList[existingIndex]
                        candidate.copy(
                            id = UUID.randomUUID().toString(),
                            userStarted = userStarted || existing.userStarted,
                            primaryPlayer = primaryPlayer || existing.primaryPlayer,
                            playing = playing ?: existing.playing,
                            width = width ?: existing.width,
                            height = height ?: existing.height,
                            durationSeconds = durationSeconds ?: existing.durationSeconds,
                            nuisanceScore = maxOf(nuisanceScore, existing.nuisanceScore),
                            headers = existing.headers + headersMap,
                        )
                    } else {
                        candidate
                    }

                    _candidates.value = if (existingIndex >= 0) {
                        currentList.toMutableList().apply { set(existingIndex, updatedCandidate) }
                    } else {
                        (currentList + updatedCandidate).takeLast(50)
                    }
                }
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

data class MediaStreamCandidate(
    val id: String,
    val url: String,
    val pageUrl: String = "",
    val title: String = "",
    val kind: String = "Video", // HLS, DASH, Video, Audio
    val resolution: String = "", // e.g. "1920x1080", "1080p"
    val mimeType: String = "",
    val headers: Map<String, String> = emptyMap(),
    val source: String = "network", // network, dom, playlist
    val userStarted: Boolean = false,
    val primaryPlayer: Boolean = false,
    val playing: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
    val nuisanceScore: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val displayTitle: String
        get() = when {
            title.isNotBlank() -> title
            resolution.isNotBlank() -> "$kind ($resolution)"
            else -> "$kind Stream"
        }

    val displaySubtitle: String
        get() = when {
            url.length > 55 -> url.take(28) + "..." + url.takeLast(24)
            else -> url
        }
}
