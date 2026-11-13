# VRKA Android 5.1.5 — Release Notes (Official Stable Release)

> [!IMPORTANT]
> **Upgrading to v5.1.5:**
> - **From v5.1.4, v5.1.3, or v5.1.2:** Update directly through **Settings -> Check for Updates** or install `VRKA-Android-v5.1.5.apk` over your existing install without uninstalling. Queue, History, Settings, and custom templates are preserved intact.
> - **From v5.0.2 or older:** First install [`VRKA-Android-v5.1.2.apk`](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) manually over v5.0.2 without uninstalling, open the app once, then update normally to v5.1.5.

VRKA Android **5.1.5** (`versionCode 50105`, `com.mvrk.vrka`) adds playlist child-job expansion, composite playlist selection dialogs, hardened MediaStore publication and recovery, in-app updater compatibility for current versions, Download page PNG wordmark branding, Android 16 User-Initiated Data Transfer (UIDT) notifications, and Issue #23 queue and deletion remediation.

## What Changed in 5.1.5

- **Playlist Child-Job Expansion & Concurrency:** Multi-item playlists expand atomically into independent child jobs (`isPlaylist=false`) with bounded concurrency (up to 4 parallel workers), unique job IDs, independent progress/history tracking, and graceful handling of aggregate size estimations.
- **Composite YouTube Playlist-Selection Dialog:** Detects composite YouTube links containing both video and playlist identifiers on paste or input, presenting "Entire Playlist" (enters expansion path) and "This Video Only" (strips playlist context).
- **Hardened MediaStore Publication & Recovery:** Direct insertion using `IS_PENDING=1` finalized to `0` without renaming `DISPLAY_NAME`. Preserves returned MediaStore URIs, prevents deleting verified media on publication errors, and maintains fail-closed staging recovery.
- **In-App Updater Handling for Current Versions:** When an installed app is at or newer than a valid release manifest (`versionCode <= installedVersionCode`), the updater cleanly reports "You are already using the latest version" instead of a confusing downgrade error, while preserving strict anti-downgrade protections.
- **Android 16 UIDT & Notification Isolation:** Implements User-Initiated Data Transfer (`RUN_USER_INITIATED_JOBS` and `DownloadTransferJobService`) on API 36, ensuring concurrent downloads maintain independent progress and distinct completion notifications that resolve to published media.
- **Download Page Header Branding:** Replaced text-based VRKA branding at the top-left of the Download page with the official transparent PNG wordmark, preserving natural 3:1 aspect ratio and vibrant purple rendering in both light and dark themes.
- **uBlock Origin XPI Cryptographic Verification (`XpiSignatureVerifier`):** Verifies bundled and downloaded `uBlock Origin` `.xpi` archives offline via PKCS#7 RSA signatures, pinned Mozilla Add-ons CA SPKI SHA-256, manifest digest coverage (`manifest.mf`/`mozilla.sf`), ZIP entry path/duplicate guards, and pinned SHA-256 checksums before installation.
- **Issue #23 Storage & Queue Remediation:** Isolates terminal completion ownership, ensuring cleanup obligations and queue dispatch proceed reliably across queued items and History deletions.
- **Media Detector & Credential Scoping Hardening:** Removes unneeded `"webRequestBlocking"` from `media-detector/manifest.json`, scopes captured headers by exact origin, strips credentials across cross-origin HTTP redirects, and restricts component updater redirects to approved GitHub hosts.
- **R8 / ProGuard Rule Narrowing:** Replaces blanket keep rules with targeted Jackson `@JsonProperty`, `ZipExtraField`, BouncyCastle JCA SPI, Room, and WorkManager rules, optimizing APK size while preserving obfuscation.

## Compatibility & Requirements

