# VRKA Android 5.1.5 — Release Evidence

- **Verification date:** 2026-11-13
- **Source version:** `5.1.5` (`versionCode` 50105)
- **APK build source commit / tag target:** `c8ea646131634ecad6c6c941fb14b8f5f3d46d47`
- **Release tag:** `v5.1.5`
- **Release page:** https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.5

## Automated checks (`v5.1.5`)

| Check | Command or evidence | Result |
| --- | --- | --- |
| Full JVM unit suite & strict build | `.\gradlew testDebugUnitTest` | PASS: `949` tests / `93` suites (`0` failures, `0` errors, `0` skipped); all suites passing cleanly |
| Production Release APK (`VRKA-Android-v5.1.5.apk`) | `app/build/outputs/apk/release/app-release.apk`, `apksigner verify --verbose` | PASS: `155,408,523` bytes (`cb61a6ba2f9ae2994c6c8bc9aa2d6afaacf325529adaa813739bfdece32e7c6b`); single `classes.dex`; V2 signature verified with official key (`9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`) |
| Hardware Instrumentation & Device Gates (OnePlus 11, Android 16) | `adb shell am instrument` + live verification on physical OnePlus 11 (`897f6ce5`, Android 16 / SDK 36) | PASS: Room v2 migration, SAF publication, real MediaStore publication with `IS_PENDING` finalization, native ARM64 yt-dlp & FFmpeg verified on device; live Instagram Reel extraction and transfer verified; composite playlist selection popup verified; UIDT notification channels verified |
| Track A: In-Place Upgrade Gate (`v5.1.4 -> v5.1.5`) | `adb install -r app-release.apk` over signed `v5.1.4` baseline | PASS: In-place upgrade succeeded (`50104 -> 50105`); cold launch verified; zero crashes; Room schema migration cleanly handled; Settings preserved; up-to-date manifest resolution verified |
| Track B: Reinstall & Storage Persistence Gate | Clean install over pre-existing `/sdcard/Download/VRKA/CollisionTest.mp4` | PASS: Pre-existing file preserved with zero truncation; clean startup with GeckoView and download destination commit |

## Production release artifact (`v5.1.5`)

- **APK filename:** `VRKA-Android-v5.1.5.apk`
- **Size:** `155,408,523` bytes (`155408523`)
- **SHA-256:** `cb61a6ba2f9ae2994c6c8bc9aa2d6afaacf325529adaa813739bfdece32e7c6b`
- **Package / version:** `com.mvrk.vrka`, version name `5.1.5`, version code `50105`
- **SDK / ABI:** `minSdkVersion` `26`, `targetSdkVersion` `36`, `arm64-v8a` only, non-debuggable
- **Signer:** `CN=VRKA, OU=MVRK, O=MVRK, L=Private, ST=Private, C=IN` (RSA 4096-bit)
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **APK signing:** APK Signature Scheme v2 verified (`v1`/`v3` absent); ZIP alignment verified (`zipalign -c -v 4`).

## Signed updater metadata (`v5.1.5`)

- **Manifest:** `VRKA-Android-v5.1.5.manifest.json` (`metadata_revision` `8`)
- **Source commit:** `c8ea646131634ecad6c6c941fb14b8f5f3d46d47`
- **Validity:** issued `2026-11-13T15:35:58Z`; expires `2027-11-13T15:35:58Z`
- **Manifest SHA-256:** `6527bc3041cf3ada52a43f100bc13a7bfcce38f0478b550ee0e36bc62ea10f73`
- **Detached signature:** `VRKA-Android-v5.1.5.manifest.json.asc`; SHA-256 `9ccf3ac0943a857df1efcbdc6a32084744a8cd563a40efbcdd88f8e43ebc8f6d`
- **OpenPGP Key ID:** `0xD519380BEBA1B9F7` (`71165A658B3A8612AC2568C6D519380BEBA1B9F7`)

---

# VRKA Android 5.1.4 — Release Evidence

- **Verification date:** 2026-11-11
- **Source version:** `5.1.4` (`versionCode` 50104)
- **APK build source commit (`C1`) / tag target:** `6717da543df98fca33cdce78f8bc098edf6cef60`
- **Source commit CI run:** https://github.com/MaverickRox/VRKA-Android/actions/runs/42988201135 (`Unit tests, lint and development build`: pass in `11m41s`; `Real MediaStore and SAF provider tests (API 36)`: pass in `6m7s`)
- **Release page:** https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.4
- **Provenance:** Annotated tag `v5.1.4` targets the exact APK build source commit `6717da543df98fca33cdce78f8bc098edf6cef60`; this commit records the signed manifest, checksums, and release evidence after `C1` CI validation passed. See [docs/RELEASE_PROVENANCE.md](docs/RELEASE_PROVENANCE.md).

## Automated checks (`v5.1.4`)

