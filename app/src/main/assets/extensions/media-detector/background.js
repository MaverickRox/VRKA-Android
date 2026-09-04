/**
 * VRKA Android 4.0.1 Media Discovery WebExtension Background Service
 * Adapted from Puemos HLS stream capture architecture & GeckoView Native Bridge
 */

const seenCandidates = new Map();
const requestHeadersMap = new Map();
const urlHeadersMap = new Map();
let lastObservedUserAgent = "";

function isAdOrNuisanceUrl(url) {
    if (!url) return 0;
    const lower = url.toLowerCase();
    if (lower.match(/\b(doubleclick|googlesyndication|adnxs|adroll|popcash|exoclick|trafficjunky|adsterra|serving-sys|creativecdn|adsystem|banner|promo)\b/)) {
        return 20;
    }
    if (lower.match(/\b\d{5,}\b.*_(\d{3,4})p(?:\.m3u8)?$/)) {
        return 10;
    }
    return 0;
}

let activeDomPlayer = null;
let lastDomPlayerTime = 0;

function sendCandidateToNative(candidate) {
    if (!candidate || !candidate.url) return;
    
    // Normalize URL
    const url = candidate.url.trim();
    if (!url.startsWith("http://") && !url.startsWith("https://")) return;
    
    // Ignore static image, font, and script assets
    const lower = url.toLowerCase();
    if (lower.match(/\.(jpg|jpeg|png|gif|svg|webp|ico|css|js|woff|woff2|ttf|json)(\?|$)/)) {
        return;
    }

    const key = `${candidate.kind}:${url}`;
    const now = Date.now();
    if (seenCandidates.has(key)) {
        const lastSeen = seenCandidates.get(key);
        if (now - lastSeen < 15000) {
            return; // Deduplicate within 15s
        }
    }
    seenCandidates.set(key, now);

    // Prune cache if large
    if (seenCandidates.size > 250) {
        for (const [k, t] of seenCandidates.entries()) {
            if (now - t > 60000) seenCandidates.delete(k);
        }
    }

    try {
        browser.runtime.sendNativeMessage("browser", {
            type: "MEDIA_DETECTED",
            candidate: {
                url: url,
                pageUrl: candidate.pageUrl || "",
                title: candidate.title || "",
                kind: candidate.kind || "Video",
                resolution: candidate.resolution || "",
                mimeType: candidate.mimeType || "",
                headers: candidate.headers || {},
                source: candidate.source || "network",
                userStarted: Boolean(candidate.userStarted),
                primaryPlayer: Boolean(candidate.primaryPlayer),
                playing: candidate.playing !== undefined ? Boolean(candidate.playing) : null,
                width: candidate.width || 0,
                height: candidate.height || 0,
                duration: candidate.duration || 0,
                nuisanceScore: candidate.nuisanceScore || 0
            }
        }).catch(() => {});
    } catch (e) {
        console.error("[VRKA-MediaDetector] Failed to send native message:", e);
    }
}

// 1. Capture outgoing headers
browser.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
        if (!details.url) return;
        const headers = {};
        if (details.requestHeaders) {
            for (const h of details.requestHeaders) {
                const name = h.name.toLowerCase();
                if (["user-agent", "referer", "origin", "range", "cookie"].includes(name)) {
                    headers[h.name] = h.value;
                    if (name === "user-agent" && h.value) {
                        lastObservedUserAgent = h.value;
                    }
                }
            }
        }
        requestHeadersMap.set(details.requestId, { url: details.url, headers });
        if (requestHeadersMap.size > 150) {
            const firstKey = requestHeadersMap.keys().next().value;
            requestHeadersMap.delete(firstKey);
        }
        
        // Also cache by URL and Origin for correlation with DOM events
        urlHeadersMap.set(details.url, headers);
        try {
            const parsed = new URL(details.url);
            urlHeadersMap.set(parsed.origin, headers);
        } catch (e) {}
        if (urlHeadersMap.size > 200) {
            const firstKey = urlHeadersMap.keys().next().value;
            urlHeadersMap.delete(firstKey);
        }
    },
    { urls: ["<all_urls>"] },
    ["requestHeaders"]
);

