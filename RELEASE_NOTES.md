# VRKA Android 5.0

## Updates

- **yt-dlp**: Bundled runtime updated to `2026.08.19`.
- **Component Version Detection**: Dynamic installed-version resolution reading active zipapp, runtime `versionName()`, and bundled raw resource without hardcoded fallbacks.
- **Update Checks Hardening**: Bounded network reads (64 KiB cap), 6-second socket connect/read timeouts, 8-second component deadline, and cooperative cancellation propagation. Single-flight batch updater with rapid-tap debounce and spam resilience.
- **GeckoView Browser Fallback**: Passive media discovery observing network requests and DOM media elements for anti-bot or client-side player challenges; standard yt-dlp transfer handoff with sanitized public URL and header validation.
- **Liquid-Glass Navigation**: Equal slot-based indicator geometry across all destinations (Queue, Download, History, Settings), layout-offset backdrop sampling, and AGSL lens transformation with 1.35x magnification, edge refraction, and chromatic dispersion.
- **Custom yt-dlp Templates**: Named argument templates with parameter validation against RFC 7230, rejecting shell hooks, file paths, credentials, and proxies.
- **uBlock Origin**: Bundled official signed WebExtension for browser request filtering.
- **Component Cleanup**: Retired Puemos component removed from active production UI, extension registration, and updater routes, with one-time migration cleanup.
- **H.264 Compatibility Mode**: Optional conversion mode with FFmpeg validation before publication.

## Verification

- **348 unit tests passed** across 33 test suites (0 failures, 0 skipped).
- **Release lint** completed with 0 errors and 0 fatal findings (67 non-fatal warnings, 1 hint).
- **Production signing** verified using APK Signature Scheme v2 with 4096-bit RSA key.
- **Physical device validation** completed and accepted by maintainer on OnePlus 11 (Android 16, arm64-v8a).

## APK

- **File:** `VRKA-Android-v5.0.apk`
- **Size:** `155,334,093` bytes (~148.1 MB)
- **SHA-256:** `D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`
- **Signing Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Package ID:** `com.mvrk.vrka`
- **Version:** `5.0` (`versionCode 50000`)
- **Native ABI:** `arm64-v8a`
- **Minimum / Target SDK:** 26 / 36
