# Changelog

All notable changes to VRKA Android are documented here. Release entries describe the production source for the corresponding release.

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
