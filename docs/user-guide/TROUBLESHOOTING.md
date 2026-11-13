# Troubleshooting Guide

## Updating & Installation

### Updating from v5.0.2 fails with "APK filename does not match the expected release"
**Cause:** v5.0.2 shipped a client-side staging filename bug that rejects self-downloaded updates.
**Fix:** Do **not** uninstall VRKA. Download [`VRKA-Android-v5.1.2.apk`](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) manually, install it over v5.0.2, launch VRKA once, and then update to the latest release (`v5.1.4` or newer). Your History and Settings stay intact.

### App Update check says "Release manifest verification failed" or "Missing mandatory OpenPGP signed manifest assets"
**Cause:** Either the release asset is missing `.manifest.json` / `.manifest.json.asc`, the device clock is significantly wrong (causing manifest `issued_at` skew or `expires_at` failure), or a network proxy altered the response.
**Fix:** Confirm accurate date/time on the device, verify direct HTTPS access to GitHub (`api.github.com` / `github.com`), or download the verified APK + `SHA256SUMS` from GitHub Releases and verify locally.

### Android says "App not installed as package appears to be invalid" or "Conflict with existing package"
- Ensure your device uses `arm64-v8a` (64-bit ARM).
- Do not install an unsigned or third-party re-signed build over an official production build (or vice versa); official releases share certificate SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`.
- If you previously installed a local `debug` or `devRelease` build, uninstall it first before installing the official release APK.

## Downloads, Estimation & Storage

### Pre-download size shows "Calculating size" or "Unavailable"
Some live streams (HLS/DASH manifests without `Content-Length` or `filesize_approx`) and third-party hosts do not declare byte sizes upfront. VRKA displays `"Unavailable"` rather than inventing an estimate; tapping **Download** still transfers the media normally.

### "Destination permission required" on Android 8 / 9 (API 26–28) or SAF folders
If the granted SAF document tree permission was revoked or the SD card/USB location became unavailable, go to **Settings -> Destination -> Change** and re-grant the folder.

### Download stuck or failed recovery notices in History / Diagnostics
Open **Settings -> Diagnostics**.
- If Room quarantined a corrupt legacy record, it appears as a safe recovery entry in History without private URLs.
- If a background staging or provider cleanup task exhausted retries, tap manual retry on that dead-letter task in Diagnostics once the destination folder is accessible again.

### Why didn't Browser Fallback download a DRM/paid/CAPTCHA stream?
VRKA does not bypass DRM encryption, logins requiring external interactive auth walls, payment paywalls, or anti-bot CAPTCHAs. If a site blocks automated extraction and protected media playback isn't exposed as a standard unencrypted stream, transfer cannot succeed.

## Custom Templates & Custom `yt-dlp` Runtimes

### My command template was rejected with an unsafe/blocked option error
VRKA restricts command templates to safe `yt-dlp` options so saved templates cannot invoke external binaries (`--exec`, `--downloader`), write arbitrary paths (`-o`, `-P`), bypass TLS (`--no-check-certificates`), or persist sensitive credentials (`--add-header Cookie:...` / `Authorization:...`). Pass session headers/cookies via Browser Fallback instead.

### Custom `yt-dlp` runtime failed verification or reverted to stock
- Only GitHub `owner/repo` repositories publishing a standalone Python zipapp asset named `yt-dlp` (up to 64 MiB) are supported; platform `.exe`/ELF binaries cannot run inside Android CPython.
- If a custom binary is modified or deleted on disk after install, `YtDlpRuntimeManager` refuses activation (`SHA-256 verification failed`) and falls back to the **stock** runtime automatically. Reinstall the fork in **Settings** or switch back to **Stock yt-dlp**.