| Check | Command or evidence | Result |
| --- | --- | --- |
| Full JVM unit suite & strict build | `testDebugUnitTest lintDebug lintRelease compileDebugAndroidTestKotlin compileReleaseKotlin assembleDebug assembleDebugAndroidTest assembleDevRelease --dependency-verification=strict` | PASS: `849` tests / `80` suites (`0` failures, `0` errors, `0` skipped); `lintDebug` `0` errors (`301` warnings); `lintRelease` `0` errors (`300` warnings); `devRelease` mapping `81,677,479` bytes (`1a745660c1bd6af6e99344ca53c5f5bbfd47080418c560fff48e29a411f7c38a`) |
| GitHub Actions CI (`C1`) | `Android CI` run `42988201135` on `6717da543df98fca33cdce78f8bc098edf6cef60` | PASS: `Unit tests, lint and development build` (`11m41s`) and `Real MediaStore and SAF provider tests (API 36)` (`6m7s`) |
| Production release build (`C1`) | `assembleRelease --dependency-verification=strict` on clean `6717da543df98fca33cdce78f8bc098edf6cef60` | PASS: `157,431,395` bytes; `mapping.txt` archived outside repo (`1a745660c1bd6af6e99344ca53c5f5bbfd47080418c560fff48e29a411f7c38a`) |
| APK signature & packaging verification | `apksigner verify --verbose --print-certs`, `zipalign -c -v 4`, `aapt2 dump badging` | PASS: APK Signature Scheme v2 only; RSA 4096; `com.mvrk.vrka` `5.1.4` (`50104`); `minSdkVersion` `26`, `targetSdkVersion` `36`, `arm64-v8a`; non-debuggable; aligned (`4` bytes) |
| Detached OpenPGP & manifest check | `gpg --verify` + `publishedV514ManifestDetachedSignatureVerifiesWithPinnedPublicKey` | PASS: revision `7`, code `50104`, key `71165A658B3A8612AC2568C6D519380BEBA1B9F7` (`0xD519380BEBA1B9F7`) |
| Live asset re-download & API 36 upgrade | Post-publication 5-asset download (`gh release download v5.1.4` at `2026-11-11T09:12:46Z`), `SHA256SUMS` + `gpg --verify` + `apksigner verify` + `aapt2 dump badging`, `C2` CI rerun (`42990621462`: `Unit tests` `130426066416` `9m37s`, `MediaStore/SAF API 36` `130426067712` `5m30s`), and API 36 emulator `v5.1.3` -> `v5.1.4` live updater (Scenario A) + `adb install -r` (Scenario B) upgrade checks | PASS: all 5 re-downloaded assets matched `SHA256SUMS`/GPG/APK signing; both `C2` CI jobs passed; both `v5.1.3` -> `v5.1.4` emulator upgrades preserved `firstInstallTime`, Room History, Diagnostics, and Settings (see `DEVICE_VALIDATION.md`) |

## Production release artifact (`v5.1.4`)

- **APK filename:** `VRKA-Android-v5.1.4.apk` (`app/build/outputs/apk/release/app-release.apk`)
- **Size:** `157,431,395` bytes (`157431395`)
- **SHA-256:** `5535291ff60c89852f1b2b960379a3b2637576e833ab3951759522110bba5d97`
- **Package / version:** `com.mvrk.vrka`, version name `5.1.4`, version code `50104`
- **SDK / ABI:** `minSdkVersion` `26`, `targetSdkVersion` `36`, `arm64-v8a` only, non-debuggable
- **Signer:** `CN=VRKA, OU=MVRK, O=MVRK, L=Private, ST=Private, C=IN` (RSA 4096-bit)
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **APK signing:** APK Signature Scheme v2 verified (`v1`/`v3` absent); ZIP alignment verified (`zipalign -c -v 4`).

## Signed updater metadata (`v5.1.4`)