- **Requirements:** Android `8.0+` (API `26` min / `36` target), `arm64-v8a` only.
- **APK Filename:** `VRKA-Android-v5.1.5.apk`
- **Signing Cert SHA-256 (APK v2, RSA-4096):** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **OpenPGP Trust Anchor:** `0xD519380BEBA1B9F7` (`71165A658B3A8612AC2568C6D519380BEBA1B9F7`)
- **Full Evidence:** See [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md), [TEST_RESULTS.md](TEST_RESULTS.md), and [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

---

# VRKA Android 5.1.4 — Release Notes (Prior Stable Release)

> [!IMPORTANT]
> **Upgrading to v5.1.4:**
> - **From v5.1.3 or v5.1.2:** Update via **Settings -> Check for Updates** or install `VRKA-Android-v5.1.4.apk` over your existing install without uninstalling. Queue, History, Settings, and templates are preserved.
> - **From v5.0.2 or older:** First install [`VRKA-Android-v5.1.2.apk`](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) manually over v5.0.2 without uninstalling, launch once, then update normally to v5.1.4.

VRKA Android **5.1.4** (`versionCode 50104`, `com.mvrk.vrka`) hardens custom `yt-dlp` fork safety, custom command blocking, in-app update verification, and project documentation.

## What Changed in 5.1.4

- **Safer custom `yt-dlp` forks:** Requires confirming a SHA-256 untrusted-runtime warning before installing a fork, re-hashes active custom binaries before execution (falling back fail-closed to stock if altered), and repairs corrupted local binaries when reinstalling a verified tag.
- **Locked-down commands & privacy:** Blocks unsafe custom template flags and credential headers (`Cookie`, `Authorization`) via shared `CustomCommandPolicy`, sets `android:allowBackup="false"`, redacts tokens/paths in failure logs, and adds one-tap GeckoView Browser Fallback session clearing.
- **Stricter in-app updates:** Bounds HTTPS redirects (`5` hops across approved GitHub hosts) and verifies target `versionCode` through WorkManager staging and installer handoff.
- **Audited builds & simpler docs:** Uses locked/hashed Gradle dependencies (`--dependency-verification=strict`), SHA-pinned GitHub Actions, hashed bundled binaries, single `main` branch policy, and concise guides in [docs/README.md](docs/README.md).

## Compatibility & Verification

- **Requirements:** Android `8.0+` (API `26` min / `36` target), `arm64-v8a` only.
- **APK SHA-256 (`VRKA-Android-v5.1.4.apk`):** `5535291ff60c89852f1b2b960379a3b2637576e833ab3951759522110bba5d97`
- **Signing Cert SHA-256 (APK v2, RSA-4096):** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **OpenPGP Trust Anchor:** `0xD519380BEBA1B9F7` (`71165A658B3A8612AC2568C6D519380BEBA1B9F7`)
- **Full Evidence:** See [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md), [TEST_RESULTS.md](TEST_RESULTS.md), and [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

---

## Previous Releases

For full change history see [CHANGELOG.md](CHANGELOG.md) or [GitHub Releases](https://github.com/MaverickRox/VRKA-Android/releases).

| Version | Code | Focus | APK SHA-256 |
|---|---:|---|---|
| **v5.1.3** | `50103` | Atomic Room/SQLite storage engine, crash-safe JSON migration, MediaStore/SAF readback verification, stricter OpenPGP policy | `c9f58d3a97d728315b51fc92f43b5f54b88ae4ad9c25b3b22ea0cd8338549ddf` |
| **v5.1.2** | `50102` | `PublicationJournal` crash recovery, pending provider naming, atomic playlist failure handling | `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a` |
| **v5.1.1** | `50101` | Authoritative `-f` size preflight & Browser Fallback sizing, Android 8–9 SAF checks, redacted failure URLs | `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8` |
| **v5.1.0** | `50100` | 14-language UI (including Arabic RTL), size estimation, luminous wake bar, OpenPGP updater manifests | `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa` |
| **v5.0.2** | `50002` | Background `WorkManager` update downloads, retry handling, APK staging improvements (legacy `.download.tmp` bug; requires `v5.1.2` bridge) | `136707980c34ea4242ab3b183571bc24c063a3ddb22bdb0a1cf56258345b9d54` |
| **v5.0.1** | `50001` | Strict release tag/asset/HTTPS redirect and downgrade certificate verification | `75f7b5edf514724e21b0237e9e66813f88df0f51e0e1d620702d6b03d177dbcd` |
| **v5.0** | `50000` | Liquid Glass UI, Share Sheet, concurrent downloads, GeckoView `156` + uBlock `1.74.0`, H.264 mode, custom templates | `d821b65be0d054f3232e7a3bb051408bc0150eb2a66cdf5e7d3fc9170d8da487` |
