# Changelog

All notable changes to VRKA Android are documented here. Release entries describe the production source for the corresponding release.

## [5.1.1] - 2026-11-08

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 STILL REQUIRE A ONE-TIME MANUAL INSTALL.** The v5.0.2 in-app updater cannot repair the code already installed in that old client. Download and install `VRKA-Android-v5.1.1.apk` from the official GitHub release page over v5.0.2 without uninstalling. Application data and settings are preserved.

### Corrected download size estimation
- Resolution chips, Best available, the confirmation estimate, and the transfer now share one format-selection contract. Chips no longer choose a stream by maximum bitrate; they use yt-dlp's revealed codec preference and the same resolution/frame-rate ranking as the canonical plan.
- The metadata preflight now uses the same `-f` format selector and 60 fps sort as the transfer, so yt-dlp's `requested_formats` are authoritative instead of a local approximation of its sort order.
- Resolution changes request and display the size for the newly selected format; the document cache is keyed by format selection and the UI rejects late results from older selections.
- Separate video/audio components are summed once, alternate resolutions and unrelated manifest variants are excluded, `filesize_approx` is never labelled exact, invalid bitrates and unknown durations produce an honest unavailable state, and byte overflow is rejected.
- Browser Fallback no longer keeps the page-level estimate after selecting a candidate. It clears that stale value and performs a best-effort estimate for the actual candidate.
- Post-processing (merge, trim, H.264 conversion, metadata, embedded subtitles, sponsor cuts) downgrades an otherwise exact source size to approximate.

### Selection, progress and history consistency
- Removed the disconnected `StreamSelectionCoordinator`; the Home screen's request state is now the single source of truth for URL, mode, quality, audio configuration and confirmation.
- Home estimates are keyed to the exact request that produced them, so an old 2160p result cannot appear after selecting 1440p or replace a newer result.
- Added a `before_dl` actual-resolution marker. The manager collects per-item heights and reports an honest mixed-playlist range instead of the last item.
- Active progress now identifies the current video/audio/media stream and playlist item. A `before_dl` stream marker supplies authoritative format ID, extension, vcodec/acodec and height; destination extension is only a fallback. A revised yt-dlp total no longer creates a false second stream or changes a video transfer into “Downloading audio.”
- Percentage, numerator and denominator describe the same current stream/item; a revised denominator is marked approximate. Aggregate final size is reported only after all media outputs are known.
- Playlist final and History sizes are the sum of published media outputs, excluding subtitle sidecars.
- Diagnostic text sanitizes URLs embedded in titles, summaries, details and error tails, preserving scheme/host/path while redacting query/fragment credentials.
- Diagnostics retain the original source URL only for failed downloads; successful downloads and successful retries do not keep source URLs. Export shows a warning and requires an explicit opt-in to include the full original source URL.

### Reliability and security
- JobStore no longer overwrites malformed queue/history files with an empty list. It preserves a bounded recovery copy, salvages valid records, reports skipped/unsupported schema states through diagnostics, uses schema version 2, and returns explicit save results. A failed publish-marker write now aborts publication instead of risking duplicates.
- Android 8 and Android 9 require a valid user-selected SAF folder before a download is accepted; revoked permissions reopen the folder prompt. API 29+ keeps the default Downloads/VRKA behavior.
- Browser Fallback diagnostics sanitize URLs before persistence and export, preserving scheme/host/route while redacting sensitive query values, encoded/case-varied parameter names, duplicates, and credential-like path segments.
- Updater timestamps use strict ISO-8601 parsing. Impossible calendar dates, incomplete values, and trailing text are rejected without weakening signature, skew, or anti-rollback checks.
- Updater cryptographic tests no longer look for a developer release key. They generate temporary OpenPGP keys and always execute; a regression guard scans test sources for production-key references.

### Accessibility and UI
- The comet progress bar exposes a determinate progress range, current progress, and a localized label to accessibility services without adding a duplicate percentage announcement.
- Comet-style download progress glow and the liquid sliding navbar are preserved.
- New destination and progress labels were added to all 14 language resources. The new translations are functional but still require native-language review.

### Validation
- 732 JVM unit tests across 72 suites pass with 0 failures, 0 errors, and 0 skipped.
- Lint reports 277 warnings, 3 hints, and 0 errors/fatal findings.
- Production release APK (`VRKA-Android-v5.1.1.apk`) built and verified: APK Signature Scheme v2 (RSA 4096-bit, SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).
- End-to-end device validation executed on physical OnePlus 11 (Android 16, SDK 36, ARM64) over ADB: verified 4K, 2K, and Best Available estimate synchronization (~54.75 MB), neutral "Downloading media" state during active transfer, speed/ETA calculations, and finalized 52.2 MiB output in Downloads/VRKA.

