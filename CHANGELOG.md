# Changelog

All notable changes to VRKA Android are documented in this file.

## [5.0] - 2026-10-04

### Added
- GeckoView media-discovery Browser Fallback for eligible anti-bot or client-side player failures.
- Dynamic yt-dlp installed-version detection from runtime, active zipapp, and bundled raw resource.
- Liquid-glass navigation refinements with equal slot-based indicator geometry, live AGSL backdrop magnification, edge refraction, and chromatic dispersion.
- Optional H.264 Compatibility Mode with FFmpeg validation.
- Named Custom yt-dlp argument templates with security-hardened parameter restrictions.

### Changed
- Bundled yt-dlp updated to 2026.08.19.
- Bundled uBlock Origin updated to the current production WebExtension.
- Hardened component update checks with bounded network reads, 6-second socket timeouts, 8-second component deadlines, and cooperative cancellation.
- Single-flight batch updater protection with rapid-tap debounce and spam resilience.
- Improved Android UI, component row alignment, and monospace status badges.
- Retained v4.5.3 component behavior and channel selector styling.

### Removed
- Retired Puemos component from the active production component/update surface and purged legacy extension state.

### Security
- Custom yt-dlp templates validated against RFC 7230 and injection protection, rejecting shell hooks, file paths, and credentials.
- Browser Fallback transfers bound to public page source with origin-validated headers and dropped custom template state.

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
