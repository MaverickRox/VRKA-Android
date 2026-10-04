# VRKA Android 5.0 — Test Results

Date: 2026-10-04

## Automated checks

| Check | Result |
|---|---|
| .\gradlew.bat clean --no-daemon | PASS |
| .\gradlew.bat testDebugUnitTest --no-daemon | PASS: 348 tests, 33 suites, 0 failures, 0 errors, 0 skipped |
| .\gradlew.bat lintRelease --no-daemon | PASS: 67 warnings, 1 hint, no Error/Fatal issues |
| JavaScript syntax check | PASS: 2 app asset scripts |
| Media-detector manifest JSON | PASS |
| .\gradlew.bat assembleRelease --no-daemon | PASS |

The suite covers dynamic yt-dlp installed-version extraction, component update state machines, batch check concurrency and timeout/cancellation handling, equal slot-based navbar indicator geometry and liquid-glass optical parameters, custom-command and Browser Fallback routing/isolation, private candidate handling, template deletion, component/version status presentation, H.264 persistence, header validation, candidate ranking, output validation, and subtitle boundaries.

Remaining lint findings are non-fatal baseline warnings and one hint recorded in the Gradle report.

## Device boundary

The maintainer has physically validated and approved the final production-signed release APK on the OnePlus 11. No automated emulator or ADB test framework was used during engineering runs.

## APK

Clean release output: app/build/outputs/apk/release/app-release.apk. Package com.mvrk.vrka, version 5.0 (50000), ABI arm64-v8a. Size: 155,334,093 bytes. SHA-256: D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487. Production-signed with RSA 4096-bit key (v2 scheme).
