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
