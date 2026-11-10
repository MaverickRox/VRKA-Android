# Device Validation & Subsystem Verification Record — VRKA Android

## v5.1.3 release validation — API 36 emulator

Validation date: 2026-11-10. This section records the v5.1.3 release checks and leaves the
historical v5.1.2 and v5.1.1 records below unchanged.

- **Environment:** Android API 36 emulator (`sdk_gphone64_x86_64`, ABI `x86_64`). No physical
  device was available for v5.1.3 validation.
- **Official v5.1.2 starting install:** SHA-256
  `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a`; production certificate
  matched the recorded VRKA RSA-4096 identity. Installed before the upgrade test.
- **In-place upgrade:** `adb install -r` installed the v5.1.3 APK over package
  `com.mvrk.vrka`. Android reported version `5.1.3` / code `50103`; `firstInstallTime` remained
  unchanged. The app launched after installation and after a force-stop/restart.
- **Data visible after upgrade and restart:** the two existing failed History entries remained
  visible; Queue remained empty; H.264 Compatibility Mode remained enabled. No successful
  provider output existed in the starting installation, so no published output record could be
  checked on this device.
- **Production package:** package `com.mvrk.vrka`, version `5.1.3` / code `50103`, min SDK 26,
  target SDK 36, ABI `arm64-v8a`. APK Signature Scheme v2 and the expected RSA-4096 signing
  certificate verified. The release host built and signed the APK; the emulator installed it.
- **Automated tests:** 828 JVM tests in 78 suites passed with no failures, errors, or skips.
  `lintDebug` reported 0 errors. All 12 API 36 instrumentation tests passed: 10 SAF tests against
  a controlled `DocumentsContract` provider and 2 MediaStore tests against the emulator provider.
- **Emulator boundary:** the emulator is x86_64, while the production APK contains arm64-v8a
  native libraries. A download attempt under v5.1.2 reported an ELF ABI mismatch, so a production
  media transfer was not verified. This is not reported as a physical-device test.
- **Provider boundary:** controlled SAF tests exercise the public provider contract. They do not
  prove behavior for every OEM DocumentsProvider, Android release, or physical device.

---

# Historical: VRKA Android 5.1.2 — Device Validation & Subsystem Verification Record

## 1. Authoritative Validation Summary

This document serves as the authoritative record for validation, emulator boundaries, and subsystem verification for VRKA Android 5.1.2 and its preceding releases.

### VRKA Android 5.1.2 Validation Scope:
- **Maintainer-Reported Physical-Device Upgrade Validation**:
  - **Existing installed application**: VRKA Android v5.1.1.
  - **Upgrade target**: VRKA Android v5.1.2 (`VRKA-Android-v5.1.2.apk`, `versionCode` 50102).
  - **Result**: Maintainer successfully updated an existing v5.1.1 installation to v5.1.2 on a physical Android phone.
  - **Validation date**: November 9, 2026.
  - **Validation category**: Maintainer-reported physical-device validation.
  - **Distinction of verified scope**: This observation confirms that the v5.1.1-to-v5.1.2 upgrade path and package installation succeed on at least one real Android device without packaging or signature conflicts. It confirms successful installation/update on that device. It does not establish first-launch verification, full data preservation across complex queues, or in-app updater download execution on that specific device, and must not be generalized to every supported Android version or hardware model.