## [5.1.0] - 2026-10-31

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 MUST DOWNLOAD AND INSTALL v5.1.0 DIRECTLY FROM THE OFFICIAL GITHUB RELEASE PAGE. THE v5.0.2 IN-APP UPDATER CANNOT COMPLETE THIS UPDATE. DO NOT USE THE RETRY BUTTON.**
> 
> **OFFICIAL DOWNLOAD PAGE:**
> https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0
> 
> **INSTALLATION:** Download `VRKA-Android-v5.1.0.apk` from the Assets section and install it over your existing VRKA installation. DO NOT UNINSTALL v5.0.2 FIRST.

### What's New in 5.1.0
- Added full interface localization for 14 languages: Arabic, Bengali, Simplified Chinese, English, French, German, Gujarati, Hindi, Kannada, Portuguese (Brazil), Portuguese (Portugal), Spanish, Tamil, and Telugu, alongside System Default language selection.
- Added preflight download size estimation to the download screen and Android Share Sheet, displaying approximate transfer size before starting downloads.
- Redesigned download progress indicator with an AMOLED-optimized directional luminous wake.
- Updated Mozilla GeckoView engine to 159.0.20261022150525.

### In-App Updater Security and Reliability
- Upgraded the in-app update mechanism with cryptographically authenticated release manifests signed with an OpenPGP detached signature (`VRKA-Android-v5.1.0.manifest.json.asc`).
- Pinned trust anchor using 4096-bit RSA OpenPGP release signing key (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`).
- Implemented anti-freeze (cryptographic expiration timestamp enforcement) and anti-rollback (strictly monotonic metadata revision numbers).
- Enforced strict cryptographic policy rejecting insecure algorithms (MD5, SHA-1, RIPEMD160, DSA).
- Fixed client-side temporary file staging verification defect (`.download.tmp` filename check) by separating archive structure verification on temporary downloads from final staged APK validation.
- All future in-app updates starting from v5.1.0 will seamlessly update via the in-app updater without manual intervention.
  - **v5.1.1 status:** This path has not been tested for v5.1.1 because no signed v5.1.1 manifest or APK exists. Do not treat it as verified until a production-signed candidate is published and the upgrade is exercised.

### Improvements
- Added support for validating and selecting custom yt-dlp runtimes in Settings.
- Improved component update state transitions for yt-dlp and uBlock Origin, separating background version resolution from active update checks.
- Unified format and quality selection across direct downloads and the Android Share Sheet.
- Refined notification layout to show a clean single application icon without duplicate branding.
- Clarified browser subsystem status presentation across active, idle, and uninitialized states.
- Enhanced navigation bar and tab switching performance with draw-phase optimizations.

### Fixes
- Fixed an issue where declining a startup component update could leave Settings displaying a persistent checking state.
- Fixed component version badge wrapping and layout clipping on compact displays.
- Fixed an issue where long translated labels could overlap or push controls off-screen.
- Corrected notification channel setup and download completion dispatch.
- Fixed recovery and cleanup logic for interrupted long-running background transfers.

### Compatibility
- Requires Android 8.0 (API level 26) or higher.
- Built for 64-bit ARM devices (arm64-v8a).

### Known Limitations
- Browser Fallback transfers currently do not extract subtitles; subtitle options apply to direct yt-dlp downloads.
- H.264 Compatibility Mode is optional, disabled by default, and requires local FFmpeg conversion.
- Live stream detection depends on site player implementations and yt-dlp extractor support.
- Due to a client-side verification check on temporary download files in the v5.0.2 binary, upgrading from v5.0.2 requires a one-time manual installation of the v5.1.0 APK. Application data and settings are preserved.

## [5.0.2] - 2026-10-05

### Fixed and Improved
- Fixed in-app application update detection and APK selection.
- Improved background application updates with persistent WorkManager downloads.
- Added retry handling for temporary network failures and connection interruptions.
- Improved APK staging, verification, and update-ready notification handling.
- Improved update installation and recovery behavior.

## [5.0.1] - 2026-10-05

### Fixed
- Restored application update detection for stable releases by requiring canonical three-part tags (`v5.0.1`) and the exact matching APK asset (`VRKA-Android-v5.0.1.apk`). The 4.5.3 updater parsed `v5.0` as `5.0.0`, but rejected the two-part APK filename and incorrectly treated the missing asset as “up to date.”
- Invalid, draft, prerelease, duplicate, malformed, or mismatched release metadata now fails closed instead of being reported as “up to date”; automatic checks remain quiet while manual checks show the error.
- Update downloads now validate every HTTPS redirect hop, enforce the approved GitHub host/port policy and hop limit, and accept only the exact APK asset for the declared version.
- Before opening Android’s installer, the app now verifies the private update-cache location, APK package identity, canonical filename/version name, strictly increasing version code, and matching installed signing certificate. Missing or mismatched verification metadata is rejected.

### Tests
- Added updater regression coverage for 5.0.1 and 4.5.3 version ordering, release and asset validation, automatic/manual check behavior, malformed metadata, download URL/redirect policy, downgrade rejection, and APK install metadata and signer checks.

## [5.0] - 2026-10-04

### User interface and navigation
- Delivered the Golden 5.0 interface with the Liquid Glass navigation bar, shared backdrop treatment, and swipeable pager navigation across the main sections.
- Refined the four-slot navigation indicator geometry and Settings controls, including component status and update presentation.
- Added Android share-sheet URL intake and sharing of completed output files.

### Downloads and queue
- Added an optional, persisted concurrency scheduler with a configurable limit of two to four simultaneous jobs; sequential processing remains the default.
- Kept normal acquisition on yt-dlp and added task/job persistence (`JobStore`) so queued and active work can be restored after process recreation.
- Added actual media resolution tracking, format and quality selection, video/audio extraction options, subtitle language/download/embed controls, and clearer queue/history management.
- Added validated output publication through `OutputPublisher`, with FFmpeg/ffprobe used for local assembly, conversion, and media checks.
- Added optional H.264 Compatibility Mode, off by default, which validates local FFmpeg `libx264` availability before converting staged media.

### Browser Fallback and media discovery
- Added GeckoView Browser Fallback for eligible extraction failures, with the media-detector extension and Gecko bridge discovering candidate media from browser requests and page media elements.
- Routed a selected candidate back through the standard yt-dlp probe and transfer path. Browser Fallback does not fetch subtitles, and FFmpeg is reserved for local post-processing and validation.
- Added GeckoView 156.0.20260921121718 and bundled uBlock Origin 1.74.0 for the browser session; removed the retired Puemos updater and active extension surface.

### Settings, components, and security
- Added named custom yt-dlp argument templates with validated parameters and restrictions against shell execution, output/path overrides, and unsafe credential handling.
- Added dynamic installed yt-dlp version detection from the active runtime/zipapp or bundled raw resource, component update presentation, and hardened persistent component update operations.
- Hardened custom headers, browser-session handoff, diagnostics redaction, and component update operations with bounded reads, six-second socket timeouts, an eight-second deadline, cooperative cancellation, and single-flight checks.
- Refined component status rows and monospace badges while retaining the Stable/Nightly channel selector.
- Bound Browser Fallback transfers to the public page source, validated captured headers by origin, and dropped custom template state before the yt-dlp handoff.

### Runtime and compatibility
- Updated the bundled yt-dlp runtime to 2026.08.19 and added Android compatibility fixes for its runtime and packaged execution path.
- Preserved persisted JobStore data, download history, destination permissions, and system installer integration.
- Kept support scoped to the shipped `arm64-v8a` package, Android API 26 minimum, and API 36 target.

## [4.5.3] - 2026-09-14

### Changed
- Updated bundled yt-dlp to 2026.08.19.
- Updated bundled uBlock Origin to 1.74.0 (gorhill/uBlock official signed WebExtension).
- Updated bundled Puemos to 5.5.0 (puemos/hls-downloader official WebExtension ID `{e3ec0551-9bfa-4233-b9dd-6b36f6a80962}`).
- Improved component update handling with authoritative batch state machine.
- Prevented duplicate update operations from repeated button presses (single-flight batchMutex guard).
- Added persistent background update operations via Android WorkManager (`ComponentUpdateWorker`, `AppUpdateDownloadWorker`) for process-death survival.
- Added 24-hour startup update check gate with non-blocking Combined Dialog.
- Added persistent app updater background download handling.
- Improved runtime verification of installed WebExtensions by querying the live GeckoView WebExtensionController.
- Updated documentation and release information to reflect verified components.

## [4.5.2] - 2026-09-09

### Fixed
- Fixed BouncyCastle provider registration for yt-dlp signature verification.
- Added R8 / Proguard rules for crypto provider compatibility.

## [4.5.1] - 2026-09-09

### Added
- Added in-app GitHub release update checker and dialog.
- Enforced APK naming convention and strict redirect validation during download.
- Improved download location UX and header propagation.
- Aligned native Opus audio post-processing.

## [4.5.0] - 2026-09-09

### Added
- Added independent in-app component updaters for bundled tools.
- Added verification matrix and release signing hardening.

## [4.0.3] - 2026-09-07

### Security
- Corrective hardening and security verification across dependencies and session management.

## [4.0.2] - 2026-09-07

### Security
- Hardened component updater and dependency verification.
- Strengthened session management and error bounds.

## [4.0.1] - 2026-09-06

### Fixed
- Fixed YouTube quality selector regression.
- Added Diagnostics UI.

## [4.0.0] - 2026-09-05

### Added
- Added embedded GeckoView browser fallback subsystem.
- Integrated full uBlock Origin and Puemos stream observation.
- Added AMOLED-black visual redesign and floating navigation pill.

## [1.0.0] - 2026-07-29

### Added
- Initial baseline release of VRKA Android.
