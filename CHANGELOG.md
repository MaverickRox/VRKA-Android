# Changelog

All notable changes to VRKA Android are documented here. Release entries describe the production source for the corresponding release.

## [5.1.4] - 2026-11-11

VRKA Android 5.1.4 (`versionCode 50104`) ships all post-v5.1.3 security, runtime, updater, supply-chain, and documentation improvements while preserving the Room storage engine and production signing identity (`com.mvrk.vrka`).

### User Features & Runtime Controls
- Require an explicit **Untrusted custom runtime** confirmation dialog displaying fork identity and full SHA-256 before installing or activating custom `yt-dlp` zipapps, plus visible SHA-256 metadata in Settings (`SettingsScreen`).
- Repair locally corrupted custom `yt-dlp` binaries cleanly when reinstalling/updating the same tag if the upstream SHA-256 matches the verified record, while continuing to reject unexpected upstream binary mutations; ensure `YtDlpRuntimeManager` always resets `working = false` on early failure paths (`YtDlpRuntimeManager`).

### Reliability & Storage
- Persist `KEY_TARGET_VERSION_CODE` across `AppUpdateManager.downloadAndInstall`, `AppUpdateDownloadWorker.markReady`, and `restoreSavedUpdateState` so install-time archive verification checks exact target version codes in addition to semantic version strings and signer sets.
- Preserve Room (`VrkaDatabase`) schema version 1 and `SettingsRepository` backward compatibility for direct upgrades from v5.1.3 and v5.1.2 (and one-time v5.0.2 -> v5.1.2 bridge).

### Security & Privacy
- Share `CustomCommandPolicy` option/header/control-character validators directly with `DownloadRequestFactory.safeCustomArguments()` for consistent case-insensitive blocking of execution, path, proxy, and credential-header arguments without shell invocation.
- Re-verify installed custom `yt-dlp` SHA-256 hashes before use (`activeBinary()`, `applyActiveRuntime()`, `activateCustom()`), reverting fail-closed to stock if altered or missing.
- Clarify the privacy boundary as a narrower source-verifiable claim (`docs/PRIVACY_NETWORK.md`) and document all application-controlled GitHub destinations (`MAX_REDIRECTS = 5`, 5 approved GitHub hosts), custom runtime risk, and failure-only URL redaction.

### Supply Chain & CI/CD
- Remove unrestricted `mavenLocal()` from production resolution, restrict Mozilla Maven with an explicit group regex, commit `app/gradle.lockfile` + `gradle/verification-metadata.xml` (`--dependency-verification=strict`), pin all GitHub Actions workflows to 40-char commit SHAs, enforce `lintRelease` and `devRelease` R8 `mapping.txt` + `mapping.sha256` CI artifacts, normalize checked-in JS/key/manifest assets to LF in `.gitattributes`, and enforce the single permanent `main` development branch policy (Dependabot version/security PR creation disabled; GitHub vulnerability alerts active).
- Complete `docs/native-binaries.json` and `docs/NATIVE_BINARY_INVENTORY.md` with LF canonical hashes, stock `res/raw/ytdlp`, `res/raw/ytdlp_pubkey.asc`, and transitive AAR native `.so` libraries.

### Performance & UI
- Keep UI verification clean without compiler warnings in the Settings runtime section (removed dead local string lookup and gated the verified badge on `verifiedAt > 0L`).

### Compatibility & Migration
- Verified direct compatibility for upgrading existing v5.1.3 (`50103`) and v5.1.2 (`50102`) installations in place; retain historical documentation for the one-time v5.0.2 -> v5.1.2 bridge.

### Testing & Verification
- Added regression tests covering custom runtime tamper/repair/working-state reset (`YtDlpRuntimeManagerTest`), command/header filtering (`DownloadRequestFactoryTest`, `CustomCommandTemplatesTest`), v5.1.3 -> v5.1.4 updater selection/anti-downgrade (`AppUpdateManagerTest`), hermetic `K1 -> K2` key rotation (`ReleaseManifestVerifierTest`), and supply-chain/single-branch invariants (`SupplyChainPolicyTest`).

