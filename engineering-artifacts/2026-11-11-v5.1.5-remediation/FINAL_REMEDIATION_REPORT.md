# VRKA Android v5.1.5 — Comprehensive Engineering Remediation & Security Verification Report

**Date:** 2026-11-11  
**Workspace:** `F:\Projects\VRKA Android V5.1.5`  
**Target Version:** `5.1.5` (`versionCode = 50105`) — Local Uncommitted Remediation Working Tree  
**Base Commit (`HEAD` / `main`):** `61b0fe4f037448756e0f87cf09cd60095e2d60da`  
**Latest Official Signed GitHub Release:** `v5.1.4` (tag `0588605f99cabde9f371239f4dd5b6993632044e` -> commit `6717da543df98fca33cdce78f8bc098edf6cef60`)  
**Remote / Git Policy Compliance:** Strictly local uncommitted working tree; zero commits, zero pushes, zero tags, zero releases, zero GitHub API/issue mutations, and zero destructive Git operations performed.

---

## A. Executive Summary

This remediation resolves the reported post-v5.1.3 storage and download queue regressions ([Issue #23](https://github.com/MaverickRox/VRKA-Android/issues/23)), hardens extension/media/subtitle/updater security boundaries, narrows ProGuard/R8 keep rules to single-DEX bytecode while removing dead post-quantum/updater bloat, reconciles third-party licensing (`ffmpeg` -> `GPL-3.0-or-later`), eliminates CI preview-channel SDK reliance, aligns project version and documentation to `5.1.5` (`50105`), and verifies all changes strictly under Gradle dependency locking and strict dependency verification (`--dependency-verification=strict`).

Verification summary after final code and repository documentation edits:

| Verification Gate | Result | Observed Evidence |
| --- | --- | --- |
| Full JVM Unit & Robolectric Suite (`testDebugUnitTest --rerun-tasks`) | **PASS** (`907 / 907`) | `87` test suites, `907` tests, `0` failures, `0` errors, `0` skipped (post-final-doc-edit run `task-2040`, `--dependency-verification=strict`) |
| Android Lint (`lintDebug`, `lintRelease`) | **PASS** (`0` errors) | `lintDebug`: `0` errors/fatal, `301` warnings, `4` info/hints (`305` total); `lintRelease`: `0` errors/fatal, `300` warnings, `4` info/hints (`304` total) (`task-1989`, `--rerun-tasks`) |
| Kotlin & Test Source Compilation | **PASS** | `compileDebugKotlin`, `compileReleaseKotlin`, `compileDebugAndroidTestKotlin`, `compileDebugUnitTestKotlin` (`task-1989`) |
| Development R8 APK (`assembleDevRelease`) | **PASS** | `VRKA-v5.1.5-dev.apk`: `155,258,401` bytes, single `classes.dex` (`8,401,420` B raw / `4,144,212` B compressed), `43,707` defined methods / `50,110` DEX references, `zipalign` OK, `apksigner` v2 Android Debug verified |
| Debug & Instrumentation Test APKs (`assembleDebug`, `assembleDebugAndroidTest`) | **PASS** | `app-debug.apk` (`181,188,244` bytes), `app-debug-androidTest.apk` (`1,270,435` bytes) built cleanly |
| Repository Hygiene (`git diff --exit-code -- app/gradle.lockfile`, `git diff --check`) | **PASS** | Exit `0` on both lockfile immutability and whitespace/EOF hygiene |
| Live Emulator / Hardware Instrumentation (`connectedDebugAndroidTest`) | **BLOCKED / NOT EXECUTED** | `Medium_Phone_API_36.0` failed to boot under Windows host virtualization (`WHPX hr=80070005` access denied headless; `-accel off` lacks `CPUID.01H:ECX.avx`; windowed launch stalled in Qt/`opengl32sw`), and no API 26 image exists locally. Substituted JVM Robolectric SDK 26 & SDK 36 tests (`ApiCompatibilityAndNotificationsTest`). |
| Production Release Signing & `<security@vrka.org>` External Proof | **NOT EXECUTED / EXTERNAL** | Local-only remediation does not touch production keystore/OpenPGP private keys; external mailbox ownership of `security@vrka.org` **Requires external maintainer action**. |

---

## B. Workspace Starting Point & Release History Comparison

Git tags and commit history in `F:\Projects\VRKA Android V5.1.5` were verified via `git show-ref --tags`, `git rev-parse`, and `git log`:

| Reference | Annotated Tag SHA | Target Commit SHA | Change Volume vs Prior Tag | Summary of Architectural Changes |
| --- | --- | --- | --- | --- |
| `v5.1.2` | `9c1747d1089401f6cc5882784dca1a070999fdf8` | `af5c1b90d0ad9e18582481460a008aa3daf2c354` | Baseline | Legacy file-based `JobStore`, pre-Room publication journal |
| `v5.1.3` | `7527e800e59bf6774b33011f3a23ca2049a41df1` | `daac0b5b7f14dc5ce6b2af0be8fcaf5119ebd2b8` | `151` files (`+16,263 / -1,225`) | Replaced `JobStore` with authoritative Room DB (`VrkaDatabase`), atomic publication commits, cleanup tasks, and recovery (`61950d7`, `82cc63e`, `9812872`, `d5eaaf3`). **Introduced Issue #23 regressions.** |
| `v5.1.4` | `0588605f99cabde9f371239f4dd5b6993632044e` | `6717da543df98fca33cdce78f8bc098edf6cef60` | `80` files (`+7,811 / -470`) | Added `gradle/verification-metadata.xml` & dependency lockfiles (`f202dbc`), custom runtime warnings (`f1a43c2`), bounded updater redirects & `CustomCommandPolicy` (`87c17f2`, `bd4f24e`). |
| `HEAD` (`main`) | `—` | `61b0fe4f037448756e0f87cf09cd60095e2d60da` | `18` files (`+273 / -593`, 5 commits post-`v5.1.4`) | Signed `v5.1.4` manifest/checksums (`38e2793`), README/emulator/changelog docs (`bfd47d9`, `7f32309`, `5655169`, `61b0fe4`). Exact local remediation starting point. |

Note: An earlier draft string (`069e80172b5be3bc3750ee3c9fe7012c4d84d14a`) found in the workspace's pre-existing `SECURITY_REVIEW.md` draft header was verified via `git cat-file -t` not to be a valid Git object in this repository and was corrected to `61b0fe4f037448756e0f87cf09cd60095e2d60da`.

---

## C. Issue #23 Investigation & Root Cause Analysis

### Issue A — History File Deletion Failure

Reported symptom: selecting one or more items in History and confirming deletion left the items visible in History and failed to reliably clean up files or track failures.

Observed root causes across `RoomStorageRepository.kt`, `VrkaDownloadManager.kt`, `OutputPublisher.kt`, `JobsScreen.kt`, and `VrkaRoot.kt`:

1. **Blocking on non-blocking publication output rows (`RoomStorageRepository.kt`)**: `deleteJobsInTransaction` queried `database.publicationOutputDao().unreconciledForJob(jobId)` and aborted deletion whenever any row existed (`unresolved.isNotEmpty()`), failing the entire batch. Because `unreconciledForJob` returned terminal non-reconciled rows with statuses `"FAILED"`, `"INTERRUPTED"`, or `"DELETED"`, any job with an interrupted or failed publication artifact was permanently un-deletable. Also, `deleteJobs` returned a single all-or-nothing `Boolean` instead of reporting which job IDs were deleted vs retained.
2. **Stale Paging 3 History UI invalidation (`RoomStorageRepository.kt`, `VrkaRoot.kt`)**: `MappingPagingSource` wrapped Room's `PagingSource<Int, JobEntity>` without propagating invalidation signals (`delegate.registerInvalidatedCallback { invalidate() }` and vice versa), and `VrkaRoot` evaluated `historyPager?.flow` un-memoized without surfacing snackbar feedback when deletion failed or was retained for storage recovery.
3. **Lack of write-ahead deletion durability & tri-state outcome differentiation (`OutputPublisher.kt`, `VrkaDownloadManager.kt`, `RoomCleanupStore.kt`)**:
   - `OutputPublisher.delete` collapsed provider `delete` errors and already-absent files into a single `false`, causing missing files either to block History cleanup or real provider/permission errors to drop History records prematurely.
   - `VrkaDownloadManager` lacked persistent `HISTORY_DELETE` cleanup obligations before physical deletion, lacked startup recovery retries (`retryPendingHistoryFileDeletions()`), and allowed `refreshCacheFromRepository()` to race with concurrent updates.
   - `JobsScreen.kt` gated single-item "also delete files" UI only on `JobState.DONE`, missing `JobState.FAILED` records carrying partial outputs, and Clear All displayed `jobs.size` rather than `displayCount`.

### Issue B — Multiple Queued Downloads Fail

Reported symptom: when multiple media jobs were queued, the first download succeeded while subsequent queued downloads failed.

Observed root causes across `VrkaDownloadManager.kt`, `RoomStorageRepository.kt`, and `OutputPublisher.kt`:

1. **Snapshot clobbering on concurrent saves (`VrkaDownloadManager.kt`)**: `persistJobsNow()` previously overwrote `_jobs.value` with `persisted.sortedByDescending(DownloadJob::createdAt)` whenever any job finished or saved state, discarding newer in-flight state, queued retries, or concurrent progress ticks from sibling jobs in `_jobs.value`.
2. **Scheduler active-map race & dropped deferred IDs (`VrkaDownloadManager.kt`)**: `triggerScheduler()` launched coroutines before inserting into `activeRunningJobs`, dropped `nextJobId` with `continue` if already active instead of deferring back to `pendingQueue`, and executed `activeRunningJobs.remove(nextJobId)` unconditionally in `finally` (capable of removing a newer retry's `Job` handle). `CancellationException` could also be logged/converted instead of cleanly rethrown.
3. **Room terminal ownership rejection (`VrkaDownloadManager.kt`, `RoomStorageRepository.kt`)**:
   - `markPublishStarted(jobId)` saved via `persistJobsNow()` without pinning `setOf(jobId)`.
   - `commitTerminalPublishedJob(...)` in `VrkaDownloadManager` could invoke `repo.commitTerminalPublishedJob(...)` while Room's cached job row lacked the current `attempt` or `publishStarted = true` from recent in-memory transitions (resulting in atomic ownership rejection and spurious `FAILED` state on subsequent queue items), and `repo.commitTerminalPublishedJob(...)` itself wrote `progress = 1f` instead of `100f` upon `DONE`.
   - `retry()` mutated/saved without clearing stale queue entries or rolling back cleanly if durable persistence failed.
4. **MediaStore filename collision blindness (`OutputPublisher.kt`)**: `preparePlan()` checked `existingSafNames` for SAF trees but set `existingNamesSet = null` for MediaStore, skipping `OutputNameAllocator.next(...)` collision avoidance across queued/concurrent MediaStore publications.

---

## D. Code Changes & Architectural Rationale

1. **`app/src/main/java/com/mvrk/vrka/storage/RoomStorageRepository.kt`**:
   - Added `NON_BLOCKING_PUBLICATION_STATUSES = setOf("RECONCILED", "FAILED", "INTERRUPTED", "DELETED")`, `deleteJobsReturningIds(...)`, and `deleteJobsReturningIdsInTransaction(...)` so only genuinely live unresolved publication outputs or non-`STAGING` cleanup tasks block History deletion.
   - Added bidirectional invalidation wiring and `jumpingSupported` delegation to `MappingPagingSource`.
   - Fixed terminal `progress` in `commitTerminalPublishedJob` from `1f` to `100f`.
2. **`app/src/main/java/com/mvrk/vrka/storage/StorageContracts.kt` & `RoomCleanupStore.kt`**:
   - Extended `CleanupStore` and implemented `pendingHistoryDeletions()`, `markHistoryDeletionPending(...)`, `clearHistoryDeletion(...)`, and `clearHistoryDeletionsForJob(...)` backed by Room `CleanupTaskEntity` (`HISTORY_DELETE` resource type) with sanitized error messages.
3. **`app/src/main/java/com/mvrk/vrka/OutputPublisher.kt`**:
   - Introduced `DeleteOutcome` (`DELETED`, `ALREADY_ABSENT`, `FAILED`) and `ProviderObjectPresence` (`EXISTS`, `ABSENT`, `UNKNOWN`), implementing scheme-appropriate MediaStore/SAF deletion and presence probing (`deleteWithOutcome`, `probePresence`).
   - Added `existingMediaStoreNames(...)` and unified `preparePlan()` filename allocation via `OutputNameAllocator.next(...)` for both MediaStore and SAF.
4. **`app/src/main/java/com/mvrk/vrka/VrkaDownloadManager.kt`**:
   - History deletion: write-ahead `markHistoryDeletionPending` before physical delete; per-job handling of `DeleteOutcome` (`DELETED` / `ALREADY_ABSENT` clear obligation and allow History removal; `FAILED` persists `recordFailure = true`, preserves History record, surfaces storage issue, and retries on recovery via `retryPendingHistoryFileDeletions()`).
   - Queue reliability: `mergePersistedIntoLive(...)`, `mergeSinglePersistedJobIntoLive(...)`, and `pickFresherJob(...)`; `CoroutineStart.LAZY`, `deferred` queue restoration, and `activeRunningJobs.remove(nextJobId, self)` in `triggerScheduler()`; atomic attempt persistence in `retry()` and durable failure persistence in `markJobFailedDurably(...)`; pinning `setOf(jobId)` in `markPublishStarted()` and ensuring `persistJobsNow(setOf(jobId))` syncs `attempt`/`publishStarted` before `repo.commitTerminalPublishedJob(...)`.
   - Added internal test seams (`runtimeInitOverride`, `customDownloadExecutor`, `streamValidationOverride`) without altering production execution paths.
5. **`app/src/main/java/com/mvrk/vrka/JobsScreen.kt` & `VrkaRoot.kt`**:
   - Included `JobState.FAILED` in single-delete file prompt eligibility (`hasFiles`), fixed Clear All count to `displayCount`, memoized `historyPager?.flow`, and wired snackbar notifications when deletions retain records for recovery or fail physically.

---

## E. Security Audit & Findings Status

### Audit 6.1 — uBlock Origin XPI Cryptographic Signature Verification & Pinning
- **Status: FIXED** (`XpiSignatureVerifier.kt`, `SecureComponentUpdater.kt`, `ComponentUpdateManager.kt`, `GeckoRuntimeManager.kt`, `TestXpiSigner.kt`).
- Enforces pinned extension version/hash manifest (`BUNDLED_UBLOCK_VERSION = "1.74.0"`, `BUNDLED_UBLOCK_SHA256 = "175756d74468c9ba45863f7fc333d3be670f82d5b066314e915814dd547d1652"`), non-empty SHA-256 verification before and after download, ZIP central-directory/sequential stream consistency & path-traversal rejection, CMS/PKCS#7 `SignedData` signature validation (`META-INF/mozilla.rsa` -> `META-INF/mozilla.sf` -> `META-INF/manifest.mf` -> per-entry SHA-256 hashes), X.509 leaf EKU `id-kp-codeSigning` + Subject `CN` binding (`uBlock0@raymondhill.net`), and pinned Mozilla AMO intermediate CA SPKI SHA-256 (`3d1feedd4396c58f0a063bb6900877dd5a68835fb8fa291bd8accb52a4f80d04`).
- `GeckoRuntimeManager` re-verifies XPI archives on disk during startup activation, extension installation, and rollback/re-seeding.

### Audit 6.2 — Third-Party Licensing & Binary Inventory Accuracy
- **Status: FIXED** (`THIRD_PARTY_NOTICES.md`, `README.md`, `docs/native-binaries.json`, `docs/NATIVE_BINARY_INVENTORY.md`).
- Updated `ffmpeg` / `ffprobe` license attribution from inaccurate LGPL references to `GPL-3.0-or-later` matching the binary build flags (`--enable-gpl --enable-version3`), reconciled `youtubedl-android` (`GPL-3.0`), `Kyant0/backdrop` (`Apache-2.0`), Bouncy Castle, Media3, OkHttp, Room, Jackson, Apache Commons, Guava, DataStore Protobuf, and Play Services SDK notices, and aligned `docs/native-binaries.json` (`generatedFor: "5.1.5"`).

### Audit 6.3 — Media Detector Extension Least Privilege & Credential Scoping
- **Status: FIXED** (`media-detector/manifest.json`, `media-detector/background.js`, `HeaderValidation.kt`, `RequestContext.kt`, `MediaHttpTransport.kt`, `GeckoWebExecutorTransport.kt`, `docs/PRIVACY_NETWORK.md`).
- Removed unused `"webRequestBlocking"` permission from `media-detector/manifest.json`.
- Bounded `background.js` maps (`MAX_REQUEST_HEADERS = 64`, `MAX_URL_HEADERS = 64`, `MAX_SEEN_CANDIDATES = 250`), keyed capture by `(tabId, url/origin)` (`${tabId}::...`), filtered non-media static assets, redacted non-allowlisted `Authorization`/`X-*` values to `"[REDACTED]"`, purged state on redirects/completion/error/tab navigation/tab close, and suppressed HTTPS -> HTTP `Referer` downgrade.
- Tightened `RequestContext` and `HeaderValidation` from host-level to strict `(scheme, host, port)` exact-origin equality (`originOf`, `isSameOrigin`) and added manual redirect credential stripping (`scopeHeadersForHop`) across both `OkHttpMediaTransport` (`.followRedirects(false)`, `.followSslRedirects(false)`) and `GeckoWebExecutorTransport` (`FETCH_FLAGS_NO_REDIRECTS`, `MAX_REDIRECTS = 5`), ensuring stripped credentials are never re-attached even if redirected back to the initial origin.

### Audit 6.4 — ProGuard / R8 Optimization
- **Status: FIXED** (`app/proguard-rules.pro` — detailed DEX metrics in Section G).

### Audit 7.1–7.6 — CI Toolchain, Obsolete Configs, Provenance Docs & OpenPGP Email Ownership
- `.github/workflows/ci.yml`: **FIXED** — removed preview `--channel=3` SDK flag while preserving 40-char SHA-pinned GitHub Actions.
- `mozconfig`: **FIXED** — deleted obsolete file from repo root.
- `docs/KEY_MANAGEMENT.md` & `docs/CI_CD_SECURITY.md`: **FIXED** — updated key-management, strict verification, and CI documentation.
- **OpenPGP User ID `<security@vrka.org>` external mailbox ownership**: **Requires external maintainer action**. Existing trusted release signing identity (`vrka_release_pubkey.asc`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) was preserved unchanged per workspace key constraints; domain/MX/DNS/inbox ownership of `security@vrka.org` cannot be verified locally without external DNS/mail control by the maintainer.

---

## F. Section 8 Regression Protection & Section 9 Broader Reliability / R8 Fixes

All `v5.1.4` security invariants were re-verified intact (approved GitHub hosts, bounded redirects, staged target `versionCode` binding, OpenPGP manifest verification, APK SHA-256/signer pin, anti-rollback, `CustomCommandPolicy`, custom `yt-dlp` runtime checks, `android:allowBackup="false"`, redacted diagnostics). In addition, targeted audit fixes were implemented in v5.1.5:

1. **`VrkaSubtitleManager.downloadBrowserSubtitle` (`app/src/main/java/com/mvrk/vrka/subtitle/VrkaSubtitleManager.kt`)**:
   - Replaced implicit redirect following with `fetchBrowserSubtitleWithScopedRedirects` (`instanceFollowRedirects = false`, `MAX_BROWSER_SUBTITLE_REDIRECTS = 5`, `HeaderValidation.resolveRedirectUrl`, `HeaderValidation.scopeHeadersForHop`) and `writeBoundedSubtitleFile` (`8 MiB` byte cap, deletes partial outputs on error/cancellation, disconnects `HttpURLConnection` in `finally`, and rethrows `kotlinx.coroutines.CancellationException`).
2. **`ComponentUpdateManager.fetchLatestGithubRelease` (`app/src/main/java/com/mvrk/vrka/ComponentUpdateManager.kt`)**:
   - Enforced HTTPS-only and approved GitHub metadata hosts (`APPROVED_GITHUB_METADATA_HOSTS = setOf("api.github.com", "github.com")`) across manual redirects (`instanceFollowRedirects = false`, `MAX_GITHUB_METADATA_REDIRECTS = 5`) via `validateGithubMetadataUrl` and `openGithubMetadataConnection`, closing intermediate/failed/cancelled connections cleanly.
3. **`HomeScreen.kt` coroutine cancellation hygiene**:
   - Added `runCancellableCatching` and applied it at coroutine/UI sites so `kotlinx.coroutines.CancellationException` is never swallowed.
4. **`DownloadNotifications.completionViewIntent` (`app/src/main/java/com/mvrk/vrka/DownloadNotifications.kt`)**:
   - Wrapped `context.contentResolver.getType(uri)` in `runCatching { ... }.getOrNull()` (matching sibling `queryDisplayName` just below it) before falling back to `OutputPublisher.guessMimeType(...)`. **Why:** Robolectric SDK 36's `FakeMediaProvider.getType` throws `IllegalArgumentException` when probing an absent/unknown MediaStore URI; in production, any provider throwing `IllegalArgumentException` or `SecurityException` on `getType(uri)` inside `buildCompleted` caused `notifyCompleted`'s outer `runCatching` to silently drop the entire completion notification.
5. **Runtime R8 Verification (`Section9ReliabilityAndR8Test.kt`)**:
   - Added 6 targeted unit tests covering same-origin/cross-origin subtitle redirect header stripping, redirect loop / >8 MiB / `file://` rejection, `CancellationException` propagation, GitHub metadata host/redirect enforcement, Bouncy Castle `BouncyCastleProvider` RSA/SHA-256 JCA roundtrip, Jackson `ObjectMapper` deserialization into `VideoInfo`, and Apache Commons Compress `ZipFile` + `ExtraFieldUtils` 0-arg extra field instantiation.

---

## G. ProGuard / R8 Optimization & APK Comparison

Replaced blanket keeps (`-keep class com.yausername.**`, `-keep class org.bouncycastle.**`, `-keep class androidx.work.**`, `-keep class org.apache.commons.compress.archivers.zip.**`) with explicit targeted keeps in `app/proguard-rules.pro` (`mapper.**` + `@JsonProperty` annotations, `ZipExtraField` `public <init>()`, `BouncyCastleProvider` + required digest/asymmetric/DRBG SPIs, `RoomDatabase` `public <init>()`).

| Metric | Prior Baseline (`devRelease` before rule narrowing) | Current `v5.1.5` (`VRKA-v5.1.5-dev.apk`) | Measured Delta / Verification |
| --- | --- | --- | --- |
| APK Size | `157,382,483` bytes | `155,258,401` bytes (`sha256: ad1c643f...`) | `-2,124,082` bytes |
| DEX Count | `2` DEX files | **`1` DEX file (`classes.dex`)** | Consolidated to single DEX (`classes2.dex` eliminated) |
| `classes.dex` Raw / Package Tree Size | `N/A` (prior total DEX `10,812,599` B recorded pre-cleanup) | `8,401,420` B raw (`4,144,212` B compressed) / `6,780,795` B tree | Verified via `zip` entry + `apkanalyzer dex packages --defined-only` |
| Total Defined / Referenced Methods | Prior `79,606` defined methods (pre-cleanup record) | `43,707` defined / `50,110` referenced | Within 65K single-DEX reference limit |
| `org.bouncycastle` Package | `38,952` defined methods (`4,841,386` B DEX) | **`1,350` defined methods (`175,739` B DEX)** | **-96.5% defined methods** (`-37,602` methods, `-4,665,647` B) |
| `org.bouncycastle.pqc` Package | `8,705` defined methods (`1,484,894` B DEX) | **`0` (completely stripped)** | `-8,705` unused PQC methods removed |
| `com.yausername` Package | `182` defined methods (`17,085` B DEX) | **`61` defined methods (`3,272` B DEX)** | `YoutubeDLUpdater -> R8$$REMOVED$$CLASS$$891`; `VideoInfo`/`VideoFormat`/`VideoThumbnail`/`VideoSubtitle` kept |
| Targeted App Obfuscation & Keeps | `—` | Verified in `mapping.txt` (`82,566,768` bytes, `sha256: f44408...`) | `VrkaDownloadManager -> qp5`, `XpiSignatureVerifier -> zx5`, `HeaderValidation -> fx1`, `AsiExtraField -> rl`, `VrkaDatabase_Impl` & `BouncyCastleProvider` preserved |

`aapt2 dump badging` confirms `package: name='com.mvrk.vrka' versionCode='50105' versionName='5.1.5'`, `minSdkVersion:'26'`, `targetSdkVersion:'36'`, `native-code: 'arm64-v8a'`. `zipalign -c -v 4` and `apksigner verify` (APK Signature Scheme v2, Android Debug RSA-2048 cert) both passed.

---

## H. Testing, Multi-API Coverage & Blocked Emulator Transparency

### Unit & Robolectric Suite (`907` tests / `87` suites — ALL PASS after final edit)

Re-executed from scratch with `--no-daemon --no-configuration-cache --dependency-verification=strict --rerun-tasks` (`task-2040` after final doc edit): **`87` suites, `907` tests, `0` failures, `0` errors, `0` skipped**. Key new and remediated suites (exact XML counts from `engineering-artifacts/2026-11-11-v5.1.5-remediation/test_summary_v5.1.5.txt`):

- **Issue #23 & Storage Recovery (`109` tests across 9 suites)**: `HistoryDeletionExhaustiveTest` (`14`), `QueueLifecycleExhaustiveTest` (`11`), `StorageInvariantsAndFaultInjectionTest` (`7`), `Issue23QueueAndDeletionTest` (`7`), `StorageDefectRemediationTest` (`21`), `RoomStorageRepositoryTest` (`8`), `RoomPublicationOrchestrationTest` (`2`), `LegacyMigrationFixtureTest` (`4`), `PublicationJournalRecoveryTest` (`35`).
- **XPI Verification, Updater, Runtime & Supply Chain**: `SecureComponentUpdaterTest` (`43`), `ComponentUpdateManagerTest` (`20`), `ComponentUpdaterStabilityTest` (`9`), `AppUpdateManagerTest` (`41`), `AppUpdateDownloadPolicyTest` (`7`), `ReleaseManifestVerifierTest` (`29`), `ReleaseManifestTest` (`21`), `ReleasePipelineEndToEndTest` (`4`), `SupplyChainPolicyTest` (`10`), `YtDlpRuntimeManagerTest` (`7`), `CustomCommandTemplatesTest` (`7`), `DownloadRequestFactoryTest` (`23`), `PackagingConfigurationTest` (`3`).
- **Media Detector, Headers & Redirects**: `MediaDetectorAndCredentialScopingTest` (`6`), `RequestContextTest` (`7`), `HeaderValidationTest` (`8`), `BoundedMemoryTransportTest` (`4`).
- **Section 9 & Multi-API Coverage**: `Section9ReliabilityAndR8Test` (`6`), `ApiCompatibilityAndNotificationsTest` (`2` running under Robolectric `@Config(sdk = [26])` and `@Config(sdk = [36])`, covering minSdk `26` mandatory SAF enforcement + channel creation + notification posting + Room round-trip, and targetSdk `36` MediaStore default + `POST_NOTIFICATIONS` denial/grant gating + MediaStore fallback + Room round-trip).

### Live Android Emulator & Device Boundary (BLOCKED / NOT EXECUTED)

Local Android SDK inventory (`$LOCALAPPDATA\Android\Sdk\system-images`) contains only `android-36\google_apis_playstore\x86_64` (AVDs `Medium_Phone_API_36.0`, `BitChord_Experimental_API36`); no API 26–28 system image or physical device is connected locally. Three documented attempts to boot `Medium_Phone_API_36.0` failed due to host environment restrictions:
1. `-no-window -no-audio -no-boot-anim -no-snapshot-save` -> `qemu-system-x86_64-headless.exe: WHPX: Failed to setup partition, hr=80070005` (`E_ACCESSDENIED`), exited `1`.
2. `-accel off` -> `TCG doesn't support requested feature: CPUID.01H:ECX.avx [bit 28]`, exited `1`.
3. Windowed `emulator.exe` -> stalled in Qt/Crashpad initialization (`Critical: Failed to load opengl32sw`) with `adb devices` empty (process terminated afterward).

Consequently, `connectedDebugAndroidTest` and live emulator / physical device validation were **not executed** for `v5.1.5` (instrumentation test compilation was verified via `compileDebugAndroidTestKotlin` and `assembleDebugAndroidTest`).

---

## I. Supply Chain, Dependencies & Licensing

- All builds and test runs executed with `--dependency-verification=strict` against existing `gradle/verification-metadata.xml` and `app/gradle.lockfile`.
- `git diff --exit-code -- app/gradle.lockfile` returned exit `0` (zero new or unverified Gradle dependencies added).
- `SupplyChainPolicyTest` (`10` tests) verified lockfile cleanliness, 40-char SHA-pinned GitHub Actions without Dependabot PRs, doc/version/manifest consistency, native binary SHA-256 inventory integrity, and command-hardening invariants.
- `THIRD_PARTY_NOTICES.md`, `README.md`, `docs/native-binaries.json`, and `docs/NATIVE_BINARY_INVENTORY.md` accurately reflect embedded FFmpeg under `GPL-3.0-or-later`.

---

## J. Documentation & Version `5.1.5` Alignment

- `app/build.gradle.kts` updated to `versionCode = 50105`, `versionName = "5.1.5"` (`PackagingConfigurationTest` and `SupplyChainPolicyTest` updated and passing).
- Added `fastlane/metadata/android/en-US/changelogs/50105.txt`.
- Updated `README.md`, `SECURITY.md`, `CHANGELOG.md`, `RELEASE_NOTES.md`, `KNOWN_LIMITATIONS.md`, `CURRENT_PRODUCTION_ARCHITECTURE.md`, `SECURITY_REVIEW.md`, `TEST_RESULTS.md`, `DEVICE_VALIDATION.md`, `RELEASE_EVIDENCE.md`, `docs/user-guide/GETTING_STARTED.md`, `docs/user-guide/TROUBLESHOOTING.md`, `docs/PRIVACY_NETWORK.md`, `docs/KEY_MANAGEMENT.md`, and `docs/CI_CD_SECURITY.md`.
- Every release-facing document explicitly distinguishes local uncommitted pre-release `v5.1.5` (`VRKA-v5.1.5-dev.apk`) from the latest official signed GitHub release (`v5.1.4`).

---

## K. Remaining Risks, Unverified Boundaries & External Maintainer Actions

1. **Live Emulator & Physical Hardware Execution (`v5.1.5`)**: Must be run on a WHPX-capable host or physical ARM64/API 26–36 Android device (`connectedDebugAndroidTest`, live MediaStore/SAF document picker flows, `arm64-v8a` yt-dlp/FFmpeg extraction/remux, and real in-place upgrade over signed `v5.1.4`).
2. **Production Release Signing & Official Manifest Publication**: Building/signing an official release APK requires maintainer access to the production Android keystore and RSA-4096 OpenPGP private key (`vrka_release_pubkey.asc` fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`), intentionally unaccessed here.
3. **`<security@vrka.org>` OpenPGP User ID Ownership**: Marked **Requires external maintainer action** (DNS/MX/mailbox proof outside repository scope).
4. **Upstream XPI Key / Version Rotation**: Pinned `BUNDLED_UBLOCK_VERSION = "1.74.0"` + AMO CA SPKI SHA-256 (`3d1feedd...`) intentionally requires explicit app update when intentionally upgrading uBlock Origin or rotating Mozilla intermediate CAs.
5. **Lint Warnings**: `lintDebug` (`301` warnings) and `lintRelease` (`300` warnings) pass with `0` errors/fatal issues; existing deprecation/warning cleanups (e.g., `LocalClipboardManager`, `statusBarColor`, deprecated BouncyCastle APIs in test/signing helpers) remain non-blocking follow-up candidates.

---

## L. Complete File Inventory & Section 15 Completion Checklist

### Modified / Added / Deleted Repository Files (`45` tracked diff entries + untracked tests/artifacts)

- **Build & CI**: `app/build.gradle.kts`, `app/proguard-rules.pro`, `.github/workflows/ci.yml`, deleted `mozconfig`.
- **Production Kotlin (`app/src/main/java/com/mvrk/vrka/`)**: `VrkaDownloadManager.kt`, `OutputPublisher.kt`, `JobsScreen.kt`, `VrkaRoot.kt`, `DownloadNotifications.kt`, `HomeScreen.kt`, `XpiSignatureVerifier.kt` (new), `SecureComponentUpdater.kt`, `ComponentUpdateManager.kt`, `GeckoRuntimeManager.kt`, `HeaderValidation.kt`, `engine/RequestContext.kt`, `engine/MediaHttpTransport.kt`, `engine/GeckoWebExecutorTransport.kt`, `storage/StorageContracts.kt`, `storage/RoomStorageRepository.kt`, `storage/RoomCleanupStore.kt`, `subtitle/VrkaSubtitleManager.kt`.
- **Browser Extension (`app/src/main/assets/extensions/media-detector/`)**: `manifest.json`, `background.js`.
- **Tests (`app/src/test/java/com/mvrk/vrka/`)**: `storage/HistoryDeletionExhaustiveTest.kt` (new), `storage/QueueLifecycleExhaustiveTest.kt` (new), `storage/StorageInvariantsAndFaultInjectionTest.kt` (new), `storage/Issue23QueueAndDeletionTest.kt` (new), `TestXpiSigner.kt` (new), `engine/MediaDetectorAndCredentialScopingTest.kt` (new), `Section9ReliabilityAndR8Test.kt` (new), `ApiCompatibilityAndNotificationsTest.kt` (new), `SecureComponentUpdaterTest.kt`, `ComponentUpdateManagerTest.kt`, `PackagingConfigurationTest.kt`, `SupplyChainPolicyTest.kt`.
- **Documentation & Metadata**: `README.md`, `SECURITY.md`, `SECURITY_REVIEW.md`, `CHANGELOG.md`, `RELEASE_NOTES.md`, `KNOWN_LIMITATIONS.md`, `CURRENT_PRODUCTION_ARCHITECTURE.md`, `TEST_RESULTS.md`, `DEVICE_VALIDATION.md`, `RELEASE_EVIDENCE.md`, `THIRD_PARTY_NOTICES.md`, `fastlane/metadata/android/en-US/changelogs/50105.txt` (new), `docs/CI_CD_SECURITY.md`, `docs/KEY_MANAGEMENT.md`, `docs/NATIVE_BINARY_INVENTORY.md`, `docs/native-binaries.json`, `docs/PRIVACY_NETWORK.md`, `docs/user-guide/GETTING_STARTED.md`, `docs/user-guide/TROUBLESHOOTING.md`.
- **Verification Artifacts (`engineering-artifacts/2026-11-11-v5.1.5-remediation/`)**: `FINAL_REMEDIATION_REPORT.md`, `test_summary_v5.1.5.txt`, `apk_analysis_v5.1.5.txt`, `v5.1.5_devRelease_dex_packages.txt`, `git_status_and_diff_stat.txt`, `baseline_devRelease_build.log`, `baseline_devRelease_dex_packages.txt`, `devRelease_dependencies.txt`, `releaseRuntimeClasspath.txt`, `runtime_dependencies_licenses.txt`, `diff_512_513_manager.patch`, `diff_512_513_publisher.patch`, `diff_512_513_ui.patch`.

### Section 15 Completion Criteria Verification

1. **All changes exclusively in `F:\Projects\VRKA Android V5.1.5` & local-only**: Verified; zero commits/pushes/tags/PRs/GitHub edits.
2. **Issue #23 root causes identified & fixed cleanly across storage/UI/queue**: Verified; `109 / 109` storage/migration/publication tests passing.
3. **Security audits 6.1–6.4, 7.1–7.6 & Section 9 hardening addressed without hiding gaps**: Verified; `security@vrka.org` explicitly marked `Requires external maintainer action`.
4. **ProGuard/R8 narrowed and verified at bytecode level**: Verified; single `classes.dex` (`43,707` defined methods), `-96.5%` BouncyCastle reduction, reflection/Room/Jackson/Commons Compress/JCA keeps intact.
5. **Post-edit test & build gates executed cleanly (`--dependency-verification=strict`)**: Verified (`907 / 907` unit tests across `87` suites post-final-doc-edit; full 159-task lint/compile/APK gate passed post-final-code-edit; `git diff --check` and `app/gradle.lockfile` check exited `0`).
6. **Unverified platform boundaries reported honestly**: Blocked live emulator (`WHPX hr=80070005`, missing x86_64 AVX under TCG, Qt `opengl32sw` stall, no API 26 image locally) documented candidly across `DEVICE_VALIDATION.md`, `TEST_RESULTS.md`, `RELEASE_EVIDENCE.md`, `SECURITY_REVIEW.md`, and this report.