// 2. Sniff responses for media content types and extensions
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        if (!details.url) return;
        const url = details.url;
        const lowerUrl = url.toLowerCase();
        
        let contentType = "";
        if (details.responseHeaders) {
            for (const h of details.responseHeaders) {
                const name = h.name.toLowerCase();
                if (name === "content-type") contentType = h.value.toLowerCase();
            }
        }

        const cachedReq = requestHeadersMap.get(details.requestId);
        const headers = (cachedReq && cachedReq.headers) ? cachedReq.headers : {};

        let detectedKind = null;
        let resolution = "";

        if (
            contentType.includes("application/vnd.apple.mpegurl") ||
            contentType.includes("application/x-mpegurl") ||
            contentType.includes("audio/mpegurl") ||
            contentType.includes("audio/x-mpegurl") ||
            lowerUrl.includes(".m3u8") ||
            lowerUrl.includes("format=m3u8")
        ) {
            detectedKind = "HLS";
            parseHlsPlaylist(url, headers);
        } else if (
            contentType.includes("application/dash+xml") ||
            lowerUrl.includes(".mpd")
        ) {
            detectedKind = "DASH";
        } else if (
            contentType.includes("video/mp4") ||
            contentType.includes("video/webm") ||
            contentType.includes("video/quicktime") ||
            contentType.includes("video/x-matroska") ||
            lowerUrl.match(/\.(mp4|webm|mkv|m4v|mov)(\?|$)/)
        ) {
            detectedKind = "Video";
        } else if (
            contentType.includes("audio/mp4") ||
            contentType.includes("audio/mpeg") ||
            contentType.includes("audio/ogg") ||
            contentType.includes("audio/webm") ||
            contentType.includes("audio/aac") ||
            contentType.includes("audio/flac") ||
            contentType.includes("audio/wav") ||
            lowerUrl.match(/\.(mp3|aac|flac|wav|ogg|m4a|opus)(\?|$)/)
        ) {
            detectedKind = "Audio";
        }

        if (detectedKind) {
            const adNuisance = isAdOrNuisanceUrl(url);
            let userStarted = false;
            let primaryPlayer = false;
            let playing = null;
            let width = 0;
            let height = 0;
            let duration = 0;
            let nuisanceScore = adNuisance;

            if (adNuisance > 0) {
                nuisanceScore = adNuisance;
                primaryPlayer = false;
                userStarted = false;
            } else if (activeDomPlayer && (Date.now() - lastDomPlayerTime < 45000)) {
                userStarted = activeDomPlayer.userStarted;
                primaryPlayer = activeDomPlayer.primaryPlayer;
                playing = activeDomPlayer.playing;
                width = activeDomPlayer.width;
                height = activeDomPlayer.height;
                duration = activeDomPlayer.duration;
                if (!resolution && activeDomPlayer.resolution) {
                    resolution = activeDomPlayer.resolution;
                }
            } else {
                // Desktop Build 017 fallback default (browser_fallback.py:568-581):
                // In fallback browser session, non-ad media requests default to primary player
                userStarted = true;
                primaryPlayer = true;
                playing = true;
            }

            sendCandidateToNative({
                url: url,
                pageUrl: headers["Referer"] || "",
                title: activeDomPlayer ? activeDomPlayer.title : "",
                kind: detectedKind,
                resolution: resolution,
                mimeType: contentType,
                headers: headers,
                source: "network",
                userStarted: userStarted,
                primaryPlayer: primaryPlayer,
                playing: playing,
                width: width,
                height: height,
                duration: duration,
                nuisanceScore: nuisanceScore
            });
        }
    },
    { urls: ["<all_urls>"] },
    ["responseHeaders"]
);