- **Manifest:** `VRKA-Android-v5.1.4.manifest.json` (`metadata_revision` `7`)
- **Source commit:** `6717da543df98fca33cdce78f8bc098edf6cef60`
- **Validity:** issued `2026-11-11T08:50:07Z`; expires `2027-11-11T08:50:07Z`
- **Manifest SHA-256:** `3b8cbf09cd3e836fe655714ebde7486d727f4988cb98734ce045a7d8ceb5d18e`
- **Detached signature:** `VRKA-Android-v5.1.4.manifest.json.asc`; SHA-256 `088c131eb2412736d4dabd6fab0a44bb3812a088a1b783c678d51bbdb6ea7a25`
- **OpenPGP signer:** RSA 4096-bit key fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7` (`0xD519380BEBA1B9F7`)
- **Public key asset:** `VRKA-Release-Public-Key.asc`; SHA-256 `7f2a6ef42a4f66289e31c4fefff44b7ba294f9d0c696ace1f20602401641f38a`
- **Checksums (`SHA256SUMS`):** SHA-256 `ce78a365f64c7b51c43e67c113666b0dc59f8a13d7b5de5b55594b30fa89089a` (historical `v5.1.3`..`v4.0.3` lines preserved unchanged)

---

# Historical: VRKA Android 5.1.3 — Release Evidence

- **Verification date:** 2026-11-10
- **Source version:** `5.1.3` (`versionCode` 50103)
- **APK build source commit:** `90de6b8e69b2b801eed4c838fe510fe91a29a80e`
- **Release page:** https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.3
- **Provenance:** the annotated tag `7527e800e59bf6774b33011f3a23ca2049a41df1` targets the evidence commit `daac0b5b7f14dc5ce6b2af0be8fcaf5119ebd2b8`; the APK build source is the parent `90de6b8e69b2b801eed4c838fe510fe91a29a80e`. No CI-generated attestation exists for v5.1.3. See [docs/RELEASE_PROVENANCE.md](docs/RELEASE_PROVENANCE.md).

**Validation state:** 828 JVM tests across 78 suites pass with no failures, errors, or skips; lint
has 0 errors; Kotlin and Android test-source compilation pass; 12 API 36 provider instrumentation
tests pass; the signed production APK and OpenPGP manifest verify; and the official v5.1.2 app
upgrades in place with its visible History, Queue, and saved setting preserved. v5.1.3 was not
validated on a physical device. The x86_64 emulator cannot run the APK's arm64 native downloader.

## Automated checks

| Check | Command or evidence | Result |
| --- | --- | --- |
| Full JVM suite and local build checks | `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin compileReleaseKotlin assembleDebug assembleDevRelease` | PASS: 828 tests / 78 suites; 0 failures, 0 errors, or skips; lint 0 errors |
| Legacy migration fixtures | `LegacyMigrationFixtureTest` in the full suite | PASS: 80 cases / 75 files; hashes, preservation, database integrity, foreign keys, repeat migration, and interrupted migration verified |
| Storage recovery regressions | `StorageDefectRemediationTest`, `RoomStorageRepositoryTest`, `RoomPublicationOrchestrationTest` | PASS in the full JVM suite |
| API 36 provider instrumentation | `connectedDebugAndroidTest` with `SafPublicationIntegrationTest` and `PublicationStorageIntegrationTest` | PASS: 12 tests, 0 failures/errors/skips (10 SAF test-provider, 2 MediaStore) |
| Production release build | `assembleRelease` with configured production signing properties explicitly selected | PASS |
| APK signature and metadata | `apksigner verify`, `aapt2 dump badging`, `zipalign -c -v 4` | PASS: v2 signature, package/version, SDK levels, ABI, alignment, non-debuggable flag, and certificate verified |
| Signed updater manifest | `publishedV513ManifestDetachedSignatureVerifiesWithPinnedPublicKey` | PASS: pinned verifier accepts v5.1.2 code 50102 / revision 5 to v5.1.3 code 50103 / revision 6 |
| In-place upgrade | Install official v5.1.2 APK, then `adb install -r` v5.1.3 on API 36 emulator | PASS: first install time unchanged; app launched; History, Queue, and H.264 setting persisted through restart |

## Production release artifact

- **APK:** `app/build/outputs/apk/release/app-release.apk` (`VRKA-Android-v5.1.3.apk` for release upload)
- **Size:** 157,428,789 bytes
- **SHA-256:** `c9f58d3a97d728315b51fc92f43b5f54b88ae4ad9c25b3b22ea0cd8338549ddf`
- **Package/version:** `com.mvrk.vrka`, version name `5.1.3`, version code `50103`
- **SDK/ABI:** min SDK 26; target SDK 36; `arm64-v8a` only
- **Signer:** `CN=VRKA, OU=MVRK, O=MVRK, L=Private, ST=Private, C=IN`; RSA 4096-bit
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **APK signing:** APK Signature Scheme v2 verified; v1 and v3 signatures are absent. ZIP alignment and non-debuggable badging verified.

## Signed updater metadata

- **Manifest:** `VRKA-Android-v5.1.3.manifest.json`; metadata revision `6`
- **Source commit:** `90de6b8e69b2b801eed4c838fe510fe91a29a80e`
- **Validity:** issued `2026-11-10T17:19:31Z`; expires `2027-11-10T17:19:31Z`
- **Manifest SHA-256:** `3e5f8593731e401c236b74c1ce727e831c80c71d05c486ab92a04fb5597e528c`
- **Detached signature:** `VRKA-Android-v5.1.3.manifest.json.asc`; SHA-256 `ee142c5970d827cfdb882e83b7878ae512799667ef087e5f4ee0ef7d984757a8`
- **OpenPGP signer:** RSA-4096 key fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7` (`0xD519380BEBA1B9F7`); GPG and the app's pinned-key verifier passed.
- `SHA256SUMS` contains entries for the v5.1.3 APK and both manifest files. Existing v5.1.2 release assets and their historical checksums remain unchanged.

