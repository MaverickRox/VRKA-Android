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

## 5.0.2

- Fixed in-app application update detection and APK selection.
- Improved background application updates with persistent WorkManager downloads.
- Added retry handling for temporary network failures and connection interruptions.
- Improved APK staging, verification, and update-ready notification handling.
- Improved update installation and recovery behavior.

## 5.0

- Redesigned the interface with the Liquid Glass navigation system and horizontal swipe navigation.
- Added configurable concurrent downloads with bounded scheduling and independent cancellation/failure handling.
- Added Android Share Sheet support with robust shared-URL extraction and Download URL pre-filling.
- Added actual downloaded-resolution tracking and improved Queue/History media metadata.
- Added safer History management with record-only deletion, file deletion, multi-select, batch actions, and destructive-action safeguards.
- Improved download state persistence across process restarts and hardened terminal job-state handling.
- Restored and hardened Browser Fallback discovery and transfer handoff through GeckoView and yt-dlp.
- Improved Browser Fallback reliability by separating browser UI/session lifecycle from active transfers and removing blocking worker operations.
- Added validated custom yt-dlp argument templates with shell-free execution, safe staging/output handling, and fallback isolation.
- Improved Android yt-dlp compatibility, including handling of unsupported impersonation targets.
- Added optional, default-off H.264 Compatibility Mode using local FFmpeg conversion and post-conversion validation.
- Reworked subtitle handling with targeted language selection, rate-limit/failure isolation, container-aware embedding, and final subtitle-track verification.
- Improved YouTube subtitle handling for creator/manual captions and automatic-caption fallback.
- Upgraded GeckoView to 156.0.20260921121718 and improved browser-component/runtime reporting.
- Improved component updater reliability, installed-version detection, state transitions, cancellation, and channel switching.
- Hardened request/header handling, private browser-candidate state, diagnostics, output validation, and staged-file cleanup.
- Improved Settings descriptions and component/status presentation to reflect the final production architecture.

## Known Limitations

- Browser Fallback downloads do not currently fetch subtitles.
- H.264 Compatibility Mode is optional and disabled by default.
- Browser Fallback remains dependent on site discovery and yt-dlp/site compatibility.
