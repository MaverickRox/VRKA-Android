# VRKA Android 5.1.5 — Remediation Security Assessment

Review date: 2026-11-11. Scope: local remediation changes relative to starting commit `61b0fe4f037448756e0f87cf09cd60095e2d60da` (`main` / `origin/main`, 5 commits after annotated tag `v5.1.4` `6717da543df98fca33cdce78f8bc098edf6cef60`), unit/integration verification, and `devRelease` R8 APK analysis. Historical reviews for `v5.1.4`, `v5.1.2`, `v5.1.0`, and `v5.0` below are preserved for reference and do not describe v5.1.5 without re-verification.

> [!NOTE]
> **Methodology and Scope Boundary**:
> This assessment covers source code inspection, automated JVM/unit tests, `devRelease` R8 APK inspection via `apkanalyzer`, and local verification performed in the v5.1.5 remediation workspace. This is not an external penetration test or formal certification, production signing credentials were not accessed, and live emulator instrumentation (`connectedDebugAndroidTest`) was blocked in this Windows host environment (`WHPX hr=80070005` / x86_64 TCG AVX requirement; no API 26 system image installed). External mailbox ownership for `<security@vrka.org>` (self-asserted in the OpenPGP User ID) remains unverified and **Requires external maintainer action**.

## Security-relevant changes since v5.1.4

1. **uBlock Origin XPI cryptographic & structural verification (`XpiSignatureVerifier`, `SecureComponentUpdater`, `ComponentUpdateManager`, `GeckoRuntimeManager`)**:
   - Extension updates and runtime activation now enforce a pinned version allowlist (`BUNDLED_UBLOCK_VERSION = "1.74.0"` / `BUNDLED_UBLOCK_SHA256`), non-empty expected SHA-256 verification before and after download, ZIP path-traversal and central-directory/sequential consistency checks, Mozilla AMO PKCS#7/CMS `SignedData` signature verification (`META-INF/mozilla.rsa` over `mozilla.sf`, `manifest.mf`, and all archive entry SHA-256 digests), leaf EKU `id-kp-codeSigning` + Subject `CN` binding, and pinned Mozilla AMO intermediate CA SPKI SHA-256 validation (`3d1feedd4396c58f0a063bb6900877dd5a68835fb8fa291bd8accb52a4f80d04`).
   - `GeckoRuntimeManager` verifies XPI signature/manifest integrity on disk during startup initialization, installation, rollback, and tampered asset re-seeding.
2. **VRKA Media Detector least privilege & credential scoping (`media-detector/manifest.json`, `background.js`, `HeaderValidation`, `RequestContext`, `MediaHttpTransport`, `GeckoWebExecutorTransport`)**:
   - Removed unused `"webRequestBlocking"` permission from `media-detector/manifest.json`.
   - Restricted credential capture in `background.js` to bounded FIFO maps (`MAX_REQUEST_HEADERS = 64`, `MAX_URL_HEADERS = 64`), pre-filtering static/ad traffic, binding captured headers and DOM active-player state to exact `(tabId, documentOrigin)` keys, replacing non-allowlisted `Authorization`/`X-*` values with `"[REDACTED]"`, and clearing state on redirect/completion/error/tab navigation or close.
   - Enforced strict `(scheme, host, port)` exact-origin equality in `RequestContext` and manual redirect credential stripping (`scopeHeadersForHop`) across both `OkHttpMediaTransport` and `GeckoWebExecutorTransport`, preventing cross-port, cross-scheme, HTTPS→HTTP downgrade, or cross-origin redirect leakage of `Cookie`, `Authorization`, `Proxy-Authorization`, and auth-related headers.