## Emulator validation boundary

The official v5.1.2 APK was installed first on an API 36 `sdk_gphone64_x86_64` emulator. The v5.1.3 package installed over it with the original `firstInstallTime` preserved. Two failed History rows, the empty Queue, and the enabled H.264 Compatibility Mode remained visible after installation and a force-stop/restart. The emulator uses x86_64, while the application ships arm64-v8a native libraries; no production media transfer was verified. SAF instrumentation uses a controlled `DocumentsContract` test provider and does not establish behavior for every OEM provider.

---

# Historical: VRKA Android 5.1.2 — Release Evidence

> [!NOTE]
> This section records the v5.1.2 artifact and its release evidence as published on 2026-11-09.
> v5.1.3 uses the Room/SQLite storage architecture described above. The v5.1.2 tag, APK,
> manifest, signature, checksum asset, and version metadata remain unchanged.

**Verification date:** 2026-11-09

**Source version:** `5.1.2` (`versionCode` 50102)
**Source revision:** `main` (Official release published at https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2).
**Validation state:** JVM unit tests (747 tests in 73 suites at release time; 784 tests in 73 suites on current `main`, 0 failures), Android test-source compilation, release-variant Kotlin compilation, lint (0 errors), 2 real MediaStore instrumentation tests on an API 36 emulator, 33 publication recovery tests, and maintainer-reported physical-device upgrade validation. The controlled provider suite is deterministic test evidence, not complete real-SAF or multi-OEM coverage; SAF tree integration was not executed. No release artifact, manifest, signature, or tag was modified by the post-release remediation.

## Automated checks

| Check | Command | Result |
| --- | --- | --- |
| Full JVM unit tests (release-time validation) | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 747 tests in 73 suites; 0 failures, 0 errors, 0 skipped |
| Full JVM unit tests (current `main` remediation) | `./gradlew -Duser.home=<isolated temp dir> --no-daemon --rerun-tasks --no-build-cache testDebugUnitTest` | PASS: 784 tests in 73 suites; 0 failures, 0 errors, 0 skipped |
| Publication recovery suite | `--tests com.mvrk.vrka.PublicationJournalRecoveryTest` | PASS: 33 tests |
| JobStore retention/durability suite | `--tests com.mvrk.vrka.JobStorePersistenceTest` | PASS: 31 tests |
| OpenPGP verifier suite | `--tests com.mvrk.vrka.update.security.ReleaseManifestVerifierTest` | PASS: 26 tests; published v5.1.1/v5.1.2 signatures verify |
| Real MediaStore instrumentation (API 36 emulator) | `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mvrk.vrka.PublicationStorageIntegrationTest` | PASS: 2 tests (finalization; pending-row crash recovery) |
| Debug Kotlin compilation | `./gradlew compileDebugKotlin` | PASS |
| Android test-source compilation | `./gradlew compileDebugAndroidTestKotlin` | PASS |
| Release-variant compilation | `./gradlew compileReleaseKotlin` | PASS without production signing credentials |
| Debug APK | built and installed by `connectedDebugAndroidTest` | PASS |
| Development/production release APK | not rebuilt in this remediation | Not claimed; published v5.1.2 artifact preserved |
| Lint | `./gradlew lintDebug` | PASS: 0 errors, 291 warnings |
| `git diff --check` | `git diff --check` | PASS |

**Verified source identity:** `0866d00df5952376a4da137a7667bd24f66c30e8`. The earlier reported SHA `0866d001bb052a2fe95b6c31e9c20a9a3b680c44` is not a Git object and was a reporting error; all reports use the verified object SHA.

## Publication Recovery Audit Remediation (Post-Release v5.1.2)

