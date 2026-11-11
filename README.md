<div align="center">
  <img src="docs/assets/vrka-wolf-256.png" alt="VRKA Android Logo" width="128" />
  <h1>VRKA Android</h1>
  <p><b>Native on-device Android media downloader &amp; passive web stream discovery</b></p>
  <p>
    <a href="https://github.com/MaverickRox/VRKA-Android/releases/latest"><b>Download APK (v5.1.4)</b></a> •
    <a href="docs/README.md"><b>Documentation Hub</b></a> •
    <a href="docs/user-guide/GETTING_STARTED.md"><b>Getting Started</b></a> •
    <a href="CHANGELOG.md"><b>Changelog</b></a> •
    <a href="SECURITY.md"><b>Security Policy</b></a> •
    <a href="LICENSE"><b>GPL-3.0</b></a>
  </p>
  <p>
    <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026--36)-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+ (API 26-36)" />
    <img src="https://img.shields.io/badge/ABI-arm64--v8a-7B61FF?style=flat-square" alt="arm64-v8a" />
    <img src="https://img.shields.io/badge/Languages-14%20(RTL%20Arabic)-0A7EA4?style=flat-square" alt="14 Languages" />
    <img src="https://img.shields.io/badge/License-GPL--3.0-blue?style=flat-square" alt="GPL-3.0" />
  </p>
</div>

> [!IMPORTANT]
> **Upgrading to VRKA v5.1.4:**
> - **From v5.1.3 or v5.1.2:** Update directly through **Settings -> Check for Updates** or install `VRKA-Android-v5.1.4.apk` over your existing install without uninstalling. Signing identity is unchanged, so Queue, History, Settings, and templates are preserved (and v5.1.2 JSON data imports automatically into Room on first launch).
> - **From v5.0.2 or older:** Due to a historical staging filename bug in the old v5.0.2 client, its in-app updater cannot self-execute this upgrade. First install [`VRKA-Android-v5.1.2.apk`](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) manually over v5.0.2 **without uninstalling**, open the app once, then update normally to v5.1.4.
> - **Current Download Page:** [https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.4](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.4)

---

## Overview

