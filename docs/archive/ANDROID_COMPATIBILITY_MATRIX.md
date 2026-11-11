# VRKA Android 5.1.1 — Configured Android Compatibility

This file records the current Gradle build configuration for the local v5.1.1 candidate. It does not claim physical device validation.

| Setting | Value from app/build.gradle.kts |
| --- | --- |
| Application ID | com.mvrk.vrka |
| VersionName / VersionCode | 5.1.1 / 50101 |
| Minimum API | 26 (Android 8.0) |
| Target API | 36 (Android 16) |
| Compile SDK | API 37, minor API level 2 |
| Native ABI filter | arm64-v8a only |
| GeckoView | 159.0.20261022150525 (geckoview-arm64-v8a) |
| Java source / target | 17 |
| Interface Locales | 14 languages + System Default (full RTL support) |

The project does not configure armeabi-v7a or x86_64 APK support. The local 5.1.1 development artifact metadata and packaged ABI are recorded in RELEASE_EVIDENCE.md. It is debug-signed; no production v5.1.1 artifact exists.

## Destination behavior by API

- **API 29+:** Sizes and downloads publish through scoped `MediaStore.Downloads` under `Downloads/VRKA`; no broad storage permission is requested.
- **API 26–28:** A user-selected SAF tree with persisted write permission is mandatory before a download starts. This avoids the legacy `MediaStore.Files` path that would require `WRITE_EXTERNAL_STORAGE`.
- **Revoked permission:** The job returns to `WAITING_FOR_USER` and re-opens the folder prompt.

## Platform behavior to validate on-device

The source targets Android API 36 and declares API 26 as its minimum. No compatible ARM64 physical device or emulator was available for v5.1.1; the installed AVDs are x86_64 and cannot run the arm64-only APK. API 26–28 runtime behavior, scoped-storage publication, revoked-permission recovery, and TalkBack progress announcements remain unverified on hardware.

## Release artifact checks

The local check covers package/version metadata, native library ABI directories, debug signing, and build success. Those checks confirm packaging, not runtime compatibility or a complete inventory of native behavior.
