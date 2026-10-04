# VRKA Android 5.0 — Final Local Release Evidence

**Build date:** 2026-10-04
**Status:** production-signed local correction candidate; unpublished; awaiting maintainer physical-device validation.

## Build and checks

- Clean command: `.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease --offline --no-daemon`
- Result: `BUILD SUCCESSFUL`.
- Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3; Java source/target 17.
- Unit tests: 329 tests across 33 suites; 0 failures, 0 errors, 0 skipped.
- Release lint: 67 warnings, 1 hint, 0 errors, 0 fatal findings.
- Resource processing, Debug/Release Kotlin and Java compilation, release minification, and release packaging completed successfully.
- JavaScript syntax checks passed for the media detector's `background.js` and `content.js`; its `manifest.json` parsed successfully.
- The release APK's optimized raw yt-dlp payload was inspected; its embedded `yt_dlp/version.py` reports `2026.08.19`. The installed-version reader parses `yt_dlp/version.py` from the active installed zipapp instead of hard-coding this bundled version.
- No emulator, ADB, physical device, or device automation was used.

Gradle warned that some upstream native libraries could not be stripped; it packaged them intact. Release lint reported warnings and one hint, with no errors or fatal findings.

## APK identity

- Path: `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
- Size: 155,334,511 bytes
- SHA-256: `A22F62500D3268E0562CDA586BEB2BFC9DE63327B1FEAD91CE466625B7614A06`
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

Components & Updates lists only yt-dlp and uBlock Origin. `InstalledComponentVersionReader` reads the active installed yt-dlp zipapp's `yt_dlp/version.py`; it can initialize the bundled runtime first and has persisted-version fallbacks. Installed and remote checked versions remain separate. The release payload reports `2026.08.19`, and this value is not hard-coded into the installed-version UI.

The previous check could hang because blocking `HttpURLConnection` response-body reads were wrapped in coroutine timeouts that could not interrupt the socket. An unbounded body and per-read timeout that reset as bytes arrived allowed a slow-drip response to keep the coroutine pending. The corrected request has connect/read timeouts, a 64 KiB response cap, a cancellable continuation that disconnects the active connection, and an eight-second per-component deadline. Batch cancellation propagates; every success, error, timeout, or cancellation settles loading state and releases the single-flight guard. A failed check is not shown as up to date.

The navbar's active pill samples the live pager backdrop and applies an AGSL coordinate magnification/refraction transform on API 33+; this is actual backdrop sampling and pixel-coordinate transformation, not just blur or alpha. The outer capsule and active pill are separate layers. See [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) for exact rendering and geometry details. Perceived strength, scrolling stability, and transition smoothness still require the maintainer's physical-device inspection.

Normal and Browser Fallback transfers use yt-dlp; FFmpeg handles local processing. Puemos has no active production UI, extension registration, or update path. Historical one-time cleanup removes its legacy state from upgraded installs.

The new APK has not been physically tested. The maintainer will test this exact APK manually after the correction pass. See [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md).

## Local repository state

- Branch: `vrka-5.0`
- Source base HEAD when verification started: `40707aa185fe5f758f4268e2e3e47047d864a029`; the APK was built from that checkout plus the correction source changes recorded with this evidence.
- The annotated local `v5.0` tag object remains `f414305be1087aeba28cd2947321cae280774d01`, peeled to `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c`.
- A local correction commit records the verified source and evidence after the build. The tag is unchanged.
- Nothing was pushed, uploaded, or published as a GitHub Release. F-Droid files and unrelated pre-existing work remain untouched and unstaged.

See [ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt](ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt) for the compact artifact record.
