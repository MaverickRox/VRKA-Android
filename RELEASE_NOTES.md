# VRKA Android 5.1.4 — Release Notes

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
