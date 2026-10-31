# VRKA Android 5.1.0

VRKA Android 5.1.0 introduces comprehensive interface localization across 14 languages, preflight download size estimation, an AMOLED-optimized luminous wake progress indicator, custom yt-dlp runtime validation, and an updated GeckoView 159 engine.

## Highlights

- **14 Languages & System Default:** Complete interface localization supporting Arabic (with full RTL support), Bengali, Simplified Chinese, English, French, German, Gujarati, Hindi, Kannada, Portuguese (Brazil), Portuguese (Portugal), Spanish, Tamil, and Telugu, with resource-key parity across all languages.
- **Preflight Download Size Estimation:** Accurate approximate size estimates displayed on the download card and Android Share Sheet prior to queueing or downloading.
- **Directional Luminous Wake Progress Bar:** Redesigned download progress renderer featuring an 18-track streak and cached analytic teardrop glow optimized for AMOLED displays.
- **Custom yt-dlp Runtime Management:** Settings now supports configuring and validating custom yt-dlp forks while keeping upstream OpenPGP signature verification for official releases.
- **GeckoView 159 Engine Update:** Bundled Mozilla GeckoView runtime updated to 159.0.20261022150525 with uBlock Origin 1.74.0 and hardened private-session cleanup.
- **Notification & Share Sheet Refinements:** Streamlined notifications without duplicate large icons, and unified format selection on the compact floating share card.

## Release Verification

- **Production APK:** `VRKA-Android-v5.1.0.apk`
- **Package ID:** `com.mvrk.vrka`
- **Version Code:** `50100`
- **Version Name:** `5.1.0`
- **File Size:** 157,192,535 bytes
- **SHA-256:** `CA5C74F0C59A98FE225D133A79E98A9E2913E8F82BFDAC0C841A27F2DCD1F28A`
- **Signing Scheme:** APK Signature Scheme v2 (RSA 4096-bit)
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

---

# VRKA Android 5.0.2

VRKA Android 5.0.2 improves background application update reliability, download persistence, and installation handling.

## Highlights

- **Background Update Persistence:** Application update downloads run independently in the background via WorkManager, continuing smoothly when VRKA is backgrounded.
- **Network Resilience:** Update downloads now automatically retry transient network failures (such as connection resets or aborts) with bounded backoff instead of terminating the task prematurely.
- **Safe Staging:** Update APKs are staged securely in private storage and thoroughly verified for package identity, version sequence, checksum integrity, and production signature before being marked ready to install.
- **Update-Ready Flow:** When an update finishes downloading in the background, a ready-to-install notification allows opening the update directly, and foregrounding the app displays the ready state for straightforward installation.

## Complete 5.0 Features Summary

- **Liquid Glass Interface:** Fluid AMOLED-black UI with glass navigation, custom typography, and responsive swipe navigation across screens.
- **Concurrent Queue:** Configurable concurrent downloads with persistent task state preserved across app restarts.
- **System Integration:** Android Share Sheet intake and native file sharing.
- **Browser Fallback & Discovery:** Passive stream detection in GeckoView with built-in uBlock Origin protection.
- **Media Engine:** yt-dlp runtime with dynamic version detection, custom argument templates, format selection, and subtitle extraction.
- **Compatibility Mode:** Optional on-device H.264 video conversion via local FFmpeg.
- **Security & Integrity:** Strict HTTPS redirect enforcement, domain validation, and APK signature verification.
