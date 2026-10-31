# VRKA Android 5.1.0 — Release Security Review

**Date:** 2026-10-31
**Scope:** Source changes for v5.1.0 localization, preflight download size estimation, custom runtime management, and the resulting production-signed APK.

## Custom runtimes and component updates

- Custom yt-dlp runtimes configured in Settings are validated for integrity and execution safety without shell interpolation.
- Upstream official yt-dlp component updates continue to enforce OpenPGP signature verification before installation.
- uBlock Origin updates enforce Firefox WebExtension XPI structure and checksum checks.

## Localization and resource security

- All 14 application locale string resources were audited for XML structure, encoding integrity, and key parity.
- Format strings preserve positional specifiers without formatting string injection risks.
- Right-to-left layout handling for Arabic ensures UI elements remain bounded and readable.

## Release signature and integrity verification

- The final production APK (`VRKA-Android-v5.1.0.apk`) was verified with `apksigner`:
  - One RSA 4096-bit signer
  - APK Signature Scheme v2 verified
  - Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
  - Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
  - Exact match with established production signing identity.
- No keystores, private keys, or passwords exist in the repository or public documentation.

## Verification boundary

- Unit tests: 601 passing tests across 58 suites, 0 failures.
- Release lint: 275 warnings, 0 hints, 0 errors.
- Production APK build: non-debuggable, minified with R8, shrunk resources.
- Physical OnePlus 11 hardware was unavailable; emulator validation and automated suites passed.

---

# VRKA Android 5.0 — Release Security Review

**Date:** 2026-10-04
**Scope:** source changes for final local release preparation and the resulting production-signed APK. This is not a dynamic penetration test.

## Custom yt-dlp templates

- Templates are parsed as argument lists and passed to yt-dlp without shell execution.
- VRKA retains control of the public URL, staging path, and output publication.
- Existing policy rejects shell hooks and unsafe output/config paths, cookie files, external downloaders, credential-bearing header arguments, proxies, and extra positional URLs.
- Eligible failed custom attempts may enter Browser Fallback. The fallback request clears template IDs/arguments and user-entered headers and uses the standard yt-dlp path.

## URLs, headers, and persisted state

- External job URLs are constrained to HTTP(S).
- Header validation rejects malformed/CRLF values and restricts sensitive browser-session headers to approved use.
- Browser candidate URLs and captured headers are held in memory and are excluded from persisted job data.
- Diagnostics sanitize URLs, headers, and free text; Browser Fallback failures do not expose private candidate URLs.

## Runtime components and release signature

- GeckoView hosts navigation and the active VRKA media-detector and uBlock WebExtensions.
- yt-dlp owns normal and Browser Fallback transfers; FFmpeg handles local processing. Media3/remote-FFmpeg code has no active manager call site, and N_m3u8DLRE is gated off.
- The retired extension is absent from the APK and active updater/registration paths. Upgrade-only cleanup cancels its queued update, clears old preferences, uninstalls the existing Gecko extension ID, and deletes its old copied XPI.
- The final APK was verified with `apksigner`: one RSA 4096-bit signer, v2 scheme verified, certificate SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`. This matches the known production certificate. Signing secrets and passwords are not included in the repository or this report.

## Verification boundary

The clean build passed 348 unit tests and release lint (67 warnings, one hint, no errors or fatal findings). APK metadata and packaged assets were inspected. No emulator, ADB, physical device, or dynamic network interception was used. Browser Fallback remains dependent on yt-dlp and site behavior and does not bypass DRM, authentication, payment, CAPTCHA, or other access controls. See [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) for exact artifact metadata and [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md) for device-test status.
