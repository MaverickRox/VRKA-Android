# VRKA Android 5.0.2 — Configured Android Compatibility

This file records the current Gradle build configuration. It does not claim device validation.

| Setting | Value from `app/build.gradle.kts` |
| --- | --- |
| Application ID | `com.mvrk.vrka` |
| `versionName` / `versionCode` | `5.0.2` / `50002` |
| Minimum API | 26 (Android 8.0) |
| Target API | 36 (Android 16) |
| Compile SDK | API 37, minor API level 1 |
| Native ABI filter | `arm64-v8a` only |
| GeckoView | `156.0.20260921121718` (`geckoview-arm64-v8a`) |
| Java source / target | 17 |

The project does not configure `armeabi-v7a` or `x86_64` APK support. The application has not been validated on every Android release between its minimum and target APIs. The 5.0.2 artifact metadata and packaged ABI are recorded in `RELEASE_EVIDENCE.md` after build verification.

## Platform behavior to validate on-device

The source targets Android API 36 and declares API 26 as its minimum. The 5.0 release had a separate physical validation report; 5.0.2 has not been physically validated. No emulator or ADB automation is used for this release work.

## Release artifact checks

The final APK check covers package/version metadata, native library ABI directories, and Android signature verification. Those artifact checks confirm packaging and signing; they do not prove runtime compatibility or a complete inventory of native behavior.
