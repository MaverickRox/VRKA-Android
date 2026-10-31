# VRKA Android 5.1.0 — Configured Android Compatibility

This file records the current Gradle build configuration. It does not claim full physical device validation.

| Setting | Value from pp/build.gradle.kts |
| --- | --- |
| Application ID | com.mvrk.vrka |
| ersionName / ersionCode | 5.1.0 / 50100 |
| Minimum API | 26 (Android 8.0) |
| Target API | 36 (Android 16) |
| Compile SDK | API 37, minor API level 2 |
| Native ABI filter | rm64-v8a only |
| GeckoView | 159.0.20261022150525 (geckoview-arm64-v8a) |
| Java source / target | 17 |

The project does not configure rmeabi-v7a or x86_64 APK support. The 5.1.0 artifact metadata and packaged ABI are recorded in RELEASE_EVIDENCE.md after build verification.

## Platform behavior to validate on-device

The source targets Android API 36 and declares API 26 as its minimum. The 5.0 release had a separate physical validation report; 5.1.0 was validated using automated test suites, release lint, production release packaging, and emulator-based testing. Physical OnePlus 11 hardware was unavailable for this release.

## Release artifact checks

The final APK check covers package/version metadata, native library ABI directories, and Android signature verification. Those artifact checks confirm packaging and signing; they do not prove runtime compatibility or a complete inventory of native behavior.