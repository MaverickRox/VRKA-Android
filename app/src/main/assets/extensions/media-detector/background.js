/**
 * VRKA Android Media Discovery WebExtension Background Service
 * VRKA media discovery background worker and GeckoView native bridge.
 */

const seenCandidates = new Map();
const requestHeadersMap = new Map();
const urlHeadersMap = new Map();
const FIXED_CAPTURE_HEADERS = new Set([
    "user-agent", "referer", "origin", "range", "cookie", "authorization",
    "accept", "accept-language", "x-requested-with"
]);
const FORBIDDEN_CAPTURE_HEADERS = new Set([
    "host", "content-length", "connection", "proxy-authorization", "proxy-connection",
    "set-cookie", "transfer-encoding", "upgrade"
]);

function isCaptureHeader(name) {
    const lower = name.toLowerCase();
    return (FIXED_CAPTURE_HEADERS.has(lower) || lower.startsWith("x-")) &&
        !FORBIDDEN_CAPTURE_HEADERS.has(lower) && !lower.startsWith("x-moz-") &&
        !lower.startsWith("x-forwarded-");
}

function canonicalHeaderName(name) {
    const lower = name.toLowerCase();
    const known = {
        "user-agent": "User-Agent",
        "referer": "Referer",
        "origin": "Origin",
        "range": "Range",
        "cookie": "Cookie",
        "authorization": "Authorization",
        "accept": "Accept",
        "accept-language": "Accept-Language",
        "x-requested-with": "X-Requested-With"
    };
    return known[lower] || name;
}

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
    try {
        const parsed = new URL(url);
        if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") || !parsed.hostname || parsed.username || parsed.password) return;
    } catch (e) { return; }
    
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
        // Age pruning alone is not a hard bound when a page emits many unique URLs
        // inside one minute. Drop the oldest insertion(s) to cap retained state.
        while (seenCandidates.size > 250) {
            const oldestKey = seenCandidates.keys().next().value;
            if (oldestKey === undefined) break;
            seenCandidates.delete(oldestKey);
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
                const value = typeof h.value === "string" ? h.value : "";
                if (isCaptureHeader(name) && value.length > 0 && value.length <= 8192 &&
                    !/[\r\n\u0000]/.test(value) && Object.keys(headers).length < 32) {
                    headers[canonicalHeaderName(h.name)] = value;
                }
            }
        }
        requestHeadersMap.set(details.requestId, { url: details.url, headers });
        if (requestHeadersMap.size > 64) {
            const firstKey = requestHeadersMap.keys().next().value;
            requestHeadersMap.delete(firstKey);
        }
        
        // Also cache by URL and Origin for correlation with DOM events
        const domHeaders = {};
        for (const [name, value] of Object.entries(headers)) {
            if (["user-agent", "referer", "origin"].includes(name.toLowerCase())) {
                domHeaders[canonicalHeaderName(name)] = value;
            }
        }
        urlHeadersMap.set(details.url, domHeaders);
        try {
            const parsed = new URL(details.url);
            urlHeadersMap.set(parsed.origin, domHeaders);
        } catch (e) {}
        if (urlHeadersMap.size > 96) {
            const firstKey = urlHeadersMap.keys().next().value;
            urlHeadersMap.delete(firstKey);
        }
    },
    { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
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
        if (![301, 302, 303, 307, 308].includes(details.statusCode)) {
            requestHeadersMap.delete(details.requestId);
        }
    },
    { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
    ["responseHeaders"]
);

// 3. Handle messages from Content Scripts
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