### Documentation & Presentation
- Remove the obsolete v5.1.1 framed showcase composite (`vrka-v5.1.1-showcase.png`), audit all screenshot captions against verified unframed captures, archive v5.0 / early v5.1.0 snapshot docs into `docs/archive/`, create structured `docs/user-guide/`, `docs/developer/`, `docs/security/`, `docs/release/`, and `docs/README.md` guides, and update `README.md`, `SECURITY.md`, `SECURITY_REVIEW.md`, `CURRENT_PRODUCTION_ARCHITECTURE.md`, `RELEASE_NOTES.md`, `RELEASE_EVIDENCE.md`, `KNOWN_LIMITATIONS.md`, `DEVICE_VALIDATION.md`, and `TEST_RESULTS.md`.

## [5.1.3] - 2026-11-10

VRKA Android 5.1.3 ships the storage reliability corrections that followed v5.1.2. The release
uses one Room/SQLite authority for job, publication, migration, and cleanup state. It preserves
v5.1.2 release evidence as historical data. See
[docs/STORAGE_ARCHITECTURE.md](docs/STORAGE_ARCHITECTURE.md) and
[docs/STORAGE_MIGRATION.md](docs/STORAGE_MIGRATION.md).

- **Single durable authority:** Room 2.8.5 + KSP 2.3.13 (built-in Kotlin preserved) stores
  `jobs`, `attempts`, `publication_batches`, `publication_outputs`, `cleanup_tasks`,
  `migration_ledger`, and `migration_records`. The existing `StateFlow` cache remains a cache,
  refreshed only after committed database transactions; media bytes stay in staging/providers.
- **Identities and atomic completion:** every retry has an attempt ID, every batch a batch ID,
  and every output a transaction ID. The terminal DONE projection, exact ordered outputs,
  reconciliation, and staging cleanup task commit in one database transaction, followed by a
  fresh read. Stale callbacks and unverified outputs cannot commit.
- **Crash-safe legacy import:** explicit v5.1.0, v5.1.1, v5.1.2, and v5.1.3 baseline
  profiles; source hashes and ledger; deterministic IDs; bounded chunks; transactional resume;
  raw preservation of ambiguous records; WAL/foreign-key/integrity verification; originals and
  snapshots retained. A 80-case/75-file fixture corpus generated from the actual tagged
  encoders is committed and verified by the test suite.
- **Startup gate:** `StorageRuntime` publishes INITIALIZING/MIGRATING/READY (or
  MIGRATION_FAILED/STORAGE_FAILED); enqueue, services, transfer worker, and UI entry points
  consult it. Migration failure blocks new work instead of starting empty.
- **Provider protocols:** MediaStore publication persists the row before copying, requires
  readback size/SHA-256 agreement, and requires a re-queried finalized row; SAF publication uses
  transaction-marked names, provider capability preflight, readback verification, unique
  final-name verification, replacement-URI rediscovery, and non-destructive recovery when the
  provider rewrites, duplicates, or hides the marker.
- **Cleanup, paging and admission:** durable staging and provider cleanup obligations with
  retry/backoff/dead-letter; History is paged (30 rows) and retained until user deletion; enqueue
  rejects invalid URLs, a full queue, insufficient space, storage that is not ready, or a failed
  durable write.
- **JobStore durability and journal integrity:** retention never evicts nonterminal jobs or jobs
  pinned by unresolved publication transactions; strict journal schema/field/batch/URI
  validation; transaction-scoped reconciliation; no arbitrary 128-entry cleanup eviction.
- **Recovery ownership:** startup completes a job only from one exact current attempt/batch with
  a complete ordered index set and independent evidence; the same atomic Room terminal
  transaction is used for recovery. Transactions from other attempts or batches are preserved
  unresolved and are never adopted, finalized, reconciled, or deleted.
- **Legacy ambiguity:** v5.1.2 journal rows without attempt/batch identity are preserved as raw
  evidence and stored in explicitly ambiguous synthetic batches that cannot complete a job or
  authorize provider actions. Unknown legacy enums are quarantined instead of defaulted.