3. **ProGuard / R8 rule narrowing (`app/proguard-rules.pro`)**:
   - Replaced blanket `-keep class org.bouncycastle.**`, `-keep class androidx.work.**`, and `-keep class com.yausername.**` rules with targeted keeps for Jackson-deserialized `youtubedl-android` mapper models, `ZipExtraField` 0-arg constructors, `ListenableWorker`/`Worker`/`RoomDatabase`, and only the JCA SPI digest/asymmetric/DRBG packages required for OpenPGP and X.509/PKCS#7 verification. Verified via `apkanalyzer` that `devRelease` shrank from 2 DEX files (`157,382,483` B APK; `org.bouncycastle` alone `38,952` defined methods / `4,841,386` B including `org.bouncycastle.pqc` `8,705` methods) to a single `classes.dex` (`8,401,420` B raw / `4,144,212` B compressed; `43,707` defined methods / `50,110` DEX references; `6,780,795` B package tree; `org.bouncycastle` `1,350` methods / `175,739` B with PQC removed; `VRKA-v5.1.5-dev.apk` `155,258,401` B), stripping unused `YoutubeDLUpdater` (`R8$$REMOVED$$CLASS$$891`), `multiprocess`, and unrelated BouncyCastle modules while preserving `BouncyCastleProvider`, RSA/SHA-256 SPIs, `VideoInfo` models, Workers, and `VrkaDatabase_Impl`.
4. **Redirect scoping, coroutine cancellation, and provider hardening (`VrkaSubtitleManager`, `ComponentUpdateManager`, `HomeScreen`, `DownloadNotifications`)**:
   - `VrkaSubtitleManager.downloadBrowserSubtitle` disables automatic redirects (`instanceFollowRedirects = false`), enforces bounded hops (`MAX_BROWSER_SUBTITLE_REDIRECTS = 5`), strips cross-origin credentials via `HeaderValidation.scopeHeadersForHop`, caps subtitle payload at `8 MiB`, deletes partial files on failure/cancellation, and rethrows `CancellationException`.
   - `ComponentUpdateManager.fetchLatestGithubRelease` disables automatic redirects (`instanceFollowRedirects = false`), enforces HTTPS + approved GitHub hosts (`api.github.com`, `github.com`) across bounded hops (`MAX_GITHUB_METADATA_REDIRECTS = 5`), and disconnects sockets cleanly.
   - `HomeScreen` uses `runCancellableCatching` so `kotlinx.coroutines.CancellationException` is rethrown instead of swallowed, and `DownloadNotifications.completionViewIntent` guards `contentResolver.getType(uri)` with `runCatching` (falling back to `OutputPublisher.guessMimeType`) so provider lookup errors cannot silently drop completion notifications on API 34+ MediaStore/SAF paths.
5. **Issue #23 storage & queue remediation (`VrkaDownloadManager`)**:
   - Fixed History deletion/cleanup orchestration and multi-item queue lifecycle regressions introduced after v5.1.2, verified across `109` passing JVM storage/migration/publication recovery tests (`HistoryDeletionExhaustiveTest` 14, `QueueLifecycleExhaustiveTest` 11, `StorageInvariantsAndFaultInjectionTest` 7, `Issue23QueueAndDeletionTest` 7, `StorageDefectRemediationTest` 21, `RoomStorageRepositoryTest` 8, `RoomPublicationOrchestrationTest` 2, `LegacyMigrationFixtureTest` 4, `PublicationJournalRecoveryTest` 35).
6. **Supply-chain, licensing, and toolchain hygiene (`THIRD_PARTY_NOTICES.md`, `docs/native-binaries.json`, `docs/NATIVE_BINARY_INVENTORY.md`, `.github/workflows/ci.yml`)**:
   - Reconciled `youtubedl-android` (`GPL-3.0`), embedded FFmpeg (`GPL-3.0-or-later` per `--enable-gpl --enable-version3` binary configure flags), `Kyant0/backdrop` (`Apache-2.0`), Bouncy Castle, Media3, OkHttp, Room, Jackson, Apache Commons, Guava, DataStore Protobuf (`BSD-3-Clause`), and Play Services SDK bindings (`Android Software Development Kit License`).
   - Removed preview `--channel=3` flag from `.github/workflows/ci.yml` SDK installation (`platforms;android-37.2` and `build-tools;36.0.0` resolve from stable channel 0) while preserving 40-char SHA-pinned GitHub Actions; removed obsolete `mozconfig`.
   - All v5.1.4 protections (approved GitHub hosts, bounded redirects, staged target `versionCode` binding, OpenPGP manifest verification, APK SHA-256/signer pin, anti-rollback, `CustomCommandPolicy`, runtime verification, `android:allowBackup="false"`, and local-only/redacted diagnostics) remain intact.

---

# VRKA Android 5.1.4 — Release Security Review

