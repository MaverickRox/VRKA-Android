# VRKA Android 5.0.1 — Test Results

Date: 2026-10-05

## Automated checks

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | PASS: 364 tests, 33 suites, 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 68 warnings, 1 hint, no Error/Fatal issues |
| `:app:assembleRelease` | PASS: production-signed APK |

Combined command: `.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --offline --no-daemon`. Gradle reported `BUILD SUCCESSFUL`.

The added updater coverage checks semantic-version ordering, canonical release tags and APK assets, malformed/draft/prerelease data, manual and automatic check behavior, update state selection, secure download URLs and redirects, install metadata, non-downgrade version codes, and signer fingerprints. Existing repository tests also ran in the same 33 suites.

## Device boundary

No emulator, ADB, device farm, or physical-device testing was performed for 5.0.1. The separate [5.0 physical validation report](PHYSICAL_VALIDATION_REPORT.md) is historical and applies only to that release APK.

## APK

Canonical file: `VRKA-Android-v5.0.1.apk`. Package `com.mvrk.vrka`, version `5.0.1` (`50001`), ABI `arm64-v8a`. Size: 155,335,005 bytes. SHA-256: `75F7B5EDF514724E21B0237E9E66813F88DF0F51E0E1D620702D6B03D177DBCD`. `apksigner` verified the production RSA 4096-bit certificate using APK Signature Scheme v2. Full metadata is in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## Historical 5.0 checks

The 5.0 release record reported 348 tests across 33 suites, 67 lint warnings plus one hint, and a separate OnePlus 11 inspection of the 5.0 APK. Those results do not apply to 5.0.1.
