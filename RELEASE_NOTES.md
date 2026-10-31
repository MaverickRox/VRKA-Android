# VRKA Android 5.1.0

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 MUST DOWNLOAD AND INSTALL v5.1.0 DIRECTLY FROM THE OFFICIAL GITHUB RELEASE PAGE. THE v5.0.2 IN-APP UPDATER CANNOT COMPLETE THIS UPDATE. DO NOT USE THE RETRY BUTTON.**
> 
> **OFFICIAL DOWNLOAD PAGE:**
> https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0
> 
> **INSTALLATION:** Download `VRKA-Android-v5.1.0.apk` from the Assets section and install it over your existing VRKA installation. DO NOT UNINSTALL v5.0.2 FIRST.

VRKA Android 5.1.0 introduces comprehensive interface localization across 14 languages, preflight download size estimation, an AMOLED-optimized luminous wake progress indicator, custom yt-dlp runtime validation, an updated GeckoView 159 engine, and a cryptographically authenticated in-app updater architecture.

## Highlights

- **14 Languages & System Default:** Complete interface localization supporting Arabic (with full RTL support), Bengali, Simplified Chinese, English, French, German, Gujarati, Hindi, Kannada, Portuguese (Brazil), Portuguese (Portugal), Spanish, Tamil, and Telugu, with resource-key parity across all languages.
- **Preflight Download Size Estimation:** Accurate approximate size estimates displayed on the download card and Android Share Sheet prior to queueing or downloading.
- **Directional Luminous Wake Progress Bar:** Redesigned download progress renderer featuring an 18-track streak and cached analytic teardrop glow optimized for AMOLED displays.
- **Custom yt-dlp Runtime Management:** Settings now supports configuring and validating custom yt-dlp forks while keeping upstream OpenPGP signature verification for official releases.
- **GeckoView 159 Engine Update:** Bundled Mozilla GeckoView runtime updated to 159.0.20261022150525 with uBlock Origin 1.74.0 and hardened private-session cleanup.
- **Notification & Share Sheet Refinements:** Streamlined notifications without duplicate large icons, and unified format selection on the compact floating share card.
- **Secure In-App Updater Redesign:** Cryptographically verified release manifest architecture (`VRKA-Android-v5.1.0.manifest.json`) authenticated by a detached OpenPGP signature (`VRKA-Android-v5.1.0.manifest.json.asc`). Pinned 4096-bit RSA OpenPGP release trust anchor (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) with anti-freeze and anti-rollback protections. Staging verification defect resolved by separating temporary archive integrity checks from post-rename staged APK checks.

## Release Verification

- **Production APK:** `VRKA-Android-v5.1.0.apk`
- **Package ID:** `com.mvrk.vrka`
- **Version Code:** `50100`
- **Version Name:** `5.1.0`
- **File Size:** 157,202,797 bytes
- **SHA-256:** `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
- **Signing Scheme:** APK Signature Scheme v2 (RSA 4096-bit)
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **Release Manifest:** `VRKA-Android-v5.1.0.manifest.json`
- **Detached OpenPGP Signature:** `VRKA-Android-v5.1.0.manifest.json.asc`
- **Exported Public Key:** `VRKA-Release-Public-Key.asc` (pinned fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`)

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
- Upgrading from v5.0.2 requires a one-time manual download and installation of the v5.1.0 APK from GitHub due to a client-side verification check on temporary download files in the v5.0.2 binary.
