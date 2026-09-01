# VRKA Android

VRKA is an ARM64 Android media downloader with a centralized queue, yt-dlp/FFmpeg execution, MediaStore/SAF publishing, persisted history, and an in-app WebView fallback for pages that require browser execution.

## Requirements

- JDK 17 or newer (the validated toolchain uses the JDK bundled with Android Studio)
- Android SDK Platform 36.1 and an API 36 build-tools installation
- Android device or emulator on API 24 or newer; this build packages only `arm64-v8a`

Set `sdk.dir` in an untracked `local.properties`, then run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## Signed release build

Create a signing-properties file outside the source tree:

```properties
storeFile=C:\absolute\path\to\release-key.p12
storePassword=replace-me
keyAlias=replace-me
keyPassword=replace-me
```

Point `VRKA_SIGNING_PROPERTIES` to that file and build:

```powershell
$env:VRKA_SIGNING_PROPERTIES = 'C:\absolute\path\to\signing.properties'
.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Signing material and `local.properties` are deliberately excluded from source packages.

## Operational notes

- The first run initializes the bundled downloader and may update yt-dlp according to the selected stable/nightly setting.
- Files publish to `Downloads/VRKA` by default, or to a user-selected Storage Access Framework directory.
- DRM-protected media is not supported. Users are responsible for downloads and site terms.
- Third-party components and licenses are listed in `THIRD_PARTY_NOTICES.md`.
