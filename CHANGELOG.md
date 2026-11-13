# Changelog

Notable changes for **VRKA Android** (`com.mvrk.vrka`). For full checksums, OpenPGP manifests, and test logs, see [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## [5.1.5] - 2026-11-13

Production release adding child-job playlist expansion, composite playlist selection dialogs, hardened MediaStore publication and recovery, in-app updater compatibility for current versions, Download page PNG wordmark branding, Android 16 User-Initiated Data Transfer (UIDT) notifications, and Issue #23 queue and deletion remediation (`versionCode 50105`, `com.mvrk.vrka`).

- **Playlist child-job expansion & concurrency:** YouTube and multi-item playlists expand atomically into independent child jobs (`isPlaylist=false`) with bounded concurrency (up to 4 parallel workers), unique job IDs, independent progress/history tracking, and graceful handling of aggregate size estimations.
- **Composite YouTube playlist-selection dialog:** Automatically detects composite YouTube URLs containing both video and playlist parameters upon pasting or input, presenting a dialog with "Entire Playlist" (enters expansion path) and "This Video Only" (strips playlist context). Suppressed when the Advanced playlist setting is already enabled.
- **Hardened MediaStore publication & recovery:** Direct insertion with `IS_PENDING=1` finalized to `0` without fragile `DISPLAY_NAME` renames. Retains returned MediaStore URIs as permanent file identities, prevents deleting verified media on transient publication errors, and maintains fail-closed staging recovery.
- **In-app updater equal/older manifest compatibility:** When an installed app is at or newer than a valid release manifest (`versionCode <= installedVersionCode`), the updater cleanly reports "You are already using the latest version" instead of a confusing downgrade error, while preserving strict anti-downgrade protections.
- **Android 16 UIDT & notification isolation:** Implements User-Initiated Data Transfer (`RUN_USER_INITIATED_JOBS` and `DownloadTransferJobService`) on API 36, ensuring concurrent downloads maintain independent progress and distinct completion notifications that resolve to published media.
- **Download page header branding:** Replaced text-based VRKA branding at the top-left of the Download page with the official transparent PNG wordmark, preserving natural 3:1 aspect ratio and vibrant purple rendering in both light and dark themes.
- **uBlock Origin XPI cryptographic verification (`XpiSignatureVerifier`):** Verifies bundled and downloaded `uBlock Origin` `.xpi` archives offline via PKCS#7 RSA signatures, pinned Mozilla Add-ons CA SPKI SHA-256, manifest digest coverage (`manifest.mf`/`mozilla.sf`), ZIP entry path/duplicate guards, and pinned SHA-256 checksums before installation.
- **Issue #23 storage & queue fixes (`VrkaDownloadManager` / Room):** Prevents multi-item queue stalling by isolating terminal completion ownership, ensuring cleanup obligations and queue dispatch proceed reliably across queued items and History deletions.
- **Media Detector & credential scoping hardening:** Removes unneeded `"webRequestBlocking"` permission from `media-detector/manifest.json`, bounds candidate storage with tab/origin isolation and header redaction, and strips credentials across cross-origin HTTP redirects and HTTPS->HTTP downgrades.
- **R8 / ProGuard rule narrowing (`app/proguard-rules.pro`):** Replaces blanket `com.yausername.**`, `org.bouncycastle.**`, `androidx.work.**`, and `commons-compress` zip keeps with targeted Jackson `@JsonProperty`, `ZipExtraField` 0-arg, JCA provider/digest/RSA/DRBG SPI, Room, and WorkManager rules.

## [5.1.4] - 2026-11-11

Security hardening, safer custom `yt-dlp` handling, stricter updates, and cleaner docs (`versionCode 50104`, direct upgrade from `v5.1.3`/`v5.1.2`).

- **Safer custom `yt-dlp` forks:** Requires confirming a SHA-256 untrusted-binary warning on install and re-verifies custom binaries on disk before running, falling back to stock if altered; reinstalling a verified tag repairs corrupted local files.
- **Locked-down commands & privacy:** Enforces shared `CustomCommandPolicy` blocking for shell hooks, path/proxy overrides, and credential headers (`Cookie`, `Authorization`), disables Android backups (`allowBackup=false`), redacts URL tokens/paths in failure logs, and adds one-tap private Browser Fallback session clearing.
- **Stricter in-app updates:** Caps updater HTTPS redirects at `5` hops across approved GitHub hosts and checks target `versionCode` through background staging and install.
- **Verified builds & cleaner docs:** Locks Gradle dependencies offline (`--dependency-verification=strict`), SHA-pins GitHub Actions, tracks bundled native asset hashes (`docs/native-binaries.json`), keeps a single `main` branch, and replaces old docs/framed images with guides under [docs/README.md](docs/README.md).
- **Tested:** `850` unit tests (`80` suites), `0` lint errors, API 36 CI provider tests, APK v2 + OpenPGP verification, and API 36 emulator `v5.1.3` -> `v5.1.4` upgrades.

## [5.1.3] - 2026-11-10

Major storage reliability upgrade moving queue, history, migration, and file-publication tracking into a single Room/SQLite database (`VrkaDatabase`, Room `2.8.5`), compatible with direct in-place upgrades from `v5.1.2`. See [docs/STORAGE_ARCHITECTURE.md](docs/STORAGE_ARCHITECTURE.md).

- **Atomic Room storage:** Unified queue, paged History (`30` rows/page), retries, publication batches, and cleanup tasks under startup readiness gating (`StorageRuntime`).
- **Ownership-checked crash recovery:** Retry attempts and batch outputs use unique IDs so stale callbacks cannot overwrite current transfers, and ambiguous legacy records are quarantined rather than dropped.
- **Auto-migration with safety snapshots:** Imports `v5.1.0`–`v5.1.2` JSON stores with hashed ledgers, WAL integrity checks, raw backup snapshots, and an 80-case test corpus.
- **Verified MediaStore & SAF publishing:** Checks output size and SHA-256 readback before marking jobs `DONE` and retains staging/provider cleanup tasks until ownership is confirmed.
- **OpenPGP & signing isolation:** Restricts updater signatures to RSA keys + `SHA-256`/`SHA-384`/`SHA-512` with matching hashed issuer fingerprints; isolates production signing keys from debug/CI tasks.
- **Verification:** `828` JVM tests (`78` suites), `0` lint errors, and `12` API 36 provider instrumentation tests (`10` SAF + `2` MediaStore).

## [5.1.2] - 2026-11-09

> [!IMPORTANT]
> **Upgrading from v5.0.2:** Install [`VRKA-Android-v5.1.2.apk`](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) manually over v5.0.2 without uninstalling first, then update normally.

- **Crash-safe file publishing (`PublicationJournal`):** Records transaction intents upfront using deterministic pending filenames (`.vrka_pending_${transactionId}_${filename}`) so interrupted MediaStore/SAF transfers can recover outputs cleanly or clean up orphans on restart.
- **Atomic playlist batching:** Interrupted multi-file playlists fail closed (`FAILED`, preserving completed URIs in History) instead of falsely reporting `DONE`.
- **Persistence ordering & corrupt journal protection:** Commits `JobStore` state before retiring journal entries or staging files, preserves damaged journals in bounded snapshots (`max 3`), handles providers lacking `renameDocument`, and fails closed on legacy journal-less in-flight jobs.
- **Verification:** `747` JVM tests (`73` suites), `0` lint errors, APK v2 RSA-4096 signed.

## [5.1.1] - 2026-11-08

- **Accurate resolution & size estimates:** Unified `-f` selector + 60 FPS sort between preflight sizing and transfers, cleared stale Browser Fallback page estimates before sizing candidates, summed video/audio streams once, and marked post-processed files (merge, trim, H.264, subtitles) as approximate.
- **Honest progress & history sizing:** Added `before_dl` actual resolution tracking, mixed-playlist resolution ranges, and aggregate media output sizes in History (excluding subtitle sidecars).
- **Storage & SAF fixes:** Preserves corrupt Queue/History files instead of resetting to empty (`JobStore` v2) and requires a valid SAF folder upfront on Android 8–9.
- **Privacy & accessibility:** Redacts URL credentials in diagnostics (keeping original URLs only on failure with opt-in export warning), enforces strict ISO-8601 updater timestamps, preserves Comet glow and Liquid navbar, and exposes determinate progress to screen readers across all 14 languages.
- **Verification:** `732` JVM tests (`72` suites), `0` lint errors, and ADB physical-device validation on OnePlus 11 (Android 16 / SDK 36).

## [5.1.0] - 2026-10-31

> [!IMPORTANT]
> **Upgrading from v5.0.2:** Requires a one-time manual APK install (install v5.1.2 or newer over v5.0.2 without uninstalling) because the v5.0.2 updater rejects `.download.tmp` staging filenames.

- **14 languages + full Arabic RTL:** Localized UI (Arabic, Bengali, Simplified Chinese, English, French, German, Gujarati, Hindi, Kannada, Portuguese BR/PT, Spanish, Tamil, Telugu) plus System Default.
- **Preflight sizing & AMOLED progress UI:** Added pre-download size estimates on Download and Share Sheet cards, directional luminous wake progress bar, GeckoView `159.0.20261022150525`, and custom `yt-dlp` runtime selection.
- **OpenPGP self-updater:** Authenticates release manifests against pinned RSA-4096 trust anchor `0xD519380BEBA1B9F7` with expiration, monotonic anti-rollback, strict hash algorithms (`SHA-256`+), and fixed `.download.tmp` staging checks.

## [5.0.2] - 2026-10-05

- Fixed in-app release detection and APK asset selection, and moved background update downloads and network retries to `WorkManager` with improved staging verification.

## [5.0.1] - 2026-10-05

- Enforced strict canonical version tags, fail-closed updater errors, HTTPS host/redirect validation, and pre-install package ID, version-code downgrade, and signing-certificate checks (backed by updater security tests).

## [5.0] - 2026-10-04

- **Liquid Glass UI & workflows:** Introduced swipeable 4-tab UI, Android Share Sheet intake, multi-select History management, and optional bounded concurrent downloads (2–4 jobs; sequential default).
- **Engine & processing:** Added persisted `JobStore`, real resolution tracking, `OutputPublisher`, subtitle selection/embedding, custom shell-free `yt-dlp` templates (`yt-dlp` `2026.08.19`), and optional `FFmpeg` `libx264` H.264 Compatibility Mode (`arm64-v8a`, Android API `26`–`36`).
- **Browser Fallback:** Switched to GeckoView `156.0.20260921121718` with bundled `uBlock Origin` `1.74.0` and a private stream-discovery bridge, hardened origin header isolation, diagnostics redaction, and bounded WorkManager component updates.

## [4.5.3] - 2026-09-14

- Updated `yt-dlp` (`2026.08.19`), `uBlock Origin` (`1.74.0`), and `Puemos` (`5.5.0`); moved app/component updaters to persistent `WorkManager` workers with single-flight guards and 24-hour startup update gating.

## [4.5.2] - 2026-09-09

- Fixed BouncyCastle registration and added R8 rules for `yt-dlp` signature verification.

## [4.5.1] - 2026-09-09

- Added in-app GitHub release checker, strict APK naming and redirect validation, improved download location UX, and native Opus post-processing.

## [4.5.0] - 2026-09-09

- Added independent component updaters and release signing/verification hardening.

## [4.0.3] - 2026-09-07

- Security hardening and dependency/session verification.

## [4.0.2] - 2026-09-07

- Hardened component updater verification, session management, and error bounds.

## [4.0.1] - 2026-09-06

- Fixed YouTube quality selector regression and added Diagnostics UI.

## [4.0.0] - 2026-09-05

- Added embedded GeckoView browser fallback (`uBlock Origin` + `Puemos` discovery), AMOLED-black theme, and floating navigation pill.

## [1.0.0] - 2026-07-29

- Initial VRKA Android release.
