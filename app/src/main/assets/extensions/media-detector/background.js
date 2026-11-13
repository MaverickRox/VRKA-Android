/**
 * VRKA Android Media Discovery WebExtension Background Service
 * VRKA media discovery background worker and GeckoView native bridge.
 */

const MAX_REQUEST_HEADERS = 64;
const MAX_URL_HEADERS = 64;
const MAX_SEEN_CANDIDATES = 250;
const DOM_PLAYER_TTL_MS = 45000;

const seenCandidates = new Map();
const requestHeadersMap = new Map();
const urlHeadersMap = new Map();
const activeDomPlayerByTab = new Map();

const ALLOWED_REPLAY_CAPTURE_HEADERS = new Set([
    "user-agent", "referer", "origin", "range", "cookie",
    "accept", "accept-language", "x-requested-with"
]);
const FORBIDDEN_CAPTURE_HEADERS = new Set([
    "host", "content-length", "connection", "proxy-authorization", "proxy-connection",
    "set-cookie", "transfer-encoding", "upgrade"
]);

function isCaptureHeader(name) {
    const lower = name.toLowerCase();
    return (ALLOWED_REPLAY_CAPTURE_HEADERS.has(lower) || lower === "authorization" || lower.startsWith("x-")) &&
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

function parseSafeOrigin(rawUrl) {
    try {
        const parsed = new URL(rawUrl);
        if ((parsed.protocol !== "http:" && parsed.protocol !== "https:") || !parsed.hostname || parsed.username || parsed.password) {
            return null;
        }
        return parsed.origin;
    } catch (e) {
        return null;
    }
}

function isDefinitelyNonMediaAssetUrl(url) {
    if (!url) return true;
    const lower = url.toLowerCase();
    return Boolean(lower.match(/\.(jpg|jpeg|png|gif|svg|webp|ico|css|js|mjs|woff|woff2|ttf|otf|eot|map|wasm)(\?|$)/));
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

function setBounded(map, key, value, maxSize) {
    if (map.has(key)) {
        map.delete(key);
    }
    map.set(key, value);
    while (map.size > maxSize) {
        const oldestKey = map.keys().next().value;
        if (oldestKey === undefined) break;
        map.delete(oldestKey);
    }
}

function purgeTabState(tabId) {
    if (tabId === undefined || tabId === null) return;
    activeDomPlayerByTab.delete(tabId);
    for (const [reqId, entry] of requestHeadersMap.entries()) {
        if (entry && entry.tabId === tabId) {
            requestHeadersMap.delete(reqId);
        }
    }
    const tabPrefix = `${tabId}::`;
    for (const key of urlHeadersMap.keys()) {
        if (key.startsWith(tabPrefix)) {
            urlHeadersMap.delete(key);
        }
    }
    const seenPrefix = `${tabId}:`;
    for (const key of seenCandidates.keys()) {
        if (key.startsWith(seenPrefix)) {
            seenCandidates.delete(key);
        }
    }
}

function sendCandidateToNative(candidate, tabId = -1) {
    if (!candidate || !candidate.url) return;

    const url = candidate.url.trim();
    if (!parseSafeOrigin(url)) return;

    const lower = url.toLowerCase();
    if (lower.match(/\.(jpg|jpeg|png|gif|svg|webp|ico|css|js|woff|woff2|ttf|json)(\?|$)/)) {
        return;
    }

    const key = `${tabId}:${candidate.kind}:${url}`;
    const now = Date.now();
    if (seenCandidates.has(key)) {
        const lastSeen = seenCandidates.get(key);
        if (now - lastSeen < 15000) {
            return;
        }
    }
    seenCandidates.set(key, now);

    if (seenCandidates.size > MAX_SEEN_CANDIDATES) {
        for (const [k, t] of seenCandidates.entries()) {
            if (now - t > 60000) seenCandidates.delete(k);
        }
        while (seenCandidates.size > MAX_SEEN_CANDIDATES) {
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

// 1. Capture outgoing request headers scoped by requestId, tabId, and origin (bounded to 64 requests)
browser.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
        if (!details || !details.url) return;
        const origin = parseSafeOrigin(details.url);
        if (!origin) return;
        if (isDefinitelyNonMediaAssetUrl(details.url) || isAdOrNuisanceUrl(details.url) > 0) {
            return;
        }

        const tabId = typeof details.tabId === "number" ? details.tabId : -1;
        const headers = {};
        if (details.requestHeaders) {
            for (const h of details.requestHeaders) {
                const name = h.name.toLowerCase();
                const value = typeof h.value === "string" ? h.value : "";
                if (isCaptureHeader(name) && value.length > 0 && value.length <= 8192 &&
                    !/[\r\n\u0000]/.test(value) && Object.keys(headers).length < 32) {
                    // Never store raw secret bearer/custom X credentials in the JS header map;
                    // preserve a non-secret presence marker only for downstream 401/403 diagnostics.
                    const safeValue = ALLOWED_REPLAY_CAPTURE_HEADERS.has(name) ? value : "[REDACTED]";
                    headers[canonicalHeaderName(h.name)] = safeValue;
                }
            }
        }

        setBounded(
            requestHeadersMap,
            details.requestId,
            { url: details.url, origin: origin, tabId: tabId, headers: headers },
            MAX_REQUEST_HEADERS
        );

        // Cache ONLY non-credential playback headers by tab + url / origin for DOM correlation
        const domHeaders = {};
        for (const [name, value] of Object.entries(headers)) {
            if (["user-agent", "referer", "origin"].includes(name.toLowerCase())) {
                domHeaders[canonicalHeaderName(name)] = value;
            }
        }
        if (Object.keys(domHeaders).length > 0) {
            setBounded(urlHeadersMap, `${tabId}::${details.url}`, domHeaders, MAX_URL_HEADERS);
            setBounded(urlHeadersMap, `${tabId}::${origin}`, domHeaders, MAX_URL_HEADERS);
        }
    },
    { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
    ["requestHeaders"]
);

// 2. Sniff responses for media content types and extensions, cleaning request state on completion/redirect
browser.webRequest.onHeadersReceived.addListener(
    (details) => {
        if (!details || !details.url) return;
        const url = details.url;
        const currentOrigin = parseSafeOrigin(url);
        const tabId = typeof details.tabId === "number" ? details.tabId : -1;
        const lowerUrl = url.toLowerCase();

        const cachedReq = requestHeadersMap.get(details.requestId);
        // Consume/delete request entry unconditionally so redirects never carry prior-origin headers
        requestHeadersMap.delete(details.requestId);

        const sameScope = Boolean(
            cachedReq &&
            currentOrigin &&
            cachedReq.origin === currentOrigin &&
            cachedReq.tabId === tabId
        );
        const headers = sameScope && cachedReq.headers ? cachedReq.headers : {};

        let contentType = "";
        if (details.responseHeaders) {
            for (const h of details.responseHeaders) {
                const name = h.name.toLowerCase();
                if (name === "content-type") contentType = (h.value || "").toLowerCase();
            }
        }

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

        if (detectedKind && currentOrigin) {
            const adNuisance = isAdOrNuisanceUrl(url);
            const tabDomEntry = activeDomPlayerByTab.get(tabId);
            const activeDom = (tabDomEntry && (Date.now() - tabDomEntry.timestamp < DOM_PLAYER_TTL_MS))
                ? tabDomEntry.player
                : null;

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
            } else if (activeDom) {
                userStarted = activeDom.userStarted;
                primaryPlayer = activeDom.primaryPlayer;
                playing = activeDom.playing;
                width = activeDom.width;
                height = activeDom.height;
                duration = activeDom.duration;
                if (!resolution && activeDom.resolution) {
                    resolution = activeDom.resolution;
                }
            } else {
                userStarted = true;
                primaryPlayer = true;
                playing = true;
            }

            sendCandidateToNative({
                url: url,
                pageUrl: headers["Referer"] || "",
                title: activeDom ? activeDom.title : "",
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
            }, tabId);
        }
    },
    { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] },
    ["responseHeaders"]
);

function cleanupRequestEntry(details) {
    if (details && details.requestId !== undefined) {
        requestHeadersMap.delete(details.requestId);
    }
}

if (browser.webRequest.onBeforeRedirect) {
    browser.webRequest.onBeforeRedirect.addListener(
        cleanupRequestEntry,
        { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] }
    );
}
if (browser.webRequest.onCompleted) {
    browser.webRequest.onCompleted.addListener(
        cleanupRequestEntry,
        { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] }
    );
}
if (browser.webRequest.onErrorOccurred) {
    browser.webRequest.onErrorOccurred.addListener(
        cleanupRequestEntry,
        { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other"] }
    );
}

if (typeof browser !== "undefined" && browser.tabs) {
    if (browser.tabs.onRemoved) {
        browser.tabs.onRemoved.addListener((tabId) => purgeTabState(tabId));
    }
    if (browser.tabs.onUpdated) {
        browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
            if (changeInfo && changeInfo.url) {
                purgeTabState(tabId);
            }
        });
    }
}

