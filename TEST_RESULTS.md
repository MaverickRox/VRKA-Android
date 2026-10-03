# VRKA Android 5.0 — Test Results

Date: 2026-10-03

## Automated checks

| Check | Result |
|---|---|
| .\gradlew.bat clean --no-daemon | PASS |
| .\gradlew.bat testDebugUnitTest --no-daemon | PASS: 325 tests, 30 suites, 0 failures, 0 errors, 0 skipped |
| .\gradlew.bat lintDevRelease --no-daemon | PASS: 76 warnings, 1 hint, no Error/Fatal issues |
| JavaScript syntax check | PASS: 2 app asset scripts |
| Media-detector manifest JSON | PASS |
| .\gradlew.bat assembleDevRelease --no-daemon | PASS |

The suite covers custom-command and Browser Fallback routing/isolation, private candidate handling, template deletion, component/version status presentation, H.264 persistence, header validation, candidate ranking, output validation, and subtitle boundaries.

Kotlin compilation emitted non-fatal deprecation and unnecessary-safe-call warnings. Remaining lint findings are recorded in the Gradle report.

## Device boundary

No emulator, ADB, physical device, or device automation was used in this engineering run. The maintainer separately reports physical OnePlus 11 Browser Fallback validation; those tests are not counted here.

## APK

Clean devRelease output: app/build/outputs/apk/devRelease/app-devRelease.apk. Package com.mvrk.vrka, version 5.0 (50000), ABI arm64-v8a. SHA-256: 7F7D986C685537AACE512360C877763421AE4BC05F25B325E793BD9503ACEA88.