- **Automated Verification**: JVM test suite (747 tests across 73 suites at release time; 784 tests across 73 suites on current `main`) passing with 0 failures, 0 errors, and 0 skipped. The current suite includes 33 publication recovery tests across production-orchestration, injected-dependency production methods, and isolated serialization checks, plus in-app updater v5.1.1-to-v5.1.2 cryptographic validation.
- **Deterministic Publication Failure-Injection Tests**: 33 recovery tests cover crash, interruption, partial copy, terminal persistence failure, fresh-runtime reconciliation, concurrent persistence, strict journal quarantine/ownership, cleanup retry, and corrupted journal snapshot handling. These are injected-dependency tests, not all full production crash simulations.
- **Emulator Integration (executed)**: An API 36 headless emulator (`Medium_Phone_API_36.0`) booted successfully. Two real MediaStore instrumentation tests passed: `PublicationStorageIntegrationTest.mediaStorePublicationIsFinalizedAndVerifiedByTheRealProvider` and `realMediaStorePendingRowIsRecoveredIntoDurableHistory`. SAF tree publication/recovery and the remaining instrumentation suite were **not** executed on the emulator.
- **Build & Artifact Validation**: The published v5.1.2 APK remains verified as documented below: production-signed `VRKA-Android-v5.1.2.apk`, APK Signature Scheme v2, 4096-bit RSA certificate (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`), zipalign alignment, and non-debuggable badging. This post-release remediation did not rebuild or re-sign it. Current `main` verification covers debug Kotlin compilation, release-variant Kotlin compilation, Android test-source compilation, lint (0 errors), and the debug APK built/installed by the emulator instrumentation task.
- **Unverified Platform Boundaries**: SAF tree publication and recovery require an interactive document-picker grant and were not executed on an emulator or physical device in this remediation. Physical API 26–28 SAF execution, live Browser Fallback end-to-end transfers, multi-OEM provider behavior, and physical-device automated instrumentation remain explicit documented boundaries. The controlled-provider JVM suite does not constitute real SAF evidence.

---

# Historical: VRKA Android 5.1.1 — Hardware & Subsystem Record


## 1. Authoritative Validation Summary

This document serves as the single authoritative record for physical-device validation, emulator boundaries, and subsystem verification for VRKA Android 5.1.1. It reconciles earlier contradictory draft records.

Subsystem testing is classified into seven rigorous categories:
1. Automated unit tests
2. Build & compilation validation
3. Manual OnePlus 11 physical-device validation
4. Browser Fallback page/playback observation
5. Actual completed Browser Fallback transfer
6. Android instrumentation execution
7. Unverified behavior & platform boundaries

---

## 2. Hardware Environment

- **Device**: OnePlus 11 5G (`CPH2447`)
- **Architecture**: `arm64-v8a` (64-bit ARM)
- **Operating System**: Android 16 (Developer Preview / Early Build)
- **API Level**: SDK 36
- **Connection Method**: ADB over USB (Device ID `897f6ce5`)
- **Package Tested**: Production-signed `VRKA-Android-v5.1.1.apk` (VersionCode `50101`, VersionName `5.1.1`)

---

## 3. Subsystem Classification & Verification Evidence

### 3.1 Automated Unit Tests
- **Status**: VERIFIED
- **Evidence**: Passing JVM unit test suite (732 tests across 72 suites; zero failures, zero errors, zero skipped) executed in an isolated temporary JVM environment with no access to production credentials. Full coverage across format parsing, size estimation, URL sanitization, JobStore persistence, and updater cryptography.

### 3.2 Build & Compilation Validation
- **Status**: VERIFIED
- **Evidence**:
  - `compileDebugKotlin` and `compileReleaseKotlin` passed.
  - `compileDebugAndroidTestKotlin` passed.
  - `assembleDebug` and `assembleDevRelease` passed.
  - `lintDebug` passed with 0 errors/fatal (277 warnings, 3 hints).
  - GitHub Actions CI Run ID `42517854024` on commit `ecc3a49` passed in 8m14s (`testDebugUnitTest`, `lintDebug`, `compileDebugAndroidTestKotlin`, `assembleDebug`, `assembleDevRelease`).

### 3.3 Manual OnePlus 11 Physical-Device Validation
- **Status**: MANUALLY VERIFIED ON HARDWARE
- **Evidence**:
  1. **Production Stream Install**: Installed `VRKA-Android-v5.1.1.apk` via `adb install -r` cleanly over existing user installation without data wipe. Process spawned successfully (PID 14291) with zero startup crashes or exceptions.
  2. **Native YouTube Size Estimation & Confirmation (`https://youtu.be/qQM5i62f5zQ`)**:
     - Preflight resolution chips rendered synchronized approximate sizes: Best available (`~54.75 MB`), 2160p 4K (`~54.75 MB`), 1440p 2K (`~27.45 MB`).
     - Confirmation modal displayed synchronized authoritative estimate (2160p at `54.75 MB`).
  3. **Completed YouTube Transfer & History Accounting**:
     - Completed download to `Downloads/VRKA` at `52.2 MiB`, matching the expected final container size.
     - History correctly recorded the completed item with functional Open, Share, and Delete actions.
  4. **Multi-Download Creative Commons Video Testing**:
     - Successfully downloaded *Tears of Steel* (CC-BY 3.0 Blender Foundation) at 800p (`194.4 MiB`).
     - Successfully downloaded *Sintel* (CC-BY 3.0 Blender Foundation) at 872p (`179.8 MiB`).
  5. **Separate Audio-Mode Extraction**:
     - Downloaded *Big Buck Bunny* in Audio mode (MP3, 320 kbps), successfully publishing the `25.7 MiB` MP3 file to `Downloads/VRKA`.
  6. **Queue & Multi-Task Sequential Concurrency**:
     - Verified multi-job queue operation where *Tears of Steel* transferred actively at `8.72 MiB/s` (`148.0 MiB / ~171.9 MiB`) while subsequent jobs remained properly queued.
  7. **MissAV Stream Classification (Neutral Media Stage)**:
     - Under live testing with ambiguous/muxed media streams where earlier versions incorrectly reported "Downloading audio", the active transfer correctly displayed the neutral `"Downloading media"` stage.
  8. **Clean Full-Resolution Screenshots**:
     - High-resolution physical screenshots (1440x3216, AMOLED black, keyboard hidden) captured and archived in `docs/screenshots/v5.1.1/`.

### 3.4 Browser Fallback Page & Playback Observation
- **Status**: OBSERVED ON HARDWARE (PASSIVE DISCOVERY ONLY)
- **Evidence**:
  - Navigated to the Shaka Player adaptive streaming demonstration page (`https://shaka-player-demo.appspot.com/demo/`) within GeckoView private browsing.
  - Video playback ("Big Buck Bunny: the Dark Truths" HLS) was initiated.
  - First-party `VRKA Media Detector` WebExtension intercepted 11 media stream candidates with active `uBlock Origin` content filtering.

### 3.5 Actual Completed Browser Fallback Transfer
- **Status**: UNVERIFIED ON PHYSICAL HARDWARE
- **Boundary**:
  - While passive candidate discovery and DOM element interception were verified on device, a completed end-to-end media transfer through the Browser Fallback engine (transferring a protected stream to final published storage with exact final-size verification) was NOT executed on the physical device.
  - Candidate parsing and engine routing are covered by automated unit tests (`MediaEngineRouterTest`, `FallbackCandidateEstimateTest`), but live-site transfer remains an unverified platform boundary.

### 3.6 Android Instrumentation Execution
- **Status**: NOT EXECUTED
- **Boundary**:
  - Test sources compiled successfully via `./gradlew compileDebugAndroidTestKotlin`.
  - However, Android instrumentation tests (including UI automated tests and TalkBack accessibility assertions) were **not executed** on the connected device or on an emulator.

### 3.7 Unverified Behavior & Platform Boundaries
The following areas remain explicitly unverified on physical hardware:
1. **API 26–28 SAF Destination Handling**: The OnePlus 11 operates on Android 16 (API 36), which uses MediaStore `Downloads/VRKA` (API 29+). Android 8.0–9.0 mandatory SAF folder picker and legacy permissions were validated architecturally and in unit tests (`DestinationPolicyTest`), but not on a physical API 26–28 device.
2. **In-Place v5.1.0-to-v5.1.1 In-App Upgrade**: The in-app updater download and signature verification logic was verified hermetically via unit tests (`ReleasePipelineEndToEndTest`), but an in-place upgrade executed through the live UI updater from an installed v5.1.0 client has not been exercised on hardware.
3. **TalkBack Accessibility Auditing**: Progress bar accessibility semantics (range, value, labels) were verified via unit tests and Compose inspection, but a physical TalkBack screen-reader audio session was not audited.
4. **Hardware Storage Finalization Timings**: Finalization stage timing logs were added, but formal micro-benchmark timings across slow flash media/USB-OTG remain pending.

---

## 4. Historical Reference: VRKA Android 5.1.0

The 5.1.0 release APK was verified through automated unit tests (601 tests across 58 test suites, 0 failures), release lint (275 warnings, 0 errors), R8 minified release compilation, APK metadata and signature verification via `aapt2` and `apksigner`, and emulator-based UI verification. Physical OnePlus 11 testing was unavailable for the final 5.1.0 publication pass. The historical [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md) documents the earlier 5.0 APK and does not represent physical hardware validation for 5.1.0.