// 3. Handle messages from Content Scripts
browser.runtime.onMessage.addListener((msg, sender) => {
    const tabId = (sender && sender.tab && typeof sender.tab.id === "number") ? sender.tab.id : -1;
    if (msg && msg.type === "DOM_PLAYER_ACTIVE") {
        activeDomPlayerByTab.set(tabId, {
            timestamp: Date.now(),
            player: {
                pageUrl: (sender && sender.tab ? sender.tab.url : msg.pageUrl) || "",
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
            }
        });
        while (activeDomPlayerByTab.size > 32) {
            const oldestTab = activeDomPlayerByTab.keys().next().value;
            if (oldestTab === undefined) break;
            activeDomPlayerByTab.delete(oldestTab);
        }
        return;
    }

    if (msg && msg.type === "DOM_MEDIA_DETECTED") {
        let headers = urlHeadersMap.get(`${tabId}::${msg.url}`);
        if (!headers) {
            const msgOrigin = parseSafeOrigin(msg.url);
            if (msgOrigin) {
                headers = urlHeadersMap.get(`${tabId}::${msgOrigin}`);
            }
        }
        headers = headers ? Object.assign({}, headers) : {};
        const effectivePageUrl = (sender && sender.tab ? sender.tab.url : msg.pageUrl) || "";
        if (!headers["Referer"] && effectivePageUrl &&
            !(effectivePageUrl.startsWith("https://") && msg.url && msg.url.startsWith("http://"))) {
            headers["Referer"] = effectivePageUrl;
        }

        sendCandidateToNative({
            url: msg.url,
            pageUrl: effectivePageUrl,
            title: msg.title || (sender && sender.tab ? sender.tab.title : ""),
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
        }, tabId);
    }
});
