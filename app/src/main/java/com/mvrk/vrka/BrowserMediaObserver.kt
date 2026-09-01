package com.mvrk.vrka

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BrowserMediaObserver {
    private val records = LinkedHashMap<String, BrowserCandidate>()
    private val _candidates = MutableStateFlow<List<BrowserCandidate>>(emptyList())
    val candidates: StateFlow<List<BrowserCandidate>> = _candidates.asStateFlow()

    @Synchronized
    fun observe(
        url: String,
        source: String,
        headers: Map<String, String> = emptyMap(),
        mimeType: String = "",
    ) {
        val normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return
        val classification = classify(normalized, mimeType)
        if (classification.first <= 0 || isConfidentJunk(normalized)) return

        val filteredHeaders = headers
            .filterKeys { it.lowercase() in handoffHeaders }
            .filterValues(String::isNotBlank)
            .toMap()
        val candidate = BrowserCandidate(
            url = normalized,
            kind = classification.second,
            score = classification.first,
            source = source,
            headers = filteredHeaders,
        )
        val previous = records[normalized]
        if (previous == null || candidate.score >= previous.score) records[normalized] = candidate
        while (records.size > candidateLimit) records.remove(records.keys.first())
        _candidates.value = records.values
            .sortedWith(compareByDescending<BrowserCandidate> { it.score }.thenBy { it.url })
            .take(candidateLimit)
    }

    fun clear() {
        synchronized(this) { records.clear() }
        _candidates.value = emptyList()
    }

    fun isConfidentJunk(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase().trimEnd('.')
        if (junkHostSuffixes.any { host == it || host.endsWith(".$it") }) return true
        val path = uri.path.orEmpty().lowercase()
        return junkPathRegex.containsMatchIn(path)
    }

    fun shouldBlockPopup(url: String, currentUrl: String, userGesture: Boolean): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return true
        if (isProtected(url)) return false
        if (isConfidentJunk(url)) return true
        val targetHost = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        val currentHost = runCatching { Uri.parse(currentUrl).host.orEmpty() }.getOrDefault("")
        return !userGesture && targetHost.isNotBlank() && targetHost != currentHost
    }

    fun isProtected(url: String): Boolean {
        if (classify(url, "").first > 0) return true
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
        return protectedMarkers.any(path::contains)
    }

    private fun classify(url: String, mimeType: String): Pair<Int, String> {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return 0 to ""
        val path = uri.path.orEmpty().lowercase()
        val mime = mimeType.lowercase().substringBefore(';').trim()
        val base = when {
            segmentExtensions.any(path::endsWith) ||
                segmentPathRegex.containsMatchIn(path) -> 0 to "Segment"
            path.endsWith(".m3u8") ||
                mime == "application/vnd.apple.mpegurl" ||
                mime == "application/x-mpegurl" -> 180 to "HLS"
            path.endsWith(".mpd") || mime == "application/dash+xml" -> 175 to "DASH"
            videoExtensions.any(path::endsWith) || mime.startsWith("video/") -> 140 to "Video"
            audioExtensions.any(path::endsWith) || mime.startsWith("audio/") -> 130 to "Audio"
            else -> 0 to ""
        }
        if (base.first == 0) return base
        var score = base.first
        if ("master" in path) score += 18
        if ("1080" in path || "2160" in path || "1440" in path) score += 8
        return score to base.second
    }

    companion object {
        const val candidateLimit = 50
        private val handoffHeaders = setOf(
            "authorization",
            "cookie",
            "origin",
            "referer",
            "user-agent",
            "x-video-expiration",
            "x-video-ip",
            "x-video-token",
        )
        private val videoExtensions = setOf(".mp4", ".webm", ".m4v", ".mov", ".ogv")
        private val audioExtensions = setOf(
            ".aac", ".flac", ".m4a", ".mp3", ".ogg", ".opus", ".wav", ".weba",
        )
        private val segmentExtensions = setOf(".m4s", ".ts")
        private val segmentPathRegex = Regex(
            """(?:^|[/_.-])(?:segment|seg|fragment|frag|chunk|init)(?:[/_.-]|\d|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val junkHostSuffixes = setOf(
            "adnxs.com",
            "doubleclick.net",
            "google-analytics.com",
            "googleadservices.com",
            "googlesyndication.com",
            "scorecardresearch.com",
            "havenclick.com",
            "pornhaven.ai",
        )
        private val junkPathRegex = Regex(
            """(?:^|/)(?:ads?|advertising|pre-?roll|vast|trackers?)(?:/|$)""",
            RegexOption.IGNORE_CASE,
        )
        private val protectedMarkers = setOf(
            "/auth/", "/authorize", "/cdn-cgi/", "/challenge", "/login",
            "/oauth", "/player", "/verify", "api.php",
        )

        val documentStartScript: String = """
            (() => {
              if (window.__vrkaObserverInstalled) return;
              window.__vrkaObserverInstalled = true;
              const emit = (value, source) => {
                try {
                  const url = new URL(String(value || ''), document.baseURI).href;
                  if (/^https?:/i.test(url) && window.vrkaObserver) {
                    window.vrkaObserver.postMessage(JSON.stringify({
                      type: 'media', url, source: String(source || 'dom')
                    }));
                  }
                } catch (_) {}
              };
              const inspect = root => {
                try {
                  if (root && root.matches && root.matches('video,audio,source')) {
                    emit(root.currentSrc || root.src, 'element');
                  }
                  if (root && root.querySelectorAll) {
                    root.querySelectorAll('video,audio,source').forEach(node =>
                      emit(node.currentSrc || node.src, 'element'));
                  }
                } catch (_) {}
              };
              ['loadedmetadata', 'canplay', 'durationchange'].forEach(name =>
                document.addEventListener(name, event => inspect(event.target), true));
              new MutationObserver(changes => changes.forEach(change =>
                change.addedNodes.forEach(inspect))).observe(
                  document.documentElement || document,
                  { childList: true, subtree: true, attributes: true,
                    attributeFilter: ['src'] }
                );
              if (window.fetch) {
                const originalFetch = window.fetch;
                window.fetch = function(...args) {
                  emit(args[0] && (args[0].url || args[0]), 'fetch');
                  return originalFetch.apply(this, args);
                };
              }
              if (window.XMLHttpRequest) {
                const originalOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url, ...rest) {
                  emit(url, 'xhr');
                  return originalOpen.call(this, method, url, ...rest);
                };
              }
              try {
                new PerformanceObserver(list => list.getEntries().forEach(entry =>
                  emit(entry.name, 'resource'))).observe({ type: 'resource', buffered: true });
              } catch (_) {}
              inspect(document);
            })();
        """.trimIndent()

        val cosmeticScript: String = """
            (() => {
              if (window.__vrkaCosmeticInstalled) return;
              window.__vrkaCosmeticInstalled = true;
              const selectors = [
                '[id*="banner" i]', '[class*="banner-ad" i]', '[class*="popup-ad" i]',
                'iframe[src*="doubleclick"]', 'iframe[src*="havenclick"]'
              ];
              const hide = root => {
                try {
                  selectors.forEach(selector => {
                    if (root.matches && root.matches(selector)) root.remove();
                    if (root.querySelectorAll) root.querySelectorAll(selector).forEach(n => n.remove());
                  });
                } catch (_) {}
              };
              hide(document);
              new MutationObserver(changes => changes.forEach(change =>
                change.addedNodes.forEach(hide))).observe(
                  document.documentElement || document, { childList: true, subtree: true });
            })();
        """.trimIndent()
    }
}
