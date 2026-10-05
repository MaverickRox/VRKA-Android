# VRKA Android 5.0.2 — Release Evidence

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
- Size: 155,338,862 bytes
- SHA-256: `136707980C34EA4242AB3B183571BC24C063A3DDB22BDB0A1CF56258345B9D54`
- Application ID: `com.mvrk.vrka`
- Version: `5.0.2` (`versionCode` 50002)
- Minimum / target / compile SDK: 26 / 36 / 37 (API 37.1 configured)
- Packaged native ABI: `arm64-v8a`

`aapt2 dump badging` confirmed package `com.mvrk.vrka`, `versionCode='50002'`, `versionName='5.0.2'`, and application label `VRKA`. `apksigner verify --verbose --print-certs` verified one RSA 4096-bit signer using APK Signature Scheme v2. The production certificate SHA-256 is `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; SHA-1 is `7758980F74A503684BF1A187994E447823D19D7C`. The fingerprints match the established production signing certificate. No signing secrets are recorded.

## Application updater background persistence & retry engineering

In earlier versions, backgrounding the application during an APK self-update could cause connection termination ("Software caused connection abort"), turning transient socket resets into terminal failures that wiped partial progress with no retry or persistent execution path.

Version 5.0.2 establishes WorkManager-owned background persistence:
- **Independent Execution:** Network transfer occurs in a dedicated WorkManager `CoroutineWorker` (`AppUpdateDownloadWorker`) independent of Activity, Compose, ViewModel, or UI lifecycle.
- **Resilient Retry Policy:** Transient network transport exceptions (connection abort, connection reset, socket timeout) and retryable HTTP statuses (408, 425, 429, 500, 502, 503, 504) return `Result.retry()` with exponential backoff (starting at 30 seconds, up to 5 attempts).
- **Permanent Failure Classification:** Metadata errors, invalid hashes, non-canonical APK filenames, security violations, and downgrade attempts fail permanently (`Result.failure()`) and are not retried.
- **Safe Staging:** Downloads write to a private staging file (`.download.tmp`) and are validated against expected size and SHA-256 before atomic promotion to the final target file. Completed, verified APKs survive worker recreation without re-downloading.
- **Update-Ready Flow:** Upon completion, update-ready state is persisted, a system notification is posted with direct intent back into the update flow, and returning to the foreground displays the installation dialog.
- **Unique Work Deduplication:** Enqueuing uses `ExistingWorkPolicy.KEEP` under `VRKA_APP_UPDATE_WORK`, preventing duplicate background update tasks.

## Physical validation boundary

No emulator, ADB, device farm, or physical device was used for 5.0.2. The historical [5.0 physical validation report](PHYSICAL_VALIDATION_REPORT.md) applies only to its separately identified 5.0 APK and is not evidence for this release. See [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

## Historical release artifacts

### 5.0.1 Artifact
- Release tag: `v5.0.1`
- Asset: `VRKA-Android-v5.0.1.apk`
- Size: 155,335,005 bytes
- SHA-256: `75F7B5EDF514724E21B0237E9E66813F88DF0F51E0E1D620702D6B03D177DBCD`
- Version: `5.0.1` (`versionCode` 50001)

### 5.0 Artifact
- Release tag: `v5.0`
- Asset: `VRKA-Android-v5.0.apk`
- Size: 155,334,093 bytes
- SHA-256: `D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`
- Version: `5.0` (`versionCode` 50000)

Historical tags and artifacts remain immutable.

## Current production architecture

Normal and Browser Fallback transfers use yt-dlp. Browser Fallback uses GeckoView and the VRKA media detector to discover candidates, then hands a selected candidate to the standard yt-dlp path; it does not fetch subtitles. FFmpeg/ffprobe handle local processing and validation. Media3 transfer code remains dormant, N_m3u8DL-RE remains gated off, and the retired Puemos component has no active shipped asset or updater route. See [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) and [CHANGELOG.md](CHANGELOG.md).
