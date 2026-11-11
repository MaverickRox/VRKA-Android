# VRKA Android — Test Results

## Post-v5.1.3 supply-chain hardening on `main`

Date: 2026-11-11. This section records unreleased maintenance after v5.1.3; the v5.1.3 release
evidence below remains historical and unchanged.

| Check | Command | Result |
|---|---|---|
| Full JVM suite (with dependency locks and verification metadata) | `.\gradlew.bat --no-daemon --no-configuration-cache --dependency-verification=strict testDebugUnitTest lintDebug lintRelease compileDebugAndroidTestKotlin assembleDebug assembleDebugAndroidTest assembleDevRelease` | PASS: 847 tests in 80 suites; 0 failures, 0 errors, 0 skipped |
| Supply-chain policy suite | `--tests com.mvrk.vrka.SupplyChainPolicyTest` (in full run) | PASS: 10 tests; repository policy, lock/verification metadata cleanliness, pinned Actions & Dependabot, doc/architecture consistency, provenance, LF native inventory & AAR hashes, build-hardening invariants |
| Custom runtime manager suite | `--tests com.mvrk.vrka.runtime.YtDlpRuntimeManagerTest` (in full run) | PASS: 6 tests; probe/size-mismatch rejection, tampered/missing fallback to stock, unverified/tampered activation refusal, active-runtime removal guard, update rollback |
| Custom command policy & request factory suites | `--tests com.mvrk.vrka.CustomCommandTemplatesTest`, `--tests com.mvrk.vrka.DownloadRequestFactoryTest` (in full run) | PASS: 7 + 23 tests; escaping/quote errors, case-insensitive blocked flags/headers, URL argv isolation |
| Release manifest verifier suite | `--tests com.mvrk.vrka.update.security.ReleaseManifestVerifierTest` (in full run) | PASS: 28 tests; published v5.1.1/v5.1.2/v5.1.3 detached signatures and hermetic `K1 -> K2` key-rotation transition fixture |
| Debug lint | `lintDebug` in the same run | PASS: 0 errors; 301 warnings, 4 hints |
| Release lint | `lintRelease` in the same run | PASS: 0 errors; 300 warnings, 4 hints |
| Development release R8 mapping | `assembleDevRelease` in the same run | PASS: non-empty `app/build/outputs/mapping/devRelease/mapping.txt` (`81,658,899` bytes, SHA-256 `776c39359ae6bf41cce8401dfd4b7335e4092e1e0269db516653203a2fb45e7f`); no signing secrets present |
| Dependency locking & diff hygiene | `app/gradle.lockfile` + `git diff --check` | PASS: `git diff --exit-code -- app/gradle.lockfile` exited `0`; `git diff --check` exited `0` |
| Dependency verification | `gradle/verification-metadata.xml` | SHA-256 metadata generated from trusted repositories after mavenLocal removal; `--dependency-verification=strict` enforced |
| Release/debug/androidTest compilation | same run | PASS |
| Debug, androidTest, and development release APKs | same run | PASS without production signing |
| API 36 provider instrumentation | Executed by CI `provider-tests` job (not re-run locally on physical devices; 12 prior v5.1.3 release-time tests recorded below) | See CI workflow run on `main` |

The supply-chain policy test also verifies that `mavenLocal()` is absent from production
resolution, every `uses:` action across `.github/workflows` is pinned to a 40-character commit,
`dependabot.yml`, release lint, and CI `devRelease` R8 mapping retention are configured, release
minification/lint/signing-isolation flags remain intact, `CURRENT_PRODUCTION_ARCHITECTURE.md` matches
Room authority, and native/trust-anchor inventory hashes match the checked-in LF assets and Gradle
verification metadata.

---

## v5.1.3 release verification

Date: 2026-11-10. The v5.1.3 release uses Room as the storage authority. The historical v5.1.2
release results below are retained as they were recorded.

**Source commit used to build the APK:** `90de6b8e69b2b801eed4c838fe510fe91a29a80e`.