Review date: 2026-11-11. Historical security reviews for `v5.1.2`, `v5.1.1`, `v5.1.0`, and `v5.0` below are preserved unchanged.

## Security-relevant changes since v5.1.3

1. **Bounded HTTPS redirect and approved-host validation (`AppUpdateManager`, `AppUpdateDownloadPolicy`)**:
   - Redirect chains during updater metadata/manifest/APK downloads are explicitly bounded to `MAX_REDIRECTS = 5`.
   - Every hop is constrained to HTTPS over approved GitHub hosts (`api.github.com`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, and `raw.githubusercontent.com`).
2. **Staged APK `versionCode` persistence and restore verification (`AppUpdateDownloadWorker`, `AppUpdateManager`)**:
   - `KEY_TARGET_VERSION_CODE` (alongside `KEY_TARGET_VERSION` and `KEY_EXPECTED_CERT_SHA256`) is durably persisted when entering `READY_TO_INSTALL`.
   - `restoreSavedUpdateState()` and install-time handoff re-verify the staged APK against the persisted target `versionCode` in addition to SHA-256, package ID (`com.mvrk.vrka`), production signing certificate SHA-256 (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`), and non-debuggable status.
3. **Unified custom yt-dlp template policy (`CustomCommandPolicy`, `DownloadRequestFactory`)**:
   - Both Settings custom template editing and normal request assembly enforce the shared `CustomCommandPolicy`, blocking shell execution flags, external downloaders, config/cookie files, and output-path overrides (`argv`-only execution; Browser Fallback strips custom template flags and custom HTTP headers).
4. **Custom yt-dlp runtime integrity and UI trust gating (`YtDlpRuntimeManager`, `SettingsScreen`)**:
   - Custom `yt-dlp` zipapps continue to undergo SHA-256 verification and CPython probing prior to activation; unverified or experimental binaries trigger explicit confirmation warnings before installation.
   - Verified runtime status pills (`runtime_badge_verified`) are strictly gated on `runtime.verifiedAt > 0L`.
   - Re-installing or updating over a corrupted local runtime file repairs the local binary when the upstream SHA-256 matches `existing.sha256` (`recordedRuntimeMatches`), while still rejecting silent upstream binary mutation on an unchanged release tag, and ensures `working = false` is restored cleanly on early or activation failures.
5. **Backup, privacy, and diagnostic boundaries (`AndroidManifest.xml`, diagnostics, GeckoView)**:
   - `android:allowBackup="false"` suppresses automatic Android cloud/ADB backup of database, preferences, and runtime state.
   - Diagnostic logs redact query tokens, signed CDN URLs, and source userinfo by default, and diagnostic export displays an explicit warning and opt-in before including full original URLs.

---

# VRKA Android 5.1.2 — Post-Release Updater Security Review

**Date:** 2026-11-09
**Scope:** In-app updater architecture, cryptographic authenticity, integrity verification, rollback/downgrade protections, and platform installation handoff for VRKA Android 5.1.2.

> [!NOTE]
> **Methodology and Scope Boundary**:
> This review is based on static code inspection of the updater subsystem (`AppUpdateManager`, `ReleaseManifestVerifier`, `AppUpdateDownloadWorker`), automated hermetic unit tests (including cryptographic signature verification and failure injection), and maintainer-confirmed real-device installation of v5.1.2 over v5.1.1 on physical Android hardware.
>
> This evaluation is **not** a penetration test, adversarial dynamic runtime assessment, or formal external security certification.

## 1. HTTPS Release Metadata Retrieval
- **Transport Security**: Release metadata is fetched exclusively over TLS/HTTPS via `validateHttpsUrl()`. The URL parser strictly rejects unencrypted `http://`, URLs with embedded credentials (userinfo), non-standard port specifications (allowing only default or 443), and URI fragments.
- **Approved Host Allowlisting**: Metadata and release asset requests are strictly restricted to approved GitHub hosts: `api.github.com`, `github.com`, and `objects.githubusercontent.com`.
- **Redirect Enforcement**: Multi-hop redirects are governed by `validateRedirectTarget()` and `canFollowRedirect()`. Redirect chains are strictly limited to `MAX_REDIRECTS = 3`, protocol downgrades to cleartext HTTP are prohibited, and destination hostnames are re-verified against the allowlist on every redirect hop.

## 2. Exact Release Manifest and Asset Selection
- **Canonical Release Naming**: The updater strictly matches canonical release tags (`v{major}.{minor}.{patch}`) and expects release artifacts adhering strictly to canonical naming conventions:
  - Manifest: `VRKA-Android-v{version}.manifest.json`
  - Detached Signature: `VRKA-Android-v{version}.manifest.json.asc`
  - Release APK: `VRKA-Android-v{version}.apk`
- **Strict Asset Association**: Manifest parsing binds the release metadata directly to the expected release asset. Releases with malformed filenames, non-canonical suffixes, or unexpected asset combinations are discarded upfront by `isValidDownloadTarget()` and `parseReleaseJson()`.

## 3. Pinned OpenPGP Verification
- **Pinned Trust Anchor**: The release manifest verifier relies on an in-tree 4096-bit RSA OpenPGP public key (`0xD519380BEBA1B9F7`, full fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) embedded directly in the application's compiled resources (`vrka_release_pubkey.asc`).
- **Cryptographic Algorithm Policy**: Cryptographic signature validation uses Bouncy Castle and enforces strict modern cryptographic algorithm policies:
  - Weak hash algorithms (MD5, SHA-1, RIPEMD160) are unconditionally rejected. Only SHA-256 (and approved SHA-2 family digests) are permitted.
  - Insecure or deprecated public key algorithms (such as DSA) are rejected. RSA 4096-bit is enforced.
- **Issuer Fingerprint Subpacket Enforcement**: Detached OpenPGP signatures (`.asc`) must include an Issuer Fingerprint signature subpacket that strictly matches the pinned release key fingerprint.
- **Temporal Validity and Clock Skew Protection**: The manifest's `issued_at` timestamp is parsed using strict ISO-8601 UTC parsing (`java.time.Instant`). The verifier rejects manifests with forward clock skew exceeding 1 hour and rejects expired manifests where `expires_at` has elapsed, preventing metadata freeze attacks.

## 4. Manifest Revision Rollback Protection
- **Monotonic Metadata Revisions**: Each signed release manifest declares a monotonically increasing `metadata_revision` (v5.1.1 used revision 4; v5.1.2 uses revision 5).
- **Anti-Rollback Enforcement**: `ReleaseManifestVerifier` compares the manifest revision against the locally persisted `currentStoredRevision`. If `manifest.metadataRevision < currentStoredRevision`, verification immediately aborts with `ManifestValidationException: Metadata revision rollback detected`, preventing replay of superseded release manifests.

## 5. Version-Code Anti-Downgrade Checks
- **Multi-Tier Downgrade Defense**:
  - **Manifest Layer**: `ReleaseManifestVerifier` verifies that `manifest.versionCode >= currentInstalledVersionCode`.
  - **Updater Logic Layer**: `AppUpdateManager.validateInstallMetadata()` enforces that `apkVersionCode > installedVersionCode` and that the semantic version `apkVersionParsed.isNewerThan(installedVersionParsed)`.
  - **Same-Version & Lower-Version Rejection**: Attempts to reinstall the identical version (e.g., 5.1.2 / 50102 over 5.1.2 / 50102) or an older version (e.g., 5.1.1 / 50101 over 5.1.2 / 50102) are rejected with explicit verification errors.
  - **Android OS Layer**: The Android Package Manager natively enforces signature matching and rejects version-code downgrades without explicit uninstallation or privileged adb flags.

## 6. APK SHA-256 Verification
- **Cryptographic Digest Assertion**: The signed release manifest declares the authoritative SHA-256 checksum of the target APK as a 64-character lowercase hexadecimal string.
- **Streamed Hash Calculation**: During download in `AppUpdateDownloadWorker`, bytes are streamed to an isolated temporary staging file (`.download.tmp`) while simultaneously computing the SHA-256 hash using `MessageDigest.getInstance("SHA-256")`.
- **Integrity Validation Before Promotion**: If the computed checksum does not match the manifest's digest, the download is aborted and the temporary file is deleted immediately. Promotion to the canonical filename occurs only after digest verification succeeds.

## 7. APK Production Certificate Pinning
- **Manifest Certificate Fingerprint**: The release manifest declares the SHA-256 hash of the release signing certificate (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).
- **Pre-Install Signature Extraction**: Prior to prompting the user or initiating handoff, `AppUpdateManager.verifyApkSigner()` extracts the signing certificates from the staged APK using Android's `PackageManager.getPackageArchiveInfo()`.
- **Certificate Pin Matching**: The SHA-256 digest of the staged APK's signing certificate is computed and verified to match both the release manifest and the hardcoded production trust anchor `EXPECTED_CERT_SHA256`. Signer sets between the running application and the update package are verified to be identical (`haveSameSignerSet()`).

## 8. Package Identity and Version Metadata Checks
- **Archive Inspection**: `AppUpdateManager.validateInstallMetadata()` parses the staged package archive to verify:
  - **Package Name Parity**: `apkPackageName` matches the installed application ID (`com.mvrk.vrka`). Third-party or spoofed packages are rejected.
  - **Version Name Synchronization**: The internal `apkVersionName` must strictly match the filename convention `VRKA-Android-v${apkVersionName}.apk`.
  - **Non-Debuggable Enforcement**: Production builds enforce that the update package is not signed with debug keys or marked with `FLAG_DEBUGGABLE`.

## 9. Private Staging and Package-Installer Handoff
- **Sandboxed Storage**: Downloaded APKs and temporary chunks are stored strictly in the application's internal private storage (`context.filesDir/updates/`), preventing tampering or replacement by unauthorized third-party apps on the device.
- **Atomic Promotion**: The update file is promoted from `.download.tmp` to the canonical APK filename only after full byte-level SHA-256 and cryptographic manifest validation pass.
- **Secure FileProvider Handoff**: The file is handed off to the platform package installer via Android `FileProvider` (`com.mvrk.vrka.fileprovider`) using a scoped content URI with read-only grant (`FLAG_GRANT_READ_URI_PERMISSION`). No world-readable files or raw file URIs are exposed.
- **Install Permission Gating**: The updater checks `packageManager.canRequestPackageInstalls()`. If ungranted, it directs the user to the system permission screen rather than silently crashing or attempting unauthorized execution.

## 10. Android API Compatibility Boundaries
- **Signing API Branching**: The verifier cleanly handles Android's signature verification evolution:
  - On API 28+ (Android 9 Pie and newer), it queries `SigningInfo` via `GET_SIGNING_CERTIFICATES` (handling certificate history and lineage).
  - On API 26–27 (Android 8.0/8.1 Oreo), it falls back to legacy `GET_SIGNATURES`.
- **Foreground Service Compatibility**: Download operations running in `AppUpdateDownloadWorker` comply with Android 10+ (API 29+) foreground service type constraints (`dataSync`) and respect Android 13+ (API 33+) notification permissions.
- **Package Installation Intent**: Compatible with modern Android package installer intents (`ACTION_INSTALL_PACKAGE` / `ACTION_VIEW`) across all supported API levels (API 26 through API 36).

## 11. Evidentiary Basis and Boundaries
- **Code Inspection**: Every verification check, regex pattern, error boundary, and cryptographic policy was verified via source review of `AppUpdateManager.kt`, `ReleaseManifestVerifier.kt`, `AppUpdateDownloadWorker.kt`, and `AndroidManifest.xml`.
- **Automated Tests**: JVM test suite (747 tests at release time, 784 tests across 73 suites on `main`) deterministically verifies valid upgrades, same-version rejections, downgrade attempts, rollback attempts, invalid metadata, tampering, signature validation, JobStore retention/durability, strict journal validation, and publication recovery without external dependencies. Two real MediaStore instrumentation tests also pass on an API 36 emulator; SAF tree integration is not executed.
- **Physical Device Observations**: The maintainer confirmed that an existing v5.1.1 installation on a physical Android device updated cleanly to v5.1.2 using the production-signed release package. Broader dynamic instrumentation, automated UI test runs, or formal third-party penetration testing were not conducted.

---

# VRKA Android 5.1.2 — Release Security Review

**Date:** 2026-11-09
**Scope:** v5.1.2 production release hardening: publication crash recovery and consistency (`PublicationJournal`), deterministic pending provider identity, atomic playlist batch intent recording, strict reconciliation persistence ordering, corrupted publication journal snapshot protection, and documentation reconciliation.

## v5.1.2 security-relevant changes

- **Publication crash consistency:** Introduced `PublicationJournal` to guarantee durable transaction logging across MediaStore and SAF publications. Pending provider files use deterministic recoverable identities (`.vrka_pending_${transactionId}_${filename}`) established before creation, eliminating orphan file leakage or crash windows where published outputs exist without History entries.
- **Atomic playlist batch recording:** Playlist jobs record all output intents upfront atomically before provider object creation begins. Partial failures fail closed (`FAILED`), avoiding incomplete playlist publication states.
- **Corrupted journal defense (PUB-04):** In-memory and on-disk journal corruption is detected, preserved in bounded snapshots (`publication_journal.corrupt-$stamp.json`, max 3), and never silently treated as empty or permitted to cause unverified file deletions. `PublicationJournal.decode()` strictly validates all required fields and enum values without permissive fallbacks.
- **Strict reconciliation ordering (PUB-01, PUB-02, PUB-03):** Startup reconciliation strictly requires successful `JobStore` terminal state persistence before deleting staging files or marking journal entries `RECONCILED` (`commitTerminalPublishedJob`). `OutputPublisher.finalizeMediaStore()` verifies `IS_PENDING = 0` and row finalization. Failed partial-output deletions transition safely to `CLEANUP_PENDING` without touching completed outputs or unrelated files.
- **Cryptographic policy hardening (DOC-03):** `ReleaseManifestVerifier.kt` accepts only SHA-256, SHA-384, and SHA-512; SHA-224 and all other unspecified digests are rejected. Signature keys must be RSA. The issuer fingerprint subpacket must be present in the **hashed** subpacket section and must exactly match the pinned key (`0xD519380BEBA1B9F7`). Missing, unhashed-only, malformed, and mismatched fingerprints are rejected. Published v5.1.1 and v5.1.2 signatures continue to verify with the updated production verifier and pinned public key.
- **Updater v5.1.1-to-v5.1.2 compatibility & validation:** Audited the full in-app updater pipeline: release manifest retrieval, OpenPGP detached signature verification, manifest revision monotonicity (rev 5 > rev 4), anti-rollback protection, package identity match, version code progression (50102 > 50101), SHA-256 payload integrity, production signing certificate pinning (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`), download staging in private storage (`filesDir/updates/`), non-debuggable APK enforcement, and safe failure/interruption handling that guarantees running applications and user data are never corrupted. Added automated regression test coverage for v5.1.1-to-v5.1.2 manifest and updater handoff. Physical installation viability was confirmed by the maintainer on a physical Android phone.
- **No trust weakening:** The production verifier, pinned certificate constant (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`), pinned OpenPGP trust anchor (`0xD519380BEBA1B9F7`), package ID (`com.mvrk.vrka`), and signing credentials remain unchanged.

---

# Unreleased Post-v5.1.2 Remediation on `main` — Security Review

**Date:** 2026-11-09
**Verified source:** `0866d00df5952376a4da137a7667bd24f66c30e8` (the earlier `0866d001…` reference was a reporting error and is not a Git object)

This unreleased remediation strengthens publication durability and provider safety beyond the published v5.1.2 binary. It does not modify any release tag, manifest, signature, checksum, public key, or APK.

- **Terminal durability (PUB-01):** `JobStore` retention protects all nonterminal jobs and every job pinned by an unresolved publication transaction. Persistence is serialized at the manager level; the snapshot is selected inside the lock, written atomically, and read back before success is reported. Terminal completion pins the completing job, verifies the durable record, retires only the exact transaction IDs created by that attempt, and cleans staging last. A failed save or verification keeps the journal and staging intact.
- **Provider verification (PUB-02):** API 29+ requires the actual provider row, expected display name, and `IS_PENDING = 0`. A missing `IS_PENDING` column is treated as unverifiable, not finalized. Recovery re-verifies even finalized journal entries before `DONE`. API 26–28 uses name-only legacy semantics.
- **Destructive-operation safety (PUB-03/PUB-04):** Provider discovery distinguishes found/absent/unknown; revoked permissions and query failures never authorize deletion. Cleanup failures persist `CLEANUP_PENDING` and retry safely. Journal decoding enforces the exact schema, unique transaction IDs, timestamps, batch/index consistency, the transaction-specific pending name, provider URI structure, and phase/URI/status consistency. Cleanup is authorized only for the single provider object carrying the transaction's unique pending name. Corrupt records are preserved and recovery fails closed unless valid jobs can be isolated.
- **Staging durability (PUB-05):** staging deletion results are checked; failures become bounded durable retry records, and retries never delete a directory still referenced by an unresolved transaction or live job.
- **OpenPGP policy (DOC-03):** the verifier now rejects SHA-224 and accepts only SHA-256/SHA-384/SHA-512, requires RSA keys, and requires the issuer fingerprint in the hashed subpacket section with an exact match to the pinned key. Historical v5.1.1 and v5.1.2 signatures were re-verified with the updated verifier and pinned public key.
- **Verification:** 784 JVM tests pass in 73 suites; lint reports 0 errors; release-variant and Android test-source compilation pass; two real MediaStore instrumentation tests pass on an API 36 emulator. SAF tree integration and multi-OEM provider behavior remain unverified.

---

# Historical: VRKA Android 5.1.1 — Release Security Review

**Date:** 2026-11-08
**Scope:** Local v5.1.1 reliability changes to size-estimation metadata handling, queue/history persistence, Android 8/9 destination policy, Browser Fallback diagnostics, updater timestamp parsing, and updater test isolation. No production artifact was built or signed.

## v5.1.1 security-relevant changes

- **Updater timestamps:** `ReleaseManifestVerifier.parseIso8601` now uses strict `java.time` ISO-8601 parsing. Impossible calendar dates, invalid clock values, incomplete timestamps, and trailing text are rejected. Signature verification, the pinned trust anchor, the one-hour issued-at clock skew, expiry, metadata revision, and version-code anti-rollback checks are unchanged.
- **Hermetic updater tests:** Unit tests no longer search for `.vrka/vrka_release_signing_key.sec` or any production signing material. Every cryptographic test generates an in-memory temporary OpenPGP key and executes on each run. A source-scan regression test fails if a test references the production key or signing-properties environment variable. The published v5.1.0 manifest signature is verified with the public trust anchor only.
- **Diagnostic privacy:** Browser Fallback diagnostic entries are sanitized before persistence and again at export. Sensitive query keys are detected case-insensitively and after percent-decoding; duplicates are retained but redacted; credential-like path segments and opaque tokens are replaced. No raw signed URL is stored.
- **Persistence integrity:** JobStore never overwrites a malformed file during load. It preserves a bounded recovery copy, salvages valid records, reports skipped/unsupported/schema states, and returns explicit save results. A failed publish-marker write aborts publication so a terminal duplicate cannot be created silently.
- **Destination policy:** API 26–28 cannot reach the legacy `MediaStore.Files` path without a granted SAF tree, because VRKA does not request `WRITE_EXTERNAL_STORAGE`. Revoked permissions return the job to the folder prompt. API 29+ scoped storage behavior is unchanged.
- **No trust weakening:** The production verifier, pinned certificate constant, package ID, and signing identity were not modified. The local candidate has no production signature.

---

# Historical: VRKA Android 5.1.0 — Release Security Review

**Date:** 2026-10-31
**Scope:** Source changes for v5.1.0 localization, preflight download size estimation, custom runtime management, secure in-app updater redesign with OpenPGP release manifest verification, and the resulting production-signed APK.

## In-App Updater Redesign and Cryptographic Verification

- **Authenticated Release Manifests:** Switched application update metadata verification from unauthenticated GitHub API fields to a versioned machine-readable manifest (`VRKA-Android-v5.1.0.manifest.json`) cryptographically authenticated by a detached OpenPGP signature (`VRKA-Android-v5.1.0.manifest.json.asc`).
- **Pinned OpenPGP Trust Anchor:** Pinned the 4096-bit RSA OpenPGP release signing key (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) in the application binary (`app/src/main/res/raw/vrka_release_pubkey.asc`).
- **Cryptographic Policy Enforcement:** The updater strictly rejects weak hash algorithms (MD5, SHA-1, RIPEMD160) and insecure key algorithms (DSA). Detached signatures must enforce issuer fingerprint subpackets matching the pinned key.
- **Freeze Attack Prevention:** Release manifests declare an expiration timestamp (`expires_at`), preventing freeze attacks where an attacker serves validly signed but outdated metadata.
- **Rollback and Downgrade Defense:** Monotonically increasing `metadata_revision` numbers prevent replay attacks, while `versionCode` checks ensure updates never downgrade an existing installation.
- **Staging Verification Defect Resolution:** Fixed the defect in `AppUpdateManager` and `AppUpdateDownloadWorker` where `.download.tmp` was checked against the final canonical filename. Archive structure, package identity, and certificate checks are now performed on the temporary download, while canonical filename checks run on the final staged APK.

## Custom runtimes and component updates

- Custom yt-dlp runtimes configured in Settings are validated for integrity and execution safety without shell interpolation.
- Upstream official yt-dlp component updates continue to enforce OpenPGP signature verification before installation.
- uBlock Origin updates enforce Firefox WebExtension XPI structure and checksum checks.

## Localization and resource security

- All 14 application locale string resources were audited for XML structure, encoding integrity, and key parity.
- Format strings preserve positional specifiers without formatting string injection risks.
- Right-to-left layout handling for Arabic ensures UI elements remain bounded and readable.

## Release signature and integrity verification

- The final production APK (`VRKA-Android-v5.1.0.apk`) was verified with `apksigner`:
  - One RSA 4096-bit signer
  - APK Signature Scheme v2 verified
  - Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
  - Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
  - Exact match with established production signing identity.
- Artifact properties:
  - File: `VRKA-Android-v5.1.0.apk`
  - Size: `157,202,797` bytes
  - SHA-256: `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
  - Manifest SHA-256: `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`
  - Manifest Signature SHA-256: `17B3C504EB4352FCB34815975E973AB8AA6AAEBC6FD1852E2719646858B4FB35`
- No keystores, private keys, or passwords exist in the repository or public documentation.

## Verification boundary

- Unit tests: 639 passing tests across 61 suites, 0 failures.
- Release lint: 275 warnings, 0 hints, 0 errors.
- Production APK build: non-debuggable, minified with R8, shrunk resources.
- Physical OnePlus 11 hardware was unavailable; emulator validation and automated suites passed.

---

# VRKA Android 5.0 — Release Security Review

**Date:** 2026-10-04
**Scope:** source changes for final local release preparation and the resulting production-signed APK. This is not a dynamic penetration test.

## Custom yt-dlp templates

- Templates are parsed as argument lists and passed to yt-dlp without shell execution.
- VRKA retains control of the public URL, staging path, and output publication.
- Existing policy rejects shell hooks and unsafe output/config paths, cookie files, external downloaders, credential-bearing header arguments, proxies, and extra positional URLs.
- Eligible failed custom attempts may enter Browser Fallback. The fallback request clears template IDs/arguments and user-entered headers and uses the standard yt-dlp path.

## URLs, headers, and persisted state

- External job URLs are constrained to HTTP(S).
- Header validation rejects malformed/CRLF values and restricts sensitive browser-session headers to approved use.
- Browser candidate URLs and captured headers are held in memory and are excluded from persisted job data.
- Diagnostics sanitize URLs, headers, and free text; Browser Fallback failures do not expose private candidate URLs.

## Runtime components and release signature

- GeckoView hosts navigation and the active VRKA media-detector and uBlock WebExtensions.
- yt-dlp owns normal and Browser Fallback transfers; FFmpeg handles local processing. Media3/remote-FFmpeg code has no active manager call site, and N_m3u8DLRE is gated off.
- The retired extension is absent from the APK and active updater/registration paths. Upgrade-only cleanup cancels its queued update, clears old preferences, uninstalls the existing Gecko extension ID, and deletes its old copied XPI.
- The final APK was verified with `apksigner`: one RSA 4096-bit signer, v2 scheme verified, certificate SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`. This matches the known production certificate. Signing secrets and passwords are not included in the repository or this report.

## Verification boundary

The clean build passed 348 unit tests and release lint (67 warnings, one hint, no errors or fatal findings). APK metadata and packaged assets were inspected. No emulator, ADB, physical device, or dynamic network interception was used. Browser Fallback remains dependent on yt-dlp and site behavior and does not bypass DRM, authentication, payment, CAPTCHA, or other access controls. See [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) for exact artifact metadata and [PHYSICAL_VALIDATION_REPORT.md](docs/archive/PHYSICAL_VALIDATION_REPORT.md) for device-test status.