// 3. Parse HLS master playlists for stream qualities (Puemos technique)
async function parseHlsPlaylist(playlistUrl, headers) {
    try {
        const resp = await fetch(playlistUrl, {
            headers: {
                "User-Agent": headers["User-Agent"] || navigator.userAgent,
                "Referer": headers["Referer"] || playlistUrl
            }
        });
        if (!resp.ok) return;
        const text = await resp.text();
        if (!text.includes("#EXTM3U")) return;

        const adNuisance = isAdOrNuisanceUrl(playlistUrl);
        const isMasterPrimary = adNuisance === 0;

        // First emit the master playlist itself as a top-ranked candidate
        sendCandidateToNative({
            url: playlistUrl,
            pageUrl: headers["Referer"] || playlistUrl,
            title: "Master Playlist",
            kind: "HLS",
            resolution: "",
            mimeType: "application/vnd.apple.mpegurl",
            headers: headers,
            source: "network",
            userStarted: adNuisance === 0,
            primaryPlayer: isMasterPrimary,
            playing: true,
            nuisanceScore: adNuisance
        });

        const lines = text.split("\n");
        let currentResolution = "";

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                const resMatch = line.match(/RESOLUTION=(\d+x\d+)/i);
                currentResolution = resMatch ? resMatch[1] : "";
            } else if (line && !line.startsWith("#")) {
                let variantUrl = line;
                if (!variantUrl.startsWith("http://") && !variantUrl.startsWith("https://")) {
                    variantUrl = new URL(variantUrl, playlistUrl).href;
                }
                sendCandidateToNative({
                    url: variantUrl,
                    pageUrl: playlistUrl,
                    title: currentResolution ? `${currentResolution} Variant` : "HLS Stream",
                    kind: "HLS",
                    resolution: currentResolution,
                    mimeType: "application/vnd.apple.mpegurl",
                    headers: headers,
                    source: "playlist",
                    userStarted: adNuisance === 0,
                    primaryPlayer: false,
                    playing: true,
                    nuisanceScore: adNuisance
                });
                currentResolution = "";
            }
        }
    } catch (e) {}
}

// 4. Handle messages from Content Scripts
browser.runtime.onMessage.addListener((msg, sender) => {
    if (msg && msg.type === "DOM_PLAYER_ACTIVE") {
        activeDomPlayer = {
            pageUrl: (sender.tab ? sender.tab.url : msg.pageUrl) || "",
            title: msg.title || "",
            kind: msg.kind || "Video",
            resolution: msg.resolution || "",
            userStarted: Boolean(msg.userStarted),
            primaryPlayer: Boolean(msg.primaryPlayer),
            playing: msg.playing !== undefined ? Boolean(msg.playing) : null,
            width: msg.width || 0,
            height: msg.height || 0,
            duration: msg.duration || 0,
            nuisanceScore: msg.nuisanceScore || 0
        };
        lastDomPlayerTime = Date.now();
        return;
    }

    if (msg && msg.type === "DOM_MEDIA_DETECTED") {
        let headers = urlHeadersMap.get(msg.url);
        if (!headers) {
            try {
                const parsed = new URL(msg.url);
                headers = urlHeadersMap.get(parsed.origin);
            } catch (e) {}
        }
        headers = headers ? Object.assign({}, headers) : {};
        if (!headers["User-Agent"] && lastObservedUserAgent) {
            headers["User-Agent"] = lastObservedUserAgent;
        }
        const effectivePageUrl = sender.tab ? sender.tab.url : msg.pageUrl;
        if (!headers["Referer"] && effectivePageUrl) {
            headers["Referer"] = effectivePageUrl;
        }

        sendCandidateToNative({
            url: msg.url,
            pageUrl: effectivePageUrl,
            title: msg.title || (sender.tab ? sender.tab.title : ""),
            kind: msg.kind || "Video",
            resolution: msg.resolution || "",
            mimeType: msg.mimeType || "",
            headers: headers,
            source: "dom",
            userStarted: Boolean(msg.userStarted),
            primaryPlayer: Boolean(msg.primaryPlayer),
            playing: msg.playing !== undefined ? Boolean(msg.playing) : null,
            width: msg.width || 0,
            height: msg.height || 0,
            duration: msg.duration || 0,
            nuisanceScore: msg.nuisanceScore || 0
        });
    }
});