| Check | Command or evidence | Result |
|---|---|---|
| Full JVM suite, lint, compilation, and development APKs | `.\gradlew.bat --no-daemon -Duser.home=<empty temp> --rerun-tasks --no-build-cache testDebugUnitTest lintDebug compileDebugAndroidTestKotlin compileReleaseKotlin assembleDebug assembleDevRelease` | PASS: 828 tests in 78 suites; 0 failures, errors, or skipped tests |
| Legacy migration corpus | `LegacyMigrationFixtureTest` in the full JVM run | PASS: 80 cases / 75 files; hash, raw-evidence, integrity, foreign-key, repeat-import, and interruption checks |
| Storage defect and repository suites | `StorageDefectRemediationTest`, `RoomStorageRepositoryTest`, `RoomPublicationOrchestrationTest` | PASS in full JVM run; ownership, cleanup, quarantine, paging, readiness, and fresh-runtime recovery cases |
| Lint | `lintDebug` in the full verification command | PASS: 0 errors; 301 warnings |
| Kotlin and Android test compilation | `compileDebugAndroidTestKotlin`, `compileReleaseKotlin` | PASS |
| Debug and development APK builds | `assembleDebug`, `assembleDevRelease` | PASS without production signing properties |
| API 36 provider instrumentation | `connectedDebugAndroidTest` for `SafPublicationIntegrationTest` and `PublicationStorageIntegrationTest` | PASS: 12 tests; 0 failures, errors, or skips (10 SAF provider tests, 2 MediaStore tests) |
| Release APK build | `assembleRelease`, with production signing properties explicitly selected | PASS |
| Release APK identity | `apksigner`, `aapt2`, `zipalign` | PASS: v2 signature; expected RSA-4096 certificate; package/version, SDK, ABI, alignment, and non-debuggable metadata verified |
| Release manifest | `publishedV513ManifestDetachedSignatureVerifiesWithPinnedPublicKey` | PASS: revision 6 is accepted from installed v5.1.2 revision 5; signature and APK metadata match the release asset |
| In-place upgrade | Official v5.1.2 APK installed over by v5.1.3 APK on an API 36 emulator | PASS: Android retained the original install time; History, Queue, and H.264 setting remained after upgrade and process restart |
| Diff validation | `git diff --check` | PASS |

The fixture corpus lives in `app/src/test/resources/phase0-fixtures`. Its 80 cases cover the
tagged v5.1.0, v5.1.1, and v5.1.2 encoders plus the pre-release baseline. The actual app upgrade
also imported two existing v5.1.2 History entries and retained the saved H.264 setting. The
emulator has an x86_64 runtime while the release APK ships arm64-v8a native libraries, so a real
media transfer was not verified on this emulator. The provider test suite uses a controlled SAF
DocumentsContract provider and does not establish behavior for every OEM provider.

---

# Historical: VRKA Android 5.1.2 — Test Results

Date: 2026-11-09

## Automated checks (current verification run)

| Check | Command | Result |
|---|---|---|
| Original v5.1.2 release-time JVM tests (historical, preserved) | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 747 tests, 73 suites, 0 failures, 0 errors, 0 skipped |
| Current `main` JVM tests after publication-integrity remediation | `./gradlew -Duser.home=<isolated temp dir> --no-daemon --rerun-tasks --no-build-cache testDebugUnitTest` | PASS: 784 tests, 73 suites, 0 failures, 0 errors, 0 skipped |
| Publication recovery suite | `--tests com.mvrk.vrka.PublicationJournalRecoveryTest` | PASS: 33 tests |
| JobStore retention/durability suite | `--tests com.mvrk.vrka.JobStorePersistenceTest` | PASS: 31 tests |
| OpenPGP verifier suite (includes published signatures) | `--tests com.mvrk.vrka.update.security.ReleaseManifestVerifierTest` | PASS: 26 tests; published v5.1.1 and v5.1.2 detached signatures verify with the pinned RSA-4096 key |
| Real MediaStore instrumentation (API 36 emulator) | `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mvrk.vrka.PublicationStorageIntegrationTest` | PASS: 2 tests (finalized-row verification; pending-row crash recovery) |
| Debug Kotlin compilation | `.\gradlew.bat compileDebugKotlin` | PASS |
| Debug APK build (via instrumentation task) | `./gradlew connectedDebugAndroidTest` | PASS: debug APK and test APK built, installed, and exercised |
| Android test-source compilation | `.\gradlew.bat compileDebugAndroidTestKotlin` | PASS |
| Release-variant compilation | `.\gradlew.bat compileReleaseKotlin` | PASS without production signing credentials |
| Development/production release APK builds | not run in this remediation | Not claimed; v5.1.2 artifact evidence remains historical and unchanged |
| Lint | `./gradlew lintDebug` | PASS: 0 errors, 291 warnings |
| `git diff --check` | `git diff --check` | PASS |

