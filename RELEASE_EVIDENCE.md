# VRKA Android 5.0 — Final Local Release Evidence

**Build date:** 2026-10-04
**Status:** production-signed local candidate; unpublished; awaiting maintainer physical-device validation.

## Build and checks

- Clean command: `.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease --offline --no-daemon`
- Result: `BUILD SUCCESSFUL`.
- Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3; Java source/target 17.
- Unit tests: 317 tests across 30 suites; 0 failures, 0 errors, 0 skipped.
- Release lint: 66 warnings, 1 hint, 0 errors, 0 fatal findings.
- Resource processing, Debug/Release Kotlin and Java compilation, release minification, and release packaging completed successfully.
- JavaScript syntax checks passed for the media detector's `background.js` and `content.js` using the bundled Node.js runtime. Its `manifest.json` parsed successfully.
- No emulator, ADB, physical device, or device automation was used.

Gradle emitted a warning that several upstream native libraries could not be stripped; it packaged them intact. This did not fail packaging or APK signature verification.

## APK identity

- Path: `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
- Size: 155,327,956 bytes
- SHA-256: `8FE92CD2968639238D13D376EE8A6FAC8039AD5925CCCAE9781B20275A698ED4`
- Application ID: `com.mvrk.vrka`
- Version: `5.0` (`versionCode` 50000)
- Minimum / target / compile SDK: 26 / 36 / 37 (API 37.1 configured)
- Packaged native ABI: `arm64-v8a`
- Packaged native components include GeckoView (`libmozglue.so`, `libxul.so`) and FFmpeg (`libffmpeg.so`, `libffprobe.so`). The APK contains the VRKA media-detector files and uBlock XPI; no retired-extension asset is packaged.

## Signing verification

`apksigner verify --verbose --print-certs` verified one signer using APK Signature Scheme v2. The signer is RSA 4096-bit with certificate SHA-256:

`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

The v1, v3, v3.1, and v4 scheme checks report false for this APK; v2 reports true. The certificate fingerprint matches the project's historical production-release fingerprint and the configured local key. No key passwords or signing-property contents are recorded here.

## Architecture and validation boundary

Normal and Browser Fallback transfers use yt-dlp. Browser Fallback discovers candidates in GeckoView through the VRKA media detector and returns the selected candidate to the standard yt-dlp path. FFmpeg is used for local processing. Media3/remote-FFmpeg transfer code remains dormant, and the N_m3u8DLRE gate remains off. The retired browser extension has no active registration or update path; source references that remain exist only to remove legacy state on upgrades.

The final APK has not been physically tested. The maintainer's earlier OnePlus 11 report concerns a previous build and is not validation of this APK. See [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md).

## Local repository state

- Branch: `vrka-5.0`
- Base HEAD at build: `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c` (`feat(android): release VRKA 5.0`); the APK was built from the local release-hardening working tree based on that commit.
- The local annotated `v5.0` tag still points to that base commit. A local commit records the verified source and documentation after the build; it does not move the tag.
- No push, GitHub Release, or upload was performed. F-Droid files and the existing !49780 work were left on disk and excluded from release packaging.

See [ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt](ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt) for the compact artifact record and [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) for the source-audited flow.
