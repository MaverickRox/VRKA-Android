# VRKA Android 5.1.1 — Test Results

Date: 2026-11-08

## Automated checks

| Check | Command | Result |
|---|---|---|
| Full JVM unit tests | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 732 tests, 72 suites, 0 failures, 0 errors, 0 skipped |
| Debug Kotlin compilation | `./gradlew compileDebugKotlin` | PASS |
| Android test-source compilation | `./gradlew compileDebugAndroidTestKotlin` | PASS; instrumentation was not executed |
| Release-variant compilation | `./gradlew compileReleaseKotlin` | PASS without production signing credentials |
| Debug APK | `./gradlew assembleDebug` | PASS |
| Development release APK | `./gradlew assembleDevRelease` | PASS; R8 minification and debug-key signing |
| Lint | `./gradlew lintDebug` | PASS: 277 warnings, 3 hints, 0 errors/fatal; no new lint ID from the v5.1.1 code |
| CI workflow | PyYAML parse of `.github/workflows/ci.yml` | PASS; not executed on GitHub |

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
