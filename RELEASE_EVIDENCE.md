# VRKA Android 5.0 — Final Local Release Evidence

**Build date:** 2026-10-04
**Status:** production-signed local correction candidate; unpublished; awaiting maintainer physical-device validation.

## Build and checks

- Clean command: `.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease --offline --no-daemon`
- Result: `BUILD SUCCESSFUL`.
- Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3; Java source/target 17.
- Unit tests: 319 tests across 30 suites; 0 failures, 0 errors, 0 skipped.
- Release lint: 66 warnings, 1 hint, 0 errors, 0 fatal findings.
- Resource processing, Debug/Release Kotlin and Java compilation, release minification, and release packaging completed successfully.
- JavaScript syntax checks passed for the media detector's `background.js` and `content.js`; its `manifest.json` parsed successfully.
- The packaged `res/raw/ytdlp` entry was byte-for-byte compared with `app/src/main/res/raw/ytdlp`; its embedded `yt_dlp/version.py` reports `2026.08.19`.
- No emulator, ADB, physical device, or device automation was used.

Gradle warned that some upstream native libraries could not be stripped; it packaged them intact. Release lint reported warnings and one hint, with no errors or fatal findings.

## APK identity

- Path: `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
- Size: 155,330,895 bytes
- SHA-256: `FEF0C2F1F33AE9F8E48389B8EB1AF7B70C9630213E328445A374FAA8231EB724`
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

Components & Updates lists only yt-dlp and uBlock Origin. yt-dlp's installed version comes from `YoutubeDL.versionName(context)` after runtime initialization, with the youtubedl-android persisted installed-version data as a fallback. The bundled raw resource is verified as 2026.08.19; that value is not hard-coded into the installed-version UI, so a runtime updated after installation can report its actual version.

Check All Updates uses cancellable IO requests with connection/read timeouts, a total per-component deadline, a bounded metadata body, cancellation-triggered socket disconnect, per-component errors, and batch cleanup. The navbar samples the moving pager backdrop in two layers and applies an AGSL rounded-rectangle magnification/refraction lens on supported Android versions. See [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) for implementation details and the older-platform fallback.

Normal and Browser Fallback transfers use yt-dlp; FFmpeg handles local processing. Puemos has no active production UI, extension registration, or update path. Historical one-time cleanup removes its legacy state from upgraded installs.

The new APK has not been physically tested. The maintainer will test this exact APK manually after the correction pass. See [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md).

## Local repository state

- Branch: `vrka-5.0`
- Source base HEAD when verification started: `05b207136efc4975abb1e98c05a77842e76d26be`; the APK was built from that checkout plus the correction source changes recorded with this evidence.
- The annotated local `v5.0` tag object remains `f414305be1087aeba28cd2947321cae280774d01`, peeled to `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c`.
- A local correction commit records the verified source and evidence after the build. The tag is unchanged.
- Nothing was pushed, uploaded, or published as a GitHub Release. F-Droid files and unrelated pre-existing work remain untouched and unstaged.

See [ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt](ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt) for the compact artifact record.