- **PUB-01 (HIGH — Terminal Persistence Durability):** `JobStore` now protects all nonterminal jobs plus every job pinned by an unresolved publication transaction, and bounds only safely retained terminal history. A manager-level lock serializes snapshot selection, retention, write, and a read-back verification of the exact durable record. `commitTerminalPublishedJob()` pins the completing job, verifies the persisted terminal record, retires only that attempt's transaction IDs, and only then cleans staging. `publishAll()` no longer deletes its staging source.
- **PUB-02 (MEDIUM — MediaStore Finalization and Recovery Verification):** API 29+ verification requires the provider row, expected display name, and `IS_PENDING = 0`; a missing `IS_PENDING` column is unverifiable, never finalized. Startup recovery re-inspects `FINALIZED`/`RECORDED` entries before `DONE`. API 26–28 uses name-only legacy semantics.
- **PUB-03 (MEDIUM — Cleanup Failure and URI Discovery):** Deletion results are checked on every path and failures persist `CLEANUP_PENDING`. Discovery is tri-state (`FOUND`, `DEFINITELY_ABSENT`, `UNKNOWN`); provider errors and revoked permissions never authorize deletion. Reconciliation is transaction-scoped so retries cannot retire earlier failed attempts.
- **PUB-04 (LOW — Journal Schema and URI Ownership):** The journal enforces the exact schema, unique transaction IDs, valid timestamps/indexes/batch totals, the exact pending-name relationship, provider-specific URI structure, and consistent phase/URI/status combinations. Destructive cleanup requires the unique transaction pending name and a single matching provider object. Corrupt records are preserved; valid jobs recover only when isolation is provable, otherwise recovery fails closed.
- **PUB-05 (LOW — Staging Cleanup Durability):** Staging deletion results are checked, failures become bounded durable retry records, and retries never run while an unresolved transaction or live job depends on the directory.
- **TEST-01 (MEDIUM — Coverage Accuracy and Fresh-Runtime Testing):** Recovery tests reconstruct fresh managers, stores, and journals from temporary filesystem-backed state. Coverage is classified as production-orchestration, production-method with injected dependencies, isolated serialization, and Android platform integration. Not all tests are full production crash simulations.
- **DOC-01 (`DEVICE_VALIDATION.md`):** Reconciled documentation so historical v5.1.1 sections remain strictly historical without retroactive claims.
- **DOC-02 (`CURRENT_PRODUCTION_ARCHITECTURE.md`, `README.md`):** Corrected legacy references from `DownloadJobStore` and `DownloadHistoryStore` to unified `JobStore (JSON Storage)`.
- **DOC-03 (`SECURITY.md`, `ReleaseManifestVerifier.kt`):** Enforced an explicit SHA-256/SHA-384/SHA-512 allowlist (SHA-224 and all other algorithms rejected), RSA-only signature keys, and a mandatory issuer fingerprint in the hashed subpacket section matching the pinned key. Absent, unhashed-only, or mismatched fingerprints are rejected. Published v5.1.1 and v5.1.2 signatures still verify with the production verifier and pinned public key.
- **DOC-04 (documentation accuracy):** Reconciled test counts (`747` release-time vs `784` current `main`), documented the exact source SHA, corrected the non-existent SHA reference, and replaced overstated "complete production simulation" claims with accurate test classifications.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, OpenJDK 21.0.12, Java source/target 17. Compile SDK 37 (minor API level 2), target API 36, minimum API 26, `arm64-v8a` only.

## Production Release Artifact

- Production release artifact: `VRKA-Android-v5.1.2.apk`
- Size: 157,243,017 bytes (149.96 MB)
- SHA-256: `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a`
- SHA-1: `81ff63b8b0fd6ff7125d0e1c7b5b689d35a0e87c`
- Package: `com.mvrk.vrka`; versionName `5.1.2`; versionCode `50102`; minSdk 26; targetSdk 36
- Signer: Official VRKA Android production release identity (`CN=VRKA`)
- Android Signing Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- Android Signing Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
- Verification: APK Signature Scheme v2 verified (`true`), `zipalign -c -v 4` verified, `aapt2 dump badging` verified non-debuggable.

## Device Validation & Emulator Boundary

- **Maintainer-Reported Physical Device Upgrade:** On November 9, 2026, the maintainer personally confirmed that updating an existing v5.1.1 installation to v5.1.2 on a physical Android phone was successful. This verifies that the v5.1.1-to-v5.1.2 upgrade package and signing identity are usable on real hardware. Successful installation/update was confirmed; however, broader functional testing, UI walkthroughs, and automated instrumentation were not performed as part of this validation. Comprehensive multi-device testing was not conducted. Historical v5.1.1 OnePlus 11 hardware testing is preserved below as historical evidence.
- **Emulator Integration:** An API 36 headless emulator (`Medium_Phone_API_36.0`) booted successfully. Two real MediaStore instrumentation tests passed: publication/finalization verification and pending-row crash recovery. SAF tree integration and other instrumentation tests were not executed.
- **Automated & Failure-Injection Verification:** The current JVM suite (747 tests at release time; 784 tests across 73 suites on `main`, 0 failures) covers format selection, size estimation, URL sanitization, updater cryptography, JobStore durability/retention, strict journal validation, and 33 publication recovery tests. These are deterministic injected-dependency tests; they do not alone prove real SAF behavior, multi-OEM MediaStore behavior, or physical-device crash recovery.
- **Platform Boundaries:** Authoritative subsystem verification classifications and unverified platform boundaries are defined in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

---

# Historical: VRKA Android 5.1.1 — Release Evidence


**Verification date:** 2026-11-08

**Source version:** `5.1.1` (`versionCode` 50101)
**Source revision:** `main` at `ef656e5c4e3a145847faa52186b6aa21625447a4` (tagged `v5.1.1`). Official release published at https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.1.
**Validation state:** JVM unit tests, Android test-source compilation, debug/devRelease builds, release-variant Kotlin compilation, lint, APK metadata, and hermetic updater security tests. Physical device validation performed on OnePlus 11 (Android 16, SDK 36, ARM64).

## Automated checks

| Check | Command | Result |
| --- | --- | --- |
| Full JVM unit tests | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 732 tests in 72 suites; 0 failures, 0 errors, 0 skipped |
| Debug Kotlin compilation | `./gradlew compileDebugKotlin` | PASS |
| Android test-source compilation | `./gradlew compileDebugAndroidTestKotlin` | PASS (instrumentation not executed) |
| Release-variant compilation without signing | `./gradlew compileReleaseKotlin` | PASS |
| Debug APK | `./gradlew assembleDebug` | PASS |
| Development release APK (R8, debug keystore) | `./gradlew assembleDevRelease` | PASS |
| Lint | `./gradlew lintDebug` | PASS: 277 warnings, 3 hints, 0 errors/fatal. Warnings in modified files are existing `UseKtx`, `LocalContextResourcesRead` and `ModifierParameter` patterns; the corrected estimator, JobStore, diagnostics, updater, destination and new accessibility code add no new lint ID |
| CI workflow execution | GitHub Actions CI on `main` | PASS: 100% build & test pass (Run ID `42517854024` on commit `ecc3a49`, duration 8m 14s; includes `testDebugUnitTest`, `lintDebug`, `compileDebugAndroidTestKotlin`, `assembleDebug`, `assembleDevRelease`) |

Result: `BUILD SUCCESSFUL` for every command. The definitive unit run exited `0`; stdout and stderr were captured to `/tmp/vrka-unit-stdout.txt` and `/tmp/vrka-unit-stderr.txt`, and the XML report timestamps came from that same run (newest `2026-11-08 23:11:01`). The unit suite ran with an empty temporary `user.home`; the temporary home contained no `.vrka` directory. A regression test scans the unit-test sources to ensure no test references the production signing key. All previously key-gated cryptographic tests now run on every normal run with generated temporary OpenPGP keys.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, OpenJDK 21.0.12, Java source/target 17. Compile SDK 37 (minor API level 2), target API 36, minimum API 26, `arm64-v8a` only.

## Production Release Artifact

- Production release artifact: `VRKA-Android-v5.1.1.apk`
- Size: 157,231,575 bytes
- SHA-256: `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`
- SHA-1: `acc9b903669158f399a1c93607309cb9d5649fb0`
- Package: `com.mvrk.vrka`; versionName `5.1.1`; versionCode `50101`; minSdk 26; targetSdk 36
- Signer: Official VRKA Android production release identity (`CN=VRKA`)
- Android Signing Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- Android Signing Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
- Verification: APK Signature Scheme v2 verified (`true`), `zipalign -c -v 4` verified, `aapt2 dump badging` verified non-debuggable.

## Physical Device Verification

Tested on connected physical OnePlus 11 (CPH2447, Android 16 / SDK 36, arm64-v8a) via ADB:
- Stream install `VRKA-Android-v5.1.1.apk` succeeded without data wipe.
- Live test URL `https://youtu.be/qQM5i62f5zQ`:
  - Preflight format chip estimates: Best available (`~54.75 MB`), 4K (`~54.75 MB`), 1440p (`~27.45 MB`).
  - Confirmation dialog: 2160p at `54.75 MB` (synchronized with 4K chip).
  - Active download transfer: displayed `"Downloading media"` neutral state (correcting `"Downloading audio"`), `16.61 MiB/s`, `Downloaded 46.8 MiB / 51.7 MiB`.
  - Final output published to `Downloads/VRKA` at `52.2 MiB` (exact match to target output).
- Creative Commons Multi-Download & Audio Extraction Demonstration:
  - Video format preflight: Best available and 4K chips synchronized at `~743 MB` on Big Buck Bunny 4K (CC-BY 3.0 Blender Foundation).
  - Audio extraction: downloaded Big Buck Bunny in Audio mode (MP3, 320 kbps), completing at `25.7 MiB` in `Downloads/VRKA`.
  - Multi-job active Queue: *Tears of Steel* (CC-BY 3.0 Blender Foundation) actively transferring at `8.72 MiB/s` (`148.0 MiB / ~171.9 MiB`) while *Big Buck Bunny* remained queued in sequential pipeline.
  - Completed History: verified multi-job storage containing *Tears of Steel* (Video 800p, `194.4 MiB`), *Sintel* (Video 872p, `179.8 MiB`), and *Big Buck Bunny* (Audio MP3 320 kbps, `25.7 MiB`) with active Open/Share/Delete controls.