**Verified source identity:** `0866d00df5952376a4da137a7667bd24f66c30e8` (`main` and `origin/main`). The previously reported SHA `0866d001bb052a2fe95b6c31e9c20a9a3b680c44` is not a Git object in this repository (verified with `git cat-file -t`); it was a reporting error and must not be cited. No release tag, manifest, signature, or APK was modified by this remediation.

## Publication-integrity remediation on unreleased `main`

The suite at the time of that post-v5.1.2 remediation (784 tests) was not the release-time 747-test count; later hardening adds the separate suite recorded above.

- **PUB-01 (terminal durability):** `JobStore` retention protects every nonterminal job and any job pinned by an unresolved publication transaction, then bounds only safely retained terminal history. All persistence goes through a manager-level lock that selects the snapshot, applies retention, writes, and re-reads the durable file before reporting success. `commitTerminalPublishedJob()` pins the completing job, re-reads the persisted record, retires only the exact transactions created by that attempt, and performs staging cleanup only afterwards. `publishAll()` no longer deletes its staging source.
- **PUB-02 (provider verification):** API 29+ finalization requires an existing row, the expected display name, and `IS_PENDING = 0`; a missing `IS_PENDING` column is treated as unverifiable, never as finalized. Startup recovery re-inspects even `FINALIZED`/`RECORDED` transactions before marking a job `DONE`. API 26–28 uses name-only legacy semantics without pending-state operations.
- **PUB-03 (cleanup and discovery):** every destructive path checks deletion results and persists `CLEANUP_PENDING` on failure. Pending-object discovery is tri-state (`FOUND`, `DEFINITELY_ABSENT`, `UNKNOWN`); provider exceptions and revoked permissions never authorize deletion. Reconciliation is transaction-scoped, so a retry cannot retire an earlier failed attempt.
- **PUB-04 (journal integrity):** the journal requires the exact supported schema, unique transaction IDs, valid timestamps, valid output indexes and batch totals, required pending names with the exact transaction/filename relationship, provider-specific URI structure, and consistent phase/URI/status combinations. Destructive cleanup requires the row to carry the transaction's unique pending name and to be the single discovered object. Corrupt records are preserved; valid jobs are recoverable only when isolation is provable, otherwise recovery fails closed.
- **PUB-05 (staging cleanup):** `deleteRecursively()` results are checked. Failures become durable, bounded retry records; retries occur at startup and never run while an unresolved transaction or a live job still depends on the directory.
- **TEST-01 (coverage accuracy):** recovery tests now reconstruct fresh managers, stores, and journals from temporary filesystem-backed state. The suite includes production-orchestration paths, production methods with injected provider dependencies, and isolated serialization tests. These are not all full production crash simulations.

## Test classification and coverage accuracy

- **Full production-orchestration tests:** `publishAll()` → terminal commit → journal reconciliation → staging cleanup, followed by a fresh-runtime reconstruction; plus crash-between-publish-and-commit and terminal-save-failure scenarios.
- **Production-method tests with injected dependencies:** the majority of `PublicationJournalRecoveryTest` — real manager, publisher, journal, and JobStore methods over a controlled `PublicationStorageOps` fake.
- **Isolated state/serialization tests:** strict journal decoding, quarantine/isolation, retention, and stale-write verification.
- **Android platform integration tests:** two real MediaStore tests executed on an API 36 emulator (`PublicationStorageIntegrationTest`). SAF tree publication and recovery were not executed because a persisted tree grant requires interactive document-picker consent.
- **Fake-provider boundary:** the controlled provider simulates insert/update/query/delete outcomes, pending-column omission, revoked SAF access, and ambiguous discovery. It is deterministic test evidence, not real SAF or multi-OEM provider coverage.

The post-release test expansion on `main` also adds dedicated coverage for updater regression and security invariants:
- Published v5.1.1 and v5.1.2 release manifest verification with the pinned OpenPGP trust anchor.
- Enforcement of an explicit SHA-256/SHA-384/SHA-512 digest allowlist; SHA-224 and all other algorithms are rejected.
- Mandatory issuer fingerprint in the **hashed** subpacket section; absent, unhashed-only, and mismatched fingerprints are rejected.
- Valid v5.1.1 (`50101`, revision 4) to v5.1.2 (`50102`, revision 5) upgrade acceptance across both manifest and package metadata layers.
- Internally consistent same-version installation attempt rejection (`5.1.2` / `50102` over `5.1.2` / `50102`).
- Lower-version downgrade attempt rejection (`5.1.1` / `50101` over `5.1.2` / `50102`).
- Manifest revision rollback rejection (stored revision higher than incoming manifest revision).
- Invalid package ID, version name mismatch, non-canonical filename format, and malformed version metadata rejection.

## Validation & Platform Boundaries

- **Maintainer-Reported Physical Device Upgrade:** On November 9, 2026, the maintainer personally confirmed that updating an existing v5.1.1 installation to v5.1.2 on a physical Android phone was successful. This verifies that the v5.1.1-to-v5.1.2 upgrade package and production signing identity install cleanly over v5.1.1 on real hardware. Successful installation/update was confirmed; however, broader functional testing, UI walkthroughs, and automated instrumentation were not performed as part of that validation. Comprehensive multi-device testing was not conducted. Historical v5.1.1 OnePlus 11 hardware testing is preserved below as historical evidence.
- **Emulator Integration:** An API 36 headless emulator (`Medium_Phone_API_36.0`) booted successfully during this remediation. Two real MediaStore instrumentation tests passed: publication/finalization verification and pending-row crash recovery into durable History. SAF tree publication/recovery and other instrumentation tests were not executed on the emulator.
- **Automated & Failure-Injection Verification:** The current JVM suite (784 tests across 73 suites, 0 failures) covers format selection, size estimation, URL sanitization, updater cryptography, JobStore retention/durability, strict journal validation, and publication recovery using controlled provider dependencies. It does not by itself prove real SAF provider behavior, multi-OEM MediaStore behavior, or physical-device crash recovery.
- **Platform Boundaries:** As documented in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md), Browser Fallback live-site end-to-end media transfers and physical SAF execution on API 26–28 hardware remain documented platform boundaries.

## APK

Official production release artifact: `VRKA-Android-v5.1.2.apk`, package `com.mvrk.vrka`, version `5.1.2` (`50102`), ABI `arm64-v8a`, size 157,243,017 bytes (149.96 MB), SHA-256 `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a`. Signed with the official production release key (APK Signature Scheme v2, RSA 4096-bit).

---

# Historical: VRKA Android 5.1.1 — Test Results


Date: 2026-11-08

## Automated checks