**VRKA Android** is an open-source, Jetpack Compose companion to desktop [**VRKA**](https://github.com/MaverickRox/VRKA). All media extraction, remuxing, and optional transcoding happen directly on your phone using bundled `yt-dlp`, `FFmpeg`, and an embedded Mozilla `GeckoView` private fallback browser.

VRKA includes no application analytics, advertising, or crash-reporting SDKs, and does not upload History, queue state, or diagnostics to an application backend. Network requests are strictly limited to documented GitHub release/component checks, user-requested downloads, and user-initiated Browser Fallback pages; see [docs/PRIVACY_NETWORK.md](docs/PRIVACY_NETWORK.md).

### Why VRKA

- **Unified On-Device Engine:** Direct extraction with `yt-dlp` plus local `FFmpeg` muxing, MP3 (`128`–`320 kbps`), Opus, WAV, subtitle embedding, trimming, playlist ranges, and optional H.264 Compatibility Mode.
- **Resolution-Aware Size Estimation:** Pre-download confirmation uses the exact `-f` format selector and 60 FPS sort executed by the transfer; unavailable sizes are shown honestly instead of invented.
- **Browser Fallback When Sites Block Direct Requests:** Launches a task-scoped private `GeckoView` session with bundled `uBlock Origin` (`1.74.0`) and the first-party `VRKA Media Detector` WebExtension, handing chosen streams back to `yt-dlp`.
- **Crash-Safe Room Storage:** Single SQLite/Room (`VrkaDatabase`) authority for Queue, History, retry attempts, publication transactions, and cleanup obligations.
- **Hardened Self-Updater & Supply Chain:** OpenPGP-signed release manifests verified against a pinned RSA-4096 trust anchor (`0xD519380BEBA1B9F7`), anti-rollback revision checks, expiration enforcement, and APK SHA-256 + version-code + certificate verification.
- **14 Languages + AMOLED Liquid Glass UI:** Localized across 14 languages (including full RTL layout for Arabic) with hardware-accelerated backdrop blur and AGSL shader refraction on API 33+.

---

## Documentation Hub

| Audience | Recommended Starting Points |
|---|---|
| **Users** | [Getting Started](docs/user-guide/GETTING_STARTED.md) • [Features & Settings](docs/user-guide/FEATURES_AND_SETTINGS.md) • [Troubleshooting](docs/user-guide/TROUBLESHOOTING.md) |
| **Developers** | [Documentation Index](docs/README.md) • [Build & Test Guide](docs/developer/BUILD_AND_TEST.md) • [Production Architecture](CURRENT_PRODUCTION_ARCHITECTURE.md) • [Storage Architecture](docs/STORAGE_ARCHITECTURE.md) |
| **Security & Auditors** | [Security Policy](SECURITY.md) • [Security Review](SECURITY_REVIEW.md) • [Updater & Runtime Security Model](docs/security/UPDATER_AND_CUSTOM_RUNTIME_MODEL.md) • [Privacy & Network](docs/PRIVACY_NETWORK.md) |
| **Supply Chain & Release** | [Release Notes](RELEASE_NOTES.md) • [Release Provenance](docs/RELEASE_PROVENANCE.md) • [Release Evidence](RELEASE_EVIDENCE.md) • [Dependency Management](docs/DEPENDENCY_MANAGEMENT.md) • [CI/CD Security](docs/CI_CD_SECURITY.md) |

---

## Screenshots

Verified unframed AMOLED device captures from a physical Android device (v5.1.1 UI baseline; `browser-fallback-v5.1.1.png` captures a queued Browser Fallback stream transfer from `shaka-project.github.io`):

<div align="center">
<table>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/download-v5.1.1.png" width="220" alt="Video Download & Resolution Preflight" /><br />
      <b>Download &amp; Resolution Preflight</b><br />
      <sub>Quality chips &amp; format estimation</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/download-audio-v5.1.1.png" width="220" alt="Audio Extraction Mode" /><br />
      <b>Audio Extraction</b><br />
      <sub>MP3 bitrates (128–320 kbps), Opus &amp; WAV</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/confirmation-v5.1.1.png" width="220" alt="Pre-Download Size Confirmation" /><br />
      <b>Authoritative Size Confirmation</b><br />
      <sub>Matches exact selected transfer selector</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/queue-v5.1.1.png" width="220" alt="Active Download Queue" /><br />
      <b>Active Transfer Queue</b><br />
      <sub>Live speed, ETA, progress &amp; concurrency</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/history-v5.1.1.png" width="220" alt="Completed Download History" /><br />
      <b>Verified History</b><br />
      <sub>Open, Share &amp; Delete published media</sub>
    </td>
    <td align="center" width="33%">
      <img src="docs/screenshots/v5.1.1/browser-fallback-v5.1.1.png" width="220" alt="Queued Browser Fallback Transfer" /><br />
      <b>Queued Browser Fallback Transfer</b><br />
      <sub>Selected Shaka HLS candidate queued via yt-dlp</sub>
    </td>
  </tr>
  <tr>
    <td align="center" colspan="3">
      <img src="docs/screenshots/v5.1.1/settings-v5.1.1.png" width="220" alt="Settings & Storage Controls" /><br />
      <b>Settings &amp; Storage Policies</b><br />
      <sub>Destination, concurrency, runtimes, updates &amp; localization</sub>
    </td>
  </tr>
</table>
</div>

---

## Key Features in Detail

### Media Pipeline & Controls
- **Formats & Options:** Video (`Best`, `2160p`, `1440p`, `1080p`, `720p`, `480p`, `360p`), `Prefer 60 FPS`, Audio (`MP3` `128–320 kbps`, `Opus`, `WAV`), trim sections, subtitles, and bounded playlist ranges.
- **H.264 Compatibility Mode:** Optional local `libx264` conversion for broader hardware/app compatibility (off by default).
- **Custom Headers & Diagnostics:** RFC 7230-validated headers with CRLF rejection; sensitive credentials redacted (`[REDACTED]`). Failed jobs retain sanitized local failure context only (never uploaded automatically); explicit opt-in warns before exporting unredacted failure URLs.
- **Custom Command Templates:** Safe `argv` template builder blocking shell execution (`--exec*`, `--downloader*`), output/path overrides (`-o`, `-P`), insecure TLS flags, and persistent credential headers (`Cookie`, `Authorization`), re-validated cleanly at request build time via shared `CustomCommandPolicy` helpers.
- **Custom `yt-dlp` Runtimes:** Stock bundled `yt-dlp` (`app/src/main/res/raw/ytdlp`) uses OpenPGP component verification (`SecureComponentUpdater`). Optional third-party GitHub `owner/repo` zipapp runtimes require explicit untrusted-runtime warning confirmation displaying the repo, version, and SHA-256, CPython `--version` execution in staging, and continuous on-disk SHA-256 re-verification with fail-closed fallback to stock.

### Supported Languages (14 + System Default)
Arabic (`ar`, full RTL), Bengali (`bn`), Simplified Chinese (`zh-rCN`), English (`en`), French (`fr`), German (`de`), Gujarati (`gu`), Hindi (`hi`), Kannada (`kn`), Portuguese Brazil (`pt-rBR`), Portuguese Portugal (`pt-rPT`), Spanish (`es`), Tamil (`ta`), and Telugu (`te`). See [docs/developer/BUILD_AND_TEST.md](docs/developer/BUILD_AND_TEST.md) and [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md) for localization parity guarantees.

---

## Architecture

```mermaid
flowchart TD
    subgraph UI["Jetpack Compose UI (14 Languages / Liquid Glass)"]
        Home["Download Screen"]
        Queue["Queue"]
        History["History"]
        Settings["Settings & Diagnostics"]
    end

    subgraph Core["Core Pipeline & Durable Storage"]
        DM["VrkaDownloadManager"]
        DRF["DownloadRequestFactory<br/><i>Shared CustomCommandPolicy &amp; Header Redaction</i>"]
        RTM["YtDlpRuntimeManager<br/><i>SHA-256 Pinned Stock / Custom Runtime</i>"]
        DB[("VrkaDatabase (Room / SQLite)<br/><i>Jobs, Attempts, Publication &amp; Cleanup</i>")]
        CACHE["StateFlow Cache"]
        FGS["Foreground DownloadService"]
    end

    subgraph Fallback["GeckoView Browser Fallback"]
        FBC["BrowserFallbackCoordinator"]
        GV["Private GeckoView Session<br/><i>uBlock Origin 1.74.0 + VRKA Media Detector</i>"]
        RANK["Candidate Ranker &amp; Size Preflight"]
    end

    subgraph Export["Processing & Android Publication"]
        FFMPEG["FFmpeg (Audio / Remux / Optional H.264)"]
        PUB["OutputPublisher (MediaStore API 29+ / SAF)"]
    end

    subgraph SecurityUpdates["Self-Updater & Component Updaters"]
        AUM["AppUpdateManager + ReleaseManifestVerifier<br/><i>Pinned RSA-4096 OpenPGP (0xD519380BEBA1B9F7)</i>"]
        CUM["ComponentUpdateManager<br/><i>Stock yt-dlp &amp; uBlock Origin</i>"]
    end

    Home --> DM
    Queue --> DM
    DM --- DB
    DB -->|committed rows| CACHE
    CACHE --- History
    DM --- FGS
    DM --> DRF
    DRF --> RTM
    RTM --> FFMPEG
    RTM -.->|Eligible 403 / JS player failure| FBC
    FBC --> GV --> RANK --> DRF
    FFMPEG --> PUB --> DB
    Settings -.-> AUM
    Settings -.-> CUM
```

See [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md), [docs/STORAGE_ARCHITECTURE.md](docs/STORAGE_ARCHITECTURE.md), and [docs/security/UPDATER_AND_CUSTOM_RUNTIME_MODEL.md](docs/security/UPDATER_AND_CUSTOM_RUNTIME_MODEL.md) for full details.

---

## Building & Verifying Locally

See [docs/developer/BUILD_AND_TEST.md](docs/developer/BUILD_AND_TEST.md) for complete setup and API 36 emulator test commands. Quick summary:

```powershell
# Full CI-equivalent offline/strict verification on Windows (set JAVA_HOME / ANDROID_HOME first)
.\gradlew.bat --no-daemon --no-configuration-cache --dependency-verification=strict `
  testDebugUnitTest lintDebug lintRelease compileDebugAndroidTestKotlin compileReleaseKotlin `
  assembleDebug assembleDebugAndroidTest assembleDevRelease
```

Production release signing is strictly isolated (`assembleRelease` fails closed without external `signing.properties` / `VRKA_SIGNING_PROPERTIES`; debug/CI builds never read production credentials). Supply-chain controls (repository allowlisting, no `mavenLocal()`, lockfiles, SHA-256 dependency verification metadata, SHA-pinned GitHub Actions, and native asset inventory) are documented in [docs/DEPENDENCY_MANAGEMENT.md](docs/DEPENDENCY_MANAGEMENT.md) and [docs/CI_CD_SECURITY.md](docs/CI_CD_SECURITY.md).

---

## Origin, Attribution & License

- **Desktop Counterpart:** Companion Android port of [**VRKA**](https://github.com/MaverickRox/VRKA) by [MaverickRox](https://github.com/MaverickRox).
- **Third-Party Software & Demo Assets:** Uses `yt-dlp` (Unlicense), Mozilla `GeckoView` (MPL-2.0), `uBlock Origin` (GPL-3.0), `FFmpeg` (LGPL/GPL), `Space Mono` (OFL-1.1), `Mornye` switch component (LGPL-3.0 / GPL-3.0), Blender Foundation Creative Commons Open Movies (*Big Buck Bunny*, *Sintel*, *Tears of Steel*, CC-BY 3.0), and Google *Shaka Player* demo (Apache-2.0). Full attribution is in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **License:** Free software under [GNU General Public License v3.0](LICENSE).
