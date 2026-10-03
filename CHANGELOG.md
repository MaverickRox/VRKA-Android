# Changelog

All notable changes to VRKA Android are documented in this file.

## [5.0] - 2026-10-03

### Added
- Optional H.264 Compatibility Mode converts local staged video only when the downloaded video is not H.264, then validates output before publication.
- Named Custom yt-dlp argument templates for normal queued download attempts.

### Fixed
- Eligible failures from a custom yt-dlp attempt can now enter the existing Browser Fallback flow. The fallback transfer clears custom template/header state and uses the standard yt-dlp request.
- Custom-template Delete now uses the same aligned, 48dp outlined control as Add and Edit and the destructive app color.
- Quality, audio format, bitrate, and mode selections are persisted when the user changes them.

### Changed
- Settings now distinguishes yt-dlp transfer, browser discovery, uBlock filtering, and Puemos component roles.
- Component versions are displayed as reported by runtime/package/update state; unverified components no longer appear Ready or Up to date.
- Browser media detection is described as direct media, HLS, and DASH discovery.
- Browser-session clearing text now matches the GeckoView clear flags and in-memory candidate cleanup.
- Subtitle text states that subtitle acquisition applies to the normal yt-dlp path; Browser Fallback does not currently fetch subtitles.
- Removed the obsolete stored ad-blocking toggle; uBlock remains enabled by fallback runtime behavior.

### Security
- Browser Fallback transfer construction retains the public page URL and drops custom template, custom argument, and user-defined header state before handoff.

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
- Verification matrix and release signing hardening.

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
- Embedded GeckoView browser fallback subsystem.
- Integrated full uBlock Origin and Puemos stream observation.
- AMOLED-black visual redesign and floating navigation pill.

## [1.0.0] - 2026-07-29

### Added
- Initial baseline release of VRKA Android.