- **Cleanup after History deletion:** staging cleanup tasks outlive deleted History rows; provider
  cleanup blocks deletion until ownership is resolved; dead-letter tasks are visible in
  Diagnostics and support a task-scoped manual retry.
- **Corrupt persisted rows:** undecodable Room jobs remain durable in quarantine records, appear
  as safe damaged History entries, are excluded from overwrite by cache placeholders, and make
  storage report explicit partial readiness. Unknown Room enums fail closed.
- **OpenPGP policy:** only SHA-256/SHA-384/SHA-512; RSA-only; hashed issuer fingerprint must
  exactly match the pinned key. Published v5.1.1 and v5.1.2 signatures still verify.
- **Signing isolation:** development tasks do not select or read production signing properties.
  The production properties load only when a production signing task is explicitly named.

Verification: 828 JVM tests across 78 suites pass with 0 failures, errors, or skips; `lintDebug`
reports 0 errors; debug, development, release-variant, and Android test-source builds pass; 12
API 36 provider instrumentation tests pass (10 SAF protocol/recovery + 2 MediaStore). The
production APK, v2 signature, manifest, and in-place v5.1.2 upgrade are verified in
[RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## [5.1.2] - 2026-11-09

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 STILL REQUIRE A ONE-TIME MANUAL INSTALL.** The v5.0.2 in-app updater cannot repair the code already installed in that old client. Download and install `VRKA-Android-v5.1.2.apk` from the official GitHub release page (https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) over v5.0.2 without uninstalling. Application data and settings are preserved.

### Publication crash recovery & storage hardening
- **Durable Publication Journal & Crash Reconciliation**: Introduced `PublicationJournal` with persistent transaction records to eliminate the crash window between provider file publication and terminal `JobStore` persistence. If the process is terminated mid-publication, restart reconciliation detects and adopts completed outputs into History or cleans up incomplete provider objects.
- **Discoverable Pending Output Identity**: MediaStore and SAF objects use a deterministic recoverable pending filename (`.vrka_pending_${transactionId}_${filename}`) established before creation. Startup recovery discovers orphaned provider rows created before URI persistence, ensuring zero leaked ghost files without affecting unrelated user documents.
- **Atomic Playlist Batch Intent Recording**: All output intents across multi-file playlist downloads are prepared and persisted atomically upfront before provider creation begins. Interrupted playlist transfers fail closed (`FAILED`, `INTERRUPTED_DOWNLOAD_ERROR`, detail: `"Interrupted during publication (X of Y files published)"`), preserving completed file URIs in History while safely removing partial outputs. Incomplete playlists are never marked `DONE`.
- **Strict Six-Step Persistence Ordering**: Reconciliation strictly enforces: (1) verify provider output existence, (2) update in-memory job state, (3) persist terminal state to `JobStore`, (4) verify disk persistence succeeded, (5) mark journal transactions `RECONCILED`, and (6) remove staging files. If `JobStore` persistence fails, journal entries and staging are preserved for subsequent retry.
- **Corrupted Publication Journal Protection**: Journal states are explicitly classified (`MISSING`, `OK`, `EMPTY`, `CORRUPT`, `READ_FAILED`). Corrupted journals trigger automatic preservation in bounded recovery snapshots (`publication_journal.corrupt-$stamp.json`, max 3) and emit diagnostics, preventing unverified state mutations or accidental file deletions.
- **SAF Rename Fallback Guarantees**: SAF document finalization gracefully handles document providers lacking `DocumentsContract.renameDocument` support, cleanly preserving the document at its created URI to guarantee zero data loss.
- **Legacy In-Flight Job Fallback**: Persisted in-flight jobs from earlier versions without journal records fail closed safely as `FAILED` (`"Interrupted (legacy transfer without publication journal)"`) when output URIs are empty.

### Validation
- 747 JVM unit tests across 73 suites pass with 0 failures, 0 errors, and 0 skipped, including 14 deterministic publication-failure boundaries.
- Android Test and Release Kotlin sources compile cleanly without errors.
- Release lint reports 0 errors and 0 fatal findings across the codebase.
- Production release APK (`VRKA-Android-v5.1.2.apk`) built and verified: APK Signature Scheme v2 (RSA 4096-bit, SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).

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
