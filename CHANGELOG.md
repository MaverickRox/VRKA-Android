# Changelog

Notable changes for **VRKA Android** (`com.mvrk.vrka`). For full checksums, OpenPGP manifests, and test logs, see [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## [5.1.4] - 2026-11-11

Focused on security hardening, safer custom `yt-dlp` handling, stricter update verification, and cleaner documentation. Keeps the Room storage engine and signing key for direct upgrades from `v5.1.3` and `v5.1.2`.

- **Safer custom `yt-dlp` forks:** Installing a third-party fork now requires confirming an explicit untrusted-runtime warning with repo, version, and full SHA-256. Active binaries are re-hashed on disk before execution and fall back fail-closed to stock if modified; reinstalling a verified tag repairs corrupted local files unless the upstream hash changed.
- **Locked-down custom commands & backups:** Settings templates and download requests share one `CustomCommandPolicy` blocking shell hooks (`--exec*`, `--downloader*`), output/proxy overrides, insecure TLS flags, and persistent credential headers (`Cookie`, `Authorization`); `android:allowBackup="false"` is enforced.
- **Hardened in-app updates:** Enforces `MAX_REDIRECTS = 5` across approved GitHub hosts and persists target `versionCode` checks across background downloads and installer handoff.
- **Cleaner privacy & diagnostics:** Failure logs redact query tokens, signed CDN URLs, and local paths by default, warn before opt-in full-URL exports, and expose a one-tap private Browser Fallback wipe.
- **Audited supply chain & docs:** Removed `mavenLocal()`, constrained Mozilla Maven by group regex, committed `app/gradle.lockfile` + SHA-256 verification metadata (`--dependency-verification=strict`), SHA-pinned GitHub Actions, hashed bundled binaries (`docs/native-binaries.json`), moved to a single `main` branch policy, replaced the framed showcase with unframed captures, and reorganized guides under [docs/README.md](docs/README.md).
- **Verification:** `850` JVM tests (`80` suites), `0` lint errors, GitHub Actions CI + API 36 provider instrumentation, APK v2 + OpenPGP checks, and API 36 emulator upgrades from `v5.1.3`.

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