| Check | Command | Result |
|---|---|---|
| Full JVM unit tests | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 732 tests, 72 suites, 0 failures, 0 errors, 0 skipped |
| Debug Kotlin compilation | `.\gradlew.bat compileDebugKotlin` | PASS |
| Android test-source compilation | `./gradlew compileDebugAndroidTestKotlin` | PASS; instrumentation was not executed |
| Release-variant compilation | `.\gradlew.bat compileReleaseKotlin` | PASS without production signing credentials |
| Debug APK | `./gradlew assembleDebug` | PASS |
| Development release APK | `./gradlew assembleDevRelease` | PASS; R8 minification and debug-key signing |
| Lint | `./gradlew lintDebug` | PASS: 277 warnings, 3 hints, 0 errors/fatal; no new lint ID from the v5.1.1 code |
| CI workflow | GitHub Actions CI on `main` (Run ID `42517854024` on commit `ecc3a49`) | PASS in 8m 14s; verified `testDebugUnitTest`, `lintDebug`, `compileDebugAndroidTestKotlin`, `assembleDebug`, `assembleDevRelease`. CI does not execute on physical devices or run Android instrumentation. |

The definitive run exited `0`; stdout/stderr were captured and the XML report timestamps came from that same execution (newest `2026-11-08 23:11:01`). The suite ran with an empty temporary `user.home` and no `.vrka` directory. This supersedes the earlier 690-test report and resolves the reported discrepancy where an independent invocation exited nonzero while old XML files showed passing tests: the current report is from a fresh `--rerun-tasks` execution with the exit code captured directly from Gradle. A test-source guard fails if a unit test references the production signing key. All updater cryptographic tests use generated temporary keys and run unconditionally; the only pinned-key cryptography test verifies the published v5.1.0 manifest with the public trust anchor and requires no secret.

The 5.1.1 suite adds deterministic coverage for: resolution-specific authoritative sizes, AV1-versus-higher-bitrate-VP9 chip selection, revealed-codec preference, audio once, merged and separate audio/video streams, HLS master/variant selection, DASH representations, fragment/bitrate estimates, missing and invalid metadata, overflow, unit conversion, playlist fallback, duplicate formats, browser-candidate URL/header/format/cache separation, expired-candidate failure, candidate replacement/stale estimates, stale resolution responses, rapid switching, JobStore corruption/salvage/schema/write failures, active stream and playlist scope persistence, playlist item parsing, actual-resolution marker parsing and mixed-playlist summaries, stream-marker classification for muxed/video-only/audio-only/unknown destinations, MissAV-style denominator revisions, published-media size accounting, API 26–28 destination policy, diagnostic URL sanitization in URL fields and embedded text (encoded/case/duplicate/JSON-escaped parameters), failure-only source/candidate URLs and export opt-in, strict updater timestamps, and hermetic updater tests.

## Additional marker check

A local, no-network run of the packaged yt-dlp script (2025.11.12, extracted from the bundled AAR) against a generated HLS master with `RESOLUTION=1920x1080` emitted `__VRKA_RES__1080` during a real download. `--simulate` did not execute the `before_dl` print, as expected. The generated media playlist without height emitted `__VRKA_RES__NA`, which is treated as unknown. This validates the marker template using the bundled yt-dlp package, but not the Android native launcher/device or a live site.

## Physical Device Validation

Hardware validation was performed on a connected physical OnePlus 11 (CPH2447, Android 16 / SDK 36, ABI arm64-v8a) via ADB:
- Stream installation of production-signed `VRKA-Android-v5.1.1.apk` via `adb install -r` completed with Success.
- Clean application startup (PID 14291) with 0 runtime exceptions or crashes.
- Evaluated test target `https://youtu.be/qQM5i62f5zQ`:
  - Format chip preflight: Best available (`~54.75 MB`), 2160p 4K (`~54.75 MB`), 1440p 2K (`~27.45 MB`).
  - Confirmation dialog: 2160p at `54.75 MB` (perfect synchronization with 4K chip).
  - Active download transfer: displayed neutral `"Downloading media"` stage (correcting false `"Downloading audio"`), with live speed (`16.61 MiB/s`), remaining time (`0:00 left`), and exact progress (`46.8 MiB / 51.7 MiB`).
  - Finalization & History: completed container published to `Downloads/VRKA` with final size `52.2 MiB` (exact match to target output).
