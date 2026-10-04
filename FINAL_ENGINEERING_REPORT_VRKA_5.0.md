# VRKA Android 5.0 — Final Release Hardening Report

**Date:** 2026-10-04
**Scope:** local Android release preparation. Nothing was pushed, published, or uploaded.

## A. Changes made

- Custom yt-dlp Add Template and Edit now use the shared compact outlined-button geometry used in Download History. Delete is plain destructive text, uses the existing VRKA destructive token, and is disabled and visually subdued when no template is selected.
- Components & Updates is reduced to the two active update-managed components. Rows show installed version and truthful check/update state; yt-dlp version comes from the initialized youtubedl-android runtime or its persisted installed-version value, not a hard-coded release string.
- The channel control remains Stable/Nightly for yt-dlp, with the compact selection layout checked against v4.5.3 behavior.
- Navigation icons are 24 dp with thicker existing vector strokes. The floating bar uses separate translucent backdrop layers for the capsule and active indicator, computed slot geometry, centered equal insets, pager synchronization, drag tracking, and slot-crossing haptics. Unsupported blur/lens rendering uses translucent fills.
- Current architecture, release, security, compatibility, store-description, and device-validation documents now match the source and actual APK. Prior engineering artifacts remain preserved with historical notices.

## B. Removed

The bundled `puemos.xpi`, active updater/allowlist routes, component UI/status, runtime install/readiness state, and obsolete update tests were removed. Existing installs get migration-only cleanup: cancel the legacy update work, clear retired preference keys, uninstall the old Gecko extension by ID, and remove its copied XPI. These remaining names identify state to delete; they do not register, update, or expose that component.

## C. v4.5.3 comparison

Git history was used to inspect the v4.5.3 Settings and component/update implementation directly. The compact component-row and channel-selector presentation and the 32 dp outlined-action / plain text destructive-action geometry were carried forward. Existing 5.0 channel and updater logic was retained where already correct; update checking remains limited to active components.

## D. Navigation and SpotiFLAC-Mobile reference

The inspected SpotiFLAC-Mobile `main_shell.dart` keeps a dedicated floating bottom bar in a safe-area layout, synchronizes it with its `PageView`, and disables costly blur on low-end devices. VRKA adapted the separate glass surface and page-synchronization ideas to Compose and its existing backdrop renderer. It uses measured slot widths and explicit insets, not Flutter widgets or dependencies. See [REFERENCE_NAVBAR_STUDY.md](REFERENCE_NAVBAR_STUDY.md).

## E. Active production path

Puemos has no active production role. Normal downloads use yt-dlp. Browser Fallback navigates/discovers in GeckoView, stabilizes candidates, probes with yt-dlp, and returns to the standard yt-dlp transfer. FFmpeg is for local processing, probing, validation, and optional H.264 conversion. The Media3/remote-FFmpeg route has no active call site; N_m3u8DLRE remains gated off. Normal-path subtitle functionality is retained; Browser Fallback does not request or publish subtitles. Custom yt-dlp templates remain argument lists and Browser Fallback continues to use the sanitized standard request path.

## F. Automated validation

- `clean testDebugUnitTest lintRelease assembleRelease --offline --no-daemon`: **BUILD SUCCESSFUL**.
- Unit tests: **317 passed**, 30 suites, zero failures/errors/skips.
- Release lint: zero errors and fatal findings; 66 warnings and one hint.
- Debug and release Kotlin/Java compilation and release resource processing completed.
- Both media-detector JavaScript files passed Node syntax checking; detector JSON manifest parsed.
- `apksigner verify --verbose --print-certs`: passed; one production signer, APK Signature Scheme v2 verified.
- No emulator, ADB, or physical device was used.

## G.–K. Final production APK

- **Path:** `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
- **Size:** 155,327,956 bytes
- **SHA-256:** `8FE92CD2968639238D13D376EE8A6FAC8039AD5925CCCAE9781B20275A698ED4`
- **Signing certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; matches the historical production-release certificate and configured local key.
- **Identity:** `com.mvrk.vrka`, versionName `5.0`, versionCode `50000`; min/target SDK 26/36; `arm64-v8a` only.
- **Signature scheme:** v2 verified; v1, v3, v3.1, and v4 report false. This APK is not debug-signed.

The APK contains GeckoView and FFmpeg native libraries for `arm64-v8a`, the VRKA media detector and uBlock assets, and no retired-extension asset. Gradle reported that some upstream native libraries could not be stripped and packaged them intact; packaging and signature checks still passed.

## L.–O. Repository and publication state

At build, branch was `vrka-5.0` and HEAD was `ea30c00aed644c3a068c96b2bf15e1b09b1b8e9c` (`feat(android): release VRKA 5.0`). The annotated `v5.0` tag still targets that commit. No GitHub push, release, or APK upload was performed. F-Droid files were preserved on disk and excluded from release packaging.

## P. Remaining validation

The final APK has not been physically tested. The maintainer's previous OnePlus 11 site results apply to an earlier build, not this artifact. Manual OnePlus 11 validation is the remaining release-preparation step; see [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md). Automated evidence and the fresh hash are recorded in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) and [ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt](ARTIFACT_MANIFEST_VRKA_5.0_2026-10-04.txt).
