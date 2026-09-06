/**
 * VRKA Media Detector Content Script
 * DOM Media & Stream Observer with User Interaction & Dimension Analysis
 */

(function() {
    let lastUserInteractionTs = 0;

    // Track user gestures on the page (clicks, touches, keys)
    function registerUserInteraction() {
        lastUserInteractionTs = Date.now();
    }

    window.addEventListener("click", registerUserInteraction, true);
    window.addEventListener("touchstart", registerUserInteraction, true);
    window.addEventListener("pointerdown", registerUserInteraction, true);
    window.addEventListener("keydown", registerUserInteraction, true);

    function isAdOrNuisanceUrl(url) {
        if (!url) return 0;
        const lower = url.toLowerCase();
        // Common ad server signatures
        if (lower.match(/\b(doubleclick|googlesyndication|adnxs|adroll|popcash|exoclick|trafficjunky|adsterra|serving-sys|creativecdn|adsystem|banner|promo)\b/)) {
            return 20;
        }
        // Numeric live stream id with rendition suffix (live cams / sidebar widgets)
        if (lower.match(/\b\d{5,}\b.*_(\d{3,4})p(?:\.m3u8)?$/)) {
            return 10;
        }
        return 0;
    }

    function inspectMediaElement(el) {
        if (!el) return;
        const src = el.currentSrc || el.src;
        if (!src) return;
        const isAudio = el.tagName.toLowerCase() === "audio";
        const rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0 };
        const viewW = window.innerWidth || 1100;
        const viewH = window.innerHeight || 760;
        const width = el.videoWidth || Math.round(rect.width) || 0;
        const height = el.videoHeight || Math.round(rect.height) || 0;
        const area = (rect.width * rect.height);
        const isSmallVideo = !isAudio && area > 0 && area < (0.25 * viewW * viewH);
        const isPrimary = !isSmallVideo && (width >= 320 && height >= 180);

        const userStarted = (Date.now() - lastUserInteractionTs < 15000) || isPrimary;
        const playing = !el.paused && el.readyState >= 2;
        const duration = (el.duration && !isNaN(el.duration) && el.duration > 0) ? el.duration : 0;
        const resolution = (width > 0 && height > 0) ? `${width}x${height}` : "";

        if (src.startsWith("blob:") || src.startsWith("mediasource:")) {
            // MSE / Blob stream: notify background about active DOM player dimensions and status
            const nuisanceScore = isAdOrNuisanceUrl(window.location.href) + (isSmallVideo ? 5 : 0);
            browser.runtime.sendMessage({
                type: "DOM_PLAYER_ACTIVE",
                pageUrl: window.location.href,
                title: document.title || "",
                kind: isAudio ? "Audio" : "Video",
                resolution: resolution,
                userStarted: userStarted,
                primaryPlayer: isPrimary,
                playing: playing,
                width: width,
                height: height,
                duration: duration,
                nuisanceScore: nuisanceScore
            }).catch(() => {});
            return;
        }
        if (!src.startsWith("http://") && !src.startsWith("https://")) return;
        const nuisanceScore = isAdOrNuisanceUrl(src) + (isSmallVideo ? 5 : 0);

        browser.runtime.sendMessage({
            type: "DOM_MEDIA_DETECTED",
            url: src,
            pageUrl: window.location.href,
            title: document.title || "",
            kind: isAudio ? "Audio" : (src.includes(".m3u8") ? "HLS" : "Video"),
            resolution: resolution,
            mimeType: isAudio ? "audio/*" : "video/*",
            userStarted: userStarted,
            primaryPlayer: isPrimary,
            playing: playing,
            width: width,
            height: height,
            duration: duration,
            nuisanceScore: nuisanceScore
        }).catch(() => {});
    }

    // Inspect existing DOM media
    function scanPageMedia() {
        document.querySelectorAll("video, audio, source").forEach(inspectMediaElement);
    }

    // Observe dynamic elements
    const observer = new MutationObserver((mutations) => {
        for (const m of mutations) {
            for (const node of m.addedNodes) {
                if (node.nodeType === Node.ELEMENT_NODE) {
                    if (node.matches && (node.matches("video, audio, source"))) {
                        inspectMediaElement(node);
                    }
                    if (node.querySelectorAll) {
                        node.querySelectorAll("video, audio, source").forEach(inspectMediaElement);
                    }
                }
            }
        }
    });

    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
        scanPageMedia();
    } else {
        document.addEventListener("DOMContentLoaded", () => {
            if (document.body) {
                observer.observe(document.body, { childList: true, subtree: true });
                scanPageMedia();
            }
        });
    }

    // Media element event listeners
    document.addEventListener("play", (e) => {
        if (e.target && (e.target.tagName === "VIDEO" || e.target.tagName === "AUDIO")) {
            inspectMediaElement(e.target);
        }
    }, true);

    document.addEventListener("playing", (e) => {
        if (e.target && (e.target.tagName === "VIDEO" || e.target.tagName === "AUDIO")) {
            inspectMediaElement(e.target);
        }
    }, true);

    document.addEventListener("loadeddata", (e) => {
        if (e.target && (e.target.tagName === "VIDEO" || e.target.tagName === "AUDIO")) {
            inspectMediaElement(e.target);
        }
    }, true);
})();
