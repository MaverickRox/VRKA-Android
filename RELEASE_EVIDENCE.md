# VRKA Android 5.0.1 — Release Evidence

**Verification date:** 2026-10-05

**Source version:** `5.0.1` (`versionCode` 50001)
**Validation state:** automated tests, release lint, production build, APK metadata, signing certificate, and checksum verified. 5.0.1 has not been physically validated.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 364 tests in 33 suites; 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 68 warnings, 1 hint; no errors or fatal findings |
| `:app:assembleRelease` | PASS: production-signed release APK built |
| Release signing validation | PASS |

Command: `.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --offline --no-daemon`

Result: `BUILD SUCCESSFUL`.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3, Java source/target 17. The APK reports compile SDK 37, target API 36, minimum API 26, and `arm64-v8a` only.

## APK identity and integrity

- Canonical release asset: `VRKA-Android-v5.0.1.apk`
- Local verified build: `app/build/outputs/apk/release/VRKA-Android-v5.0.1.apk`
- Size: 155,335,005 bytes
- SHA-256: `75F7B5EDF514724E21B0237E9E66813F88DF0F51E0E1D620702D6B03D177DBCD`
- SHA-1: `5CE6D1529004EB7AE8F83F356FA0EB6B0D2464C0`
- Application ID: `com.mvrk.vrka`
- Version: `5.0.1` (`versionCode` 50001)
- Minimum / target / compile SDK: 26 / 36 / 37 (API 37.1 configured)
- Packaged native ABI: `arm64-v8a`

`aapt dump badging` confirmed package, version, SDK and target SDK. `apksigner verify --verbose --print-certs` verified one RSA 4096-bit signer using APK Signature Scheme v2. The production certificate SHA-256 is `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; SHA-1 is `7758980F74A503684BF1A187994E447823D19D7C`. The fingerprints match the established production signing certificate. No signing secrets are recorded.

## Application updater regression

The supported 4.5.3 app accepted `v5.0` as semantic version `5.0.0`, but its APK-name matcher required three numeric components. The published `VRKA-Android-v5.0.apk` therefore did not match. When no APK asset was found, the old check classified the missing release as “up to date.” Version 5.0.1 restores compatibility with a canonical three-part tag and exact asset name, and invalid release metadata now fails closed.

The updater regression tests cover numeric version ordering for 4.5.3, 5.0 and 5.0.1; canonical tags and exact APK names; malformed, duplicate, draft and prerelease metadata; missing APK assets; manual checks and the 24-hour automatic gate; update state classification; HTTPS, approved hosts, user-info and ports; redirect targets and hop limits; download target validation; install package/version/version-code checks; downgrade rejection; and signer-set validation.

## Physical validation boundary

No emulator, ADB, device farm, or physical device was used for 5.0.1. The historical [5.0 physical validation report](PHYSICAL_VALIDATION_REPORT.md) applies only to its separately identified 5.0 APK and is not evidence for this release. See [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

## Historical 5.0 artifact

- Release tag: `v5.0`; annotated tag object `0b186f155e772cce5124ee9ae8ca31bc1a051b9b`, targeting commit `40aeaff7d5a2d9913fcdb2d76fbf8b33ab2bbc96`.
- Asset: `VRKA-Android-v5.0.apk`; SHA-256 `D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`.
- Version: `5.0` (`versionCode` 50000); size 155,334,093 bytes.
- Its historical engineering record reported 348 passing unit tests in 33 suites and 67 lint warnings plus one hint. Its physical inspection applies only to the 5.0 APK.
- The 5.0 app updater's release-name compatibility defect is corrected in 5.0.1.

The `v5.0` tag was inspected and left unchanged.

## Branch audit before 5.0.1 synchronization

The common base was `596d9cd0e9554311cce7b1465bdae36141de0795`. Before release work, `origin/main` was `3e00b2d4a7cfdd26f60df8f3c82212ea72ab85e7`; `origin/vrka-5.0` and `v5.0` targeted `40aeaff7d5a2d9913fcdb2d76fbf8b33ab2bbc96`. Relative to main, `vrka-5.0` had six unique commits and main had three.

The six `vrka-5.0`-only commits had no cherry-pick equivalents on main:

| Commit | Finding |
| --- | --- |
| `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c` | Introduced the final 5.0 production implementation; the actual UI, download, queue, browser discovery, media processing, and persistence changes were absent from main. |
| `05b207136efc4975abb1e98c05a77842e76d26be` | Production security hardening retained in the final 5.0 tree. |
| `40707aa185fe5f758f4268e2e3e47047d864a029` | Production correction and rollback cleanup; the resulting tree does not route the reverted experimental transfer paths. |
| `51c1bce9c9e52039fbf9e95e3bab22774aab8ba5` | Final UI and update corrections. |
| `61ab384848614593387230e33ff64c4f0402dc8e` | Final dynamic yt-dlp version and navigation work. |
| `40aeaff7d5a2d9913fcdb2d76fbf8b33ab2bbc96` | Final 5.0 release documentation and tag target. |

Main's three unique commits add F-Droid Maven-local/`mozconfig` integration, Android store metadata, and documentation synchronized with 5.0. They are preserved by a history-preserving merge; no release tag is moved and no force push is used.

## Current production architecture

Normal and Browser Fallback transfers use yt-dlp. Browser Fallback uses GeckoView and the VRKA media detector to discover candidates, then hands a selected candidate to the standard yt-dlp path; it does not fetch subtitles. FFmpeg/ffprobe handle local processing and validation. Media3 transfer code remains dormant, N_m3u8DL-RE remains gated off, and the retired Puemos component has no active shipped asset or updater route. See [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) and [CHANGELOG.md](CHANGELOG.md).