- Passive Browser Fallback & Stream Discovery:
  - Navigated to Shaka Player adaptive streaming demo in GeckoView private session.
  - Initiated playback of "Big Buck Bunny: the Dark Truths" (HLS) and verified first-party `VRKA Media Detector` intercepted 11 candidate streams with uBlock Origin filtering.
- Executed full-resolution, keyboard-free physical device verification on physical OnePlus 11.

*Note: Authoritative device-validation findings, subsystem classifications, and platform boundaries (including unverified completed live Browser Fallback transfers, unexecuted Android instrumentation tests on hardware, and unverified API 26–28 SAF on physical hardware) are defined in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).*

## Published v5.1.0 manifest integrity resolution

The v5.1.0 manifest hash discrepancy was investigated against every authoritative source:

| Source | Result |
| --- | --- |
| Git blob at `HEAD:VRKA-Android-v5.1.0.manifest.json` | 862 bytes, SHA-256 `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E` |
| GitHub v5.1.0 release asset | 862 bytes, same SHA-256, byte-identical to the repository LF file |
| Release `SHA256SUMS` | Declares the same `F1A47B342061…` manifest hash |
| Detached signature | `publishedManifestDetachedSignatureVerifiesWithPinnedPublicKey` verifies the release asset with the pinned public trust anchor |
| CRLF-rendered worktree variant | 891 bytes, SHA-256 `20C14FB8919C0925708493C0CAE8DF034D9B168DB157CB61E66EF208979DD60` — not the release asset |
| Audit value `F1A47B3463BD53F445AEF5535DFDBC03E48D6C6418EFA739BFFD31569B78D8D3` | Not found in the Git object, working tree, release asset, or release checksums |

The canonical published hash is therefore `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`. The different audit value cannot be reproduced from the published artifact. `.gitattributes` now pins the signed manifest and checksum files to LF so a Windows checkout cannot silently change the signed bytes; the manifest content and both published hashes are unchanged.

## v5.1.0 → v5.1.1 in-app update

The signed in-app updater download and verification pipeline was verified through hermetic end-to-end unit tests (`ReleasePipelineEndToEndTest`), but an in-place upgrade executed from an installed v5.1.0 client has not been exercised on hardware. The official v5.1.1 release assets (`VRKA-Android-v5.1.1.apk`, `VRKA-Android-v5.1.1.manifest.json`, `VRKA-Android-v5.1.1.manifest.json.asc`, and `SHA256SUMS`) are published on GitHub Releases. Users upgrading from v5.0.2 still require a one-time manual install due to the historical v5.0.2 filename validation defect.

## Actual-resolution marker validation

The temporary development environment's yt-dlp 2026.06.09 was run against a locally generated HLS master with `RESOLUTION=1920x1080` and no external site:

- `--simulate` does not execute `before_dl` prints.
- A real local transfer with `--print "before_dl:__VRKA_RES__%(height)s"` emitted `__VRKA_RES__1080`.
- A media playlist without height metadata emitted `__VRKA_RES__NA`, which the parser treats as unknown and falls back to file metadata validation.

This validates the marker syntax and output shape, not the bundled Android runtime or a real site. Device/live-site validation remains pending.

## Confirmed media-stage classification correction

The MissAV report was traced to two interacting sources:

1. `DownloadSizeTracker` treated any >5% change in yt-dlp's reported total as a new stream. A single muxed file whose approximate total moved from about 3.61 GiB to 4.66 GiB therefore incremented the stream index.
2. `VrkaDownloadManager` inferred the stage from the requested mode and stream index: the first stream was assumed video and every later index was assumed audio. A revised denominator therefore changed a video transfer to “Downloading audio.”

The correction removes denominator-based stream creation. Stream boundaries come only from an explicit `Destination:` event, and the stage comes from the yt-dlp `before_dl` stream marker (`format_id`, `ext`, `vcodec`, `acodec`, `height`). Destination extension is a fallback only; unknown/muxed values use the neutral media stage. Playlist item resets clear the stage and marker queue. A changed denominator stays on the same stream and becomes approximate.

The exact packaged yt-dlp script (`2025.11.12`, extracted from the bundled AAR) was run against a locally generated 1080p HLS master and emitted:

`__VRKA_STREAM__3000|mp4|NA|NA|1080`

It was classified as video, not audio. The repository’s MissAV domain probe was Cloudflare-blocked (`HTTP 403`), so no live MissAV transfer could be executed in this environment.

## Failure-only URL diagnostics

`DiagnosticEntry` now carries optional, sanitized `sourceUrl`, `candidateUrl` and `jobAttempt` fields. Terminal failures populate the original submitted source URL and Browser Fallback candidate; successful downloads and successful retries clear URL context through `DiagnosticStore.clearUrlsForJob`. Size-preflight failures no longer persist a source URL. Diagnostic export shows a warning and an explicit “include full original source URL” opt-in; credentials, source userinfo, signed query values and fragments remain redacted even when opted in. Older diagnostic records load with empty optional fields.

