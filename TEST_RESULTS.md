# VRKA Android 5.0.2 — Test Results

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
