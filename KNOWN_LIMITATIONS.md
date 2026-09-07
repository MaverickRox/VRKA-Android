# Known Limitations

> [!NOTE]
> **Historical Baseline Document**: This document records the known limitations identified during the original VRKA Android 1.0.0 release audit. It is preserved for historical traceability and baseline reference. For current v4.0.2 architecture and security policies, see [README.md](README.md) and [SECURITY.md](SECURITY.md).

## Platform

- The release APK supports `arm64-v8a` only.
- Android can stop the application process. VRKA marks an interrupted job as Failed. The user can retry the job.
- Android notification permission controls foreground-download notification visibility.

## Media and sites

- VRKA does not bypass DRM.
- VRKA does not bypass login, payment, CAPTCHA, consent, or access controls.
- A site change can require a newer yt-dlp runtime.
- Browser fallback depends on Mozilla GeckoView runtime and device network connectivity.
- Browser session headers remain in memory. Android process death during handoff requires a job retry.
- Browser fallback cookies and site data are stored in GeckoView's isolated runtime storage. In 4.0.2, users can explicitly purge this data via Settings ("Clear Browser Session") without affecting download history or app preferences.

## Desktop controls not in the Android UI

- Android does not import cookies from a desktop browser profile.
- Android does not expose proxy, rate-limit, or force-IPv4 controls.
- Android does not expose free-text yt-dlp arguments.
- Android does not expose raw output templates or a Desktop-style archive file.
- Android uses MediaStore or the Storage Access Framework for final output.

These omissions avoid untested controls and unsafe free-text command input.

## Component Updater & Bundled Extensions Scope (v4.0.2 Resolution)

- **yt-dlp Updater Authenticity**: *Resolved in v4.0.2*. The updater performs standard OpenPGP detached signature verification (`SHA2-256SUMS.sig` over `SHA2-256SUMS`) using Bouncy Castle against the pinned upstream trust anchor (`AC0CBBE6848D6A873464AF4E57CF65933B5A7581`), validates the exact SHA-256 hash of `yt-dlp`, uses transactional staging with active component backup, and validates runtime execution (`versionName`) with automatic rollback.
- **Bundled Extensions Scope**: `uBlock Origin` (v1.74.0) and `Puemos HLS Detection` (v1.0.0) are built into APK assets (`app/src/main/assets/extensions/`) and loaded via `resource://android/assets/extensions/`. They are immutable at runtime and updated exclusively via application releases.
- **Transitive Dependencies**: `youtubedl-android` transitive components (`commons-io:2.5`, `commons-compress:1.12`, `jackson-databind:2.11.1`) are used only for internal APK package extraction and local subprocess JSON deserialization; R8 code shrinking minimizes exposed bytecode.

## Test scope

- The release test report identifies each physical test, automated test, and verification result.
- A test that was not run is not a pass.
