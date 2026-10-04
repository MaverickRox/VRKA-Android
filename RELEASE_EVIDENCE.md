# VRKA Android 5.0 — Official Release Evidence

**Build date:** 2026-10-04
**Status:** production-signed release candidate; physically inspected and approved by maintainer on OnePlus 11.

## Build and checks

- Clean command: `.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease --offline --no-daemon`
- Result: `BUILD SUCCESSFUL`.
- Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3; Java source/target 17.
- Unit tests: 348 tests across 33 suites; 0 failures, 0 errors, 0 skipped.
- Release lint: 67 warnings, 1 hint, 0 errors, 0 fatal findings.
- Resource processing, Debug/Release Kotlin and Java compilation, release minification, and release packaging completed successfully.
- JavaScript syntax checks passed for the media detector's `background.js` and `content.js`; its `manifest.json` parsed successfully.
- The release APK's optimized raw yt-dlp payload was inspected; its embedded `yt_dlp/version.py` reports `2026.08.19`. The installed-version reader parses `yt_dlp/version.py` from the active installed or bundled zipapp instead of hard-coding this bundled version.
- No emulator, ADB, physical device, or device automation was used.

Gradle warned that some upstream native libraries could not be stripped; it packaged them intact. Release lint reported warnings and one hint, with no errors or fatal findings.

## APK identity

- Path: `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
- Size: 155,334,093 bytes
- SHA-256: `D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`
- Application ID: `com.mvrk.vrka`
- Version: `5.0` (`versionCode` 50000)
- Minimum / target / compile SDK: 26 / 36 / 37 (API 37.1 configured)
- Packaged native ABI: `arm64-v8a`
- The APK contains GeckoView, FFmpeg, the VRKA media-detector files, uBlock XPI, and the app-bundled yt-dlp 2026.08.19 source. No Puemos asset is packaged.

## Signing verification

`apksigner verify --verbose --print-certs` verified one RSA 4096-bit signer using APK Signature Scheme v2. Certificate SHA-256:

`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

The v1, v3, v3.1, and v4 scheme checks report false; v2 reports true. The certificate fingerprint matches the project's historical production-release fingerprint and configured local key. No key passwords or signing-property contents are recorded here.

## Source behavior and validation boundary

Components & Updates lists only yt-dlp and uBlock Origin. `InstalledComponentVersionReader` reads the active installed yt-dlp zipapp's `yt_dlp/version.py` or the bundled raw resource `R.raw.ytdlp`; it initializes the bundled runtime on IO and has persisted-version fallbacks. Installed and remote checked versions remain strictly separated. The release payload reports `2026.08.19`, and this value is not hard-coded into the installed-version UI.

The 5.0 single-flight updater protection has connect/read timeouts, a 64 KiB response cap, a cancellable continuation that disconnects the active connection, and an eight-second per-component deadline. Batch cancellation propagates; every success, error, timeout, or cancellation settles loading state and releases the single-flight guard. A failed check is not shown as up to date.

The navbar uses equal navigation slot geometry across all 4 destinations (Queue is never smaller than Download or Settings). The active pill is positioned with layout offsets so the backdrop sampler captures the exact pixels directly underneath the selected tab. It applies a live AGSL lens transformation with 1.35x magnification, 20-dp refraction height, 14-dp displacement, spherical depth, and chromatic aberration dispersion, unified with the glass material family via subtle sheen and specular rim highlight (no dark opaque/translucent bubble).

Normal and Browser Fallback transfers use yt-dlp; FFmpeg handles local processing. Puemos has no active production UI, extension registration, or update path. Historical one-time cleanup removes its legacy state from upgraded installs.

The maintainer physically inspected this final production-signed APK (`D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`) on the OnePlus 11 and confirmed that the updater/version implementation and product state are accepted for the 5.0 release. See [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md).

## Local repository state

- Branch: `vrka-5.0`
- Source base HEAD when verification started: `51c1bce9c9e52039fbf9e95e3bab22774aab8ba5`; the APK was built from that checkout plus the correction source changes recorded with this evidence.
- The annotated local `v5.0` tag object remains `f414305be1087aeba28cd2947321cae280774d01`, peeled to `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c`.
- A local correction commit records the verified source and evidence after the build. The tag is unchanged.
- Nothing was pushed, uploaded, or published as a GitHub Release. F-Droid files and unrelated pre-existing work remain untouched and unstaged.


See [ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt](ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt) for the compact artifact record.
