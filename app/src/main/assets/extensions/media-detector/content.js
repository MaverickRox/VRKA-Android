/**
 * VRKA Media Detector Content Script
 * DOM Media & Stream Observer
 */

(function() {
    function inspectMediaElement(el) {
        if (!el) return;
        const src = el.currentSrc || el.src;
        if (!src) return;
        if (src.startsWith("blob:") || src.startsWith("mediasource:")) {
            // Blob URL stream - network sniffer will catch underlying requests
            return;
        }
        if (!src.startsWith("http://") && !src.startsWith("https://")) return;

        const isAudio = el.tagName.toLowerCase() === "audio";
        const width = el.videoWidth || 0;
        const height = el.videoHeight || 0;
        const resolution = (width > 0 && height > 0) ? `${width}x${height}` : "";

        browser.runtime.sendMessage({
            type: "DOM_MEDIA_DETECTED",
            url: src,
            pageUrl: window.location.href,
            title: document.title || "",
            kind: isAudio ? "Audio" : (src.includes(".m3u8") ? "HLS" : "Video"),
            resolution: resolution,
            mimeType: isAudio ? "audio/*" : "video/*"
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

    document.addEventListener("loadeddata", (e) => {
        if (e.target && (e.target.tagName === "VIDEO" || e.target.tagName === "AUDIO")) {
            inspectMediaElement(e.target);
        }
    }, true);
})();