## Finalization performance investigation

No Android/SAF measurement was possible without a compatible device. Stage timing logs were added to the manager (`Finalize job=… stage=… elapsedMs=…`) and to JobStore saves so a device run can measure transfer exit, marker persistence, output discovery, transcoding, validation, size accounting, publication, completion state and cleanup separately.

Evidence-backed reductions implemented:
- Terminal job updates no longer enqueue the conflated JobStore save and then also perform the direct durable save; the duplicate serialization was removed.
- Playlist/SAF publication now lists the destination folder once per batch instead of once per output.
- Publication no longer performs a provider `sizeOf` query per output; the byte count from the copy is retained for History.
- Nonterminal POSTPROCESSING state changes no longer enqueue redundant persistence before the durable publish-marker save.
- Validation and per-file publication now have explicit UI states.

The remaining stages are retained as required for correctness: FFmpeg merge/transcode, output validation probes, the full copy to MediaStore/SAF, atomic JobStore replacement and fail-closed publication. They are not moved to asynchronous post-completion work.

---

# Historical: VRKA Android 5.1.0 — Release Evidence

The record below is retained as historical evidence for the published v5.1.0 release. It is not the canonical record for v5.1.1.

**Verification date:** 2026-10-31

**Source version:** `5.1.0` (`versionCode` 50100)
**Validation state:** automated tests, release lint, production release build, APK metadata, signing certificate, checksum, and OpenPGP release manifest verification. Physical OnePlus 11 was unavailable for this release; emulator instrumentation and automated test suites passed.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 639 tests in 61 suites; 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 275 warnings, 0 hints; no errors or fatal findings |
| `:app:assembleRelease` | PASS: production-signed release APK built |
| Release signing validation | PASS: verified with production PKCS#12 keystore |
| OpenPGP manifest signature | PASS: verified with pinned 4096-bit RSA release key (`0xD519380BEBA1B9F7`) |

Result: `BUILD SUCCESSFUL`.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, OpenJDK 25.0.3, Java source/target 17. The APK reports compile SDK 37 (minor API level 2), target API 36, minimum API 26, and `arm64-v8a` only.

## APK identity and integrity

- Canonical release asset: `VRKA-Android-v5.1.0.apk`
- Local verified build: `app/build/outputs/apk/release/app-release.apk`
- Size: 157,202,797 bytes
- SHA-256: `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
- Application ID: `com.mvrk.vrka`
- Version: `5.1.0` (`versionCode` 50100)
- Minimum / target / compile SDK: 26 / 36 / 37 (minor API level 2)
- Packaged native ABI: `arm64-v8a`
- Debuggable: false (non-debuggable)

`aapt2 dump badging` confirmed package `com.mvrk.vrka`, `versionCode= 50100`, `versionName=5.1.0`, and application label `VRKA`. `apksigner verify --verbose --print-certs` verified one RSA 4096-bit signer using APK Signature Scheme v2. The production certificate SHA-256 is `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; SHA-1 is `7758980F74A503684BF1A187994E447823D19D7C`. The fingerprints match the established production signing certificate. No signing secrets are recorded.

## Cryptographically Authenticated Release Manifest

- Release manifest: `VRKA-Android-v5.1.0.manifest.json` (862 bytes, metadata revision 3)
  - SHA-256: `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`
- Detached OpenPGP signature: `VRKA-Android-v5.1.0.manifest.json.asc` (870 bytes)
  - SHA-256: `17B3C504EB4352FCB34815975E973AB8AA6AAEBC6FD1852E2719646858B4FB35`
- Exported public key: `VRKA-Release-Public-Key.asc` (1,754 bytes)
  - SHA-256: `7F2A6EF42A4F66289E31C4FEFFF44B7BA294F9D0C696ACE1F20602401641F38A`
  - Key ID: `0xD519380BEBA1B9F7`
  - Fingerprint: `71165A658B3A8612AC2568C6D519380BEBA1B9F7`

---

# Historical: VRKA Android 5.0.2 — Release Evidence

**Verification date:** 2026-10-05

**Source version:** `5.0.2` (`versionCode` 50002)
**Validation state:** automated tests, release lint, production build, APK metadata, signing certificate, and checksum verified. 5.0.2 has not been physically validated.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 373 tests in 34 suites; 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 88 warnings, 1 hint; no errors or fatal findings |
| `:app:assembleRelease` | PASS: production-signed release APK built |
| Release signing validation | PASS |

Result: `BUILD SUCCESSFUL`.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3, Java source/target 17. The APK reports compile SDK 37, target API 36, minimum API 26, and `arm64-v8a` only.

## APK identity and integrity

- Canonical release asset: `VRKA-Android-v5.0.2.apk`
- Local verified build: `app/build/outputs/apk/release/app-release.apk`