- High-resolution AMOLED screenshots captured and preserved in `docs/screenshots/v5.1.1/`.

*Note: The comprehensive verification breakdown, seven testing classifications, and platform boundaries (including unverified completed live Browser Fallback transfers, unexecuted Android instrumentation tests on hardware, and unverified API 26–28 SAF on physical hardware) are authoritatively documented in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).*

## APK

Official production release artifact: `VRKA-Android-v5.1.1.apk`, package `com.mvrk.vrka`, version `5.1.1` (`50101`), ABI `arm64-v8a`, size 157,231,575 bytes, SHA-256 `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`. Signed with the official production release key (APK Signature Scheme v2, RSA 4096-bit).

---

# Historical: VRKA Android 5.1.0 — Test Results

The v5.1.0 records below are retained verbatim. Historical documentation contained conflicting counts (601 and 639 tests in different files); those older records are not used as the v5.1.1 baseline.

Date: 2026-10-31

## Automated checks

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS: 601 tests, 58 suites, 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 275 warnings, 0 hints, no Error/Fatal issues |
| `:app:assembleRelease` | PASS: production-signed APK built |
| Release signing verification | PASS: scheme v2, RSA 4096-bit, fingerprint matched |

The 5.1.0 test suite covers full 14-language key parity, tag normalization, RTL configuration, download size estimation accuracy and caching, share sheet format resolution consistency, component updater state stability, GeckoView privacy defaults, single-icon notification presentation, and custom yt-dlp runtime validation.

## Device boundary

Physical OnePlus 11 hardware was unavailable during final 5.1.0 publication. Emulator-based testing and automated verification suites passed. The earlier [5.0 physical validation report](PHYSICAL_VALIDATION_REPORT.md) remains historical context.

## APK

Canonical file: `VRKA-Android-v5.1.0.apk`. Package `com.mvrk.vrka`, version `5.1.0` (`50100`), ABI `arm64-v8a`. Size: 157,192,535 bytes. SHA-256: `CA5C74F0C59A98FE225D133A79E98A9E2913E8F82BFDAC0C841A27F2DCD1F28A`. `apksigner` verified the production RSA 4096-bit certificate using APK Signature Scheme v2. Full metadata is in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

---

# Historical: VRKA Android 5.0.2 — Test Results

Date: 2026-10-05

## Automated checks

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS: 373 tests, 34 suites, 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 88 warnings, 1 hint, no Error/Fatal issues |
| `:app:assembleRelease` | PASS: production-signed APK |

The added updater coverage checks WorkManager persistent background downloads, retry semantics on transient connection aborts / resets, exponential backoff, safe private staging, completed APK preservation, update-ready notifications, unique download work deduplication, version ordering (including 4.5.3 < 5.0.1 < 5.0.2), release and asset validation, secure download URLs and redirects, install metadata, non-downgrade version codes, and signer fingerprints.

## Device boundary

No emulator, ADB, device farm, or physical-device testing was performed for 5.0.2. The separate [5.0 physical validation report](PHYSICAL_VALIDATION_REPORT.md) is historical and applies only to that release APK.

## APK

Canonical file: `VRKA-Android-v5.0.2.apk`. Package `com.mvrk.vrka`, version `5.0.2` (`50002`), ABI `arm64-v8a`. Size: 155,338,862 bytes. SHA-256: `136707980C34EA4242AB3B183571BC24C063A3DDB22BDB0A1CF56258345B9D54`. `apksigner` verified the production RSA 4096-bit certificate using APK Signature Scheme v2. Full metadata is in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## Historical checks

The 5.0.1 release record reported 364 tests across 33 suites and a separate 5.0.1 artifact. The 5.0 release record reported 348 tests across 33 suites and a separate OnePlus 11 inspection of the 5.0 APK. Those historical results remain immutable.
