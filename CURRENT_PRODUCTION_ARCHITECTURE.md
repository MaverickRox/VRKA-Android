# VRKA Android 5.1.2 — Current Production Architecture

This document defines the architecture of the official, published VRKA Android v5.1.2 release (tag v5.1.2) and documents the production architectural pillars and crash-recovery hardening. Release-specific build, cryptographic, and test evidence is recorded in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md). Subsystem verification and physical device validation evidence are authoritatively documented in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

---

## 1. Architectural Overview

VRKA Android is a native Android media downloader and passive web stream discovery application built with **Jetpack Compose** and **Kotlin Coroutines**. It operates strictly on-device with zero application telemetry or cloud tracking.

The 5.1.0 and 5.1.1 releases established the following core architectural pillars, retained by 5.1.2:
- **Primary Transfer Engine**: `yt-dlp` is the primary and sole transfer engine for both direct downloads and Browser Fallback acquisitions.
- **Browser Fallback Engine**: Mozilla `GeckoView` operates in a task-scoped private session to load protected pages and observe media streams.
- **Media Candidate Discovery**: An internal first-party `VRKA Media Detector` WebExtension passively intercepts media requests and DOM elements, passing candidates to native Kotlin via `GeckoMediaBridge`.
- **Integrated Content Filtering**: Bundled `uBlock Origin` filters ad, tracking, and popup requests inside GeckoView during fallback discovery.
- **Local Media Processing**: `FFmpeg` handles local audio extraction, container remuxing, stream validation, and optional H.264 transcoding.
- **14-Language Interface Localization**: Comprehensive multilingual support across 14 languages plus System Default, including complete Right-to-Left (RTL) layout mirroring for Arabic and 100% string resource key parity.
- **Cryptographically Authenticated In-App Self-Updater**: Machine-readable release manifests authenticated by detached OpenPGP signatures against a pinned 4096-bit RSA trust anchor, featuring anti-rollback, anti-freeze, and two-stage installation verification.
- **Independent Component Updaters**: Dedicated background update managers for `yt-dlp` and `uBlock Origin` with single-flight locks and dynamic runtime version introspection.

---

## 2. Interface Localization & Multilingual Architecture

VRKA Android 5.1.2 retains complete interface localization across **14 distinct languages** plus **System Default**:

| Locale Tag | Language | Native Name | Layout Direction | Resource Directory |
|---|---|---|---|---|
| `ar` | Arabic | العربية | Right-to-Left (RTL) | `res/values-ar/strings.xml` |
| `bn` | Bengali | বাংলা | Left-to-Right (LTR) | `res/values-bn/strings.xml` |
| `zh-rCN` | Simplified Chinese | 简体中文 | Left-to-Right (LTR) | `res/values-zh-rCN/strings.xml` |
| `en` | English (Default) | English | Left-to-Right (LTR) | `res/values/strings.xml` |
| `fr` | French | Français | Left-to-Right (LTR) | `res/values-fr/strings.xml` |
| `de` | German | Deutsch | Left-to-Right (LTR) | `res/values-de/strings.xml` |
| `gu` | Gujarati | ગુજરાતી | Left-to-Right (LTR) | `res/values-gu/strings.xml` |
| `hi` | Hindi | हिन्दी | Left-to-Right (LTR) | `res/values-hi/strings.xml` |
| `kn` | Kannada | ಕನ್ನಡ | Left-to-Right (LTR) | `res/values-kn/strings.xml` |
| `pt-rBR` | Portuguese (Brazil) | Português (Brasil) | Left-to-Right (LTR) | `res/values-pt-rBR/strings.xml` |
| `pt-rPT` | Portuguese (Portugal) | Português (Portugal) | Left-to-Right (LTR) | `res/values-pt-rPT/strings.xml` |
| `es` | Spanish | Español | Left-to-Right (LTR) | `res/values-es/strings.xml` |
| `ta` | Tamil | தமிழ் | Left-to-Right (LTR) | `res/values-ta/strings.xml` |
| `te` | Telugu | తెలుగు | Left-to-Right (LTR) | `res/values-te/strings.xml` |

### Localization Technical Characteristics:
1. **Resource Key Parity**: Every localized resource file maintains 100% key parity with the base English strings (`res/values/strings.xml`). Positional formatting specifiers (`%1$s`, `%2$d`) are strictly validated to prevent format-string runtime exceptions.
2. **Full RTL Support**: For Arabic (`ar`), the application enforces complete bidirectional layout mirroring, start/end alignment semantics, and mirrored typography across all screens, navigation capsules, cards, and modal dialogs.
3. **Language Selection & Persistence**: Users can change language preferences dynamically in Settings via `LanguageSelector`. Preferences are persisted in DataStore (`selected_language_tag`) and integrate with Android 13+ (API 33+) per-app language APIs via `LocaleManagerCompat`.

---

## 3. Core Media Acquisition Pipeline

```
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│   (HomeScreen, JobsScreen, HistoryScreen, Settings)   │
└──────────────────────────┬─────────────────────────────┘
                           │ User Request / Link
                           ▼
               ┌───────────────────────┐
               │  VrkaDownloadManager  │
               └───────────┬───────────┘
                           │
        ┌──────────────────┴──────────────────┐
        ▼                                     ▼
┌──────────────────────────┐      ┌──────────────────────────┐
│  JobStore (JSON Storage) │      │ Foreground DownloadService│
│  State & Progress Sync   │      │ Notification & Workers   │
└──────────────────────────┘      └──────────────────────────┘
```

### 3.1 Normal Download Flow
1. **Intake & Request Construction**:
   - The user inputs a URL directly or shares a link via the Android Share Sheet.
   - `VrkaDownloadManager` creates a job entry in `JobStore`.
   - `DownloadRequestFactory` constructs argument lists (`argv`) for `yt-dlp` without invoking a shell.
   - HTTP headers (`Referer`, `Origin`, custom headers) are validated against RFC 7230 token specifications and CRLF injection rejection. Sensitive tokens and cookies are redacted (`[REDACTED]`) from logs, crash reports, and UI state.
2. **Extraction & Transfer**:
   - `yt-dlp` executes stream extraction and downloading directly to an isolated temporary staging directory (`cacheDir/staging/<jobId>`).
   - Progress callbacks stream bytes transferred, ETA, speed, and percentage to the foreground notification and Compose UI.
3. **Local Post-Processing & Packaging**:
   - If audio extraction is requested, `FFmpeg` extracts and encodes the selected format (MP3 with selectable 128–320 kbps bitrate, native Opus stream-copy with transcode fallback, or uncompressed 16-bit PCM WAV).
   - If H.264 Compatibility Mode is active and the video codec is not H.264, `FFmpeg` transcodes video via `libx264` while copying audio/subtitle tracks.
4. **Validation & MediaStore Export**:
   - Staged files are verified: non-zero byte size, valid container headers, and expected format.
   - `OutputPublisher` publishes the final file to the user's destination directory (`Downloads/VRKA` or user-selected SAF tree) and updates `JobStore` (where completed records serve as download history).

---

## 4. Browser Fallback Subsystem

When direct `yt-dlp` extraction fails due to anti-bot challenges (e.g., Cloudflare, bot verification, HTTP 403 Forbidden, or dynamic client-side player scripts), `VrkaDownloadManager` automatically routes the request to the **Browser Fallback Subsystem**.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     Browser Fallback Subsystem                           │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │                    GeckoView Private Session                       │  │
│  │                                                                    │  │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────┐  │  │
│  │  │        uBlock Origin        │  │     VRKA Media Detector     │  │  │
│  │  │   (uBlock0@raymondhill.net) │  │  (media-detector@vrka.mvrk) │  │  │
│  │  │   Request & Tracker Filter  │  │  Passive DOM / Net Observer │  │  │
│  │  └─────────────────────────────┘  └──────────────┬──────────────┘  │  │
│  └──────────────────────────────────────────────────┼─────────────────┘  │
│                                                     ▼ Candidates JSON    │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │             GeckoMediaBridge (Native Kotlin WebExtension API)       │  │
│  └──────────────────────────────────┬─────────────────────────────────┘  │
│                                     ▼                                    │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │            Candidate Store & Ranker (MIME, Bitrate, Resolution)     │  │
│  └──────────────────────────────────┬─────────────────────────────────┘  │
│                                     ▼ Selected Best Candidate Stream     │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │            yt-dlp Standard CLI Candidate Probing & Transfer        │  │
│  └──────────────────────────────────┬─────────────────────────────────┘  │
│                                     ▼ Transferred File                   │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │          FFmpeg Post-Processing ➔ Validation ➔ OutputPublisher     │  │
│  └────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────┘
```

### 4.1 Browser Fallback Lifecycle & Candidate Handoff
1. **Isolated Session Initialization**:
   `BrowserFallbackCoordinator` initializes an ephemeral, private Mozilla `GeckoView` session with dedicated cookie jars and storage partitions.
2. **Content & Tracker Suppression**:
   Bundled `uBlock Origin` (`uBlock0@raymondhill.net`) intercepts network requests to suppress banner advertisements, tracking scripts, coin miners, and malicious redirects.
3. **Passive Candidate Sniffing**:
   The internal `VRKA Media Detector` WebExtension (`media-detector@vrka.mvrk.com`) observes outgoing web requests (`webRequest.onBeforeRequest`) and inspects DOM `<video>` and `<audio>` elements for direct media URLs, HLS playlists (`.m3u8`), and DASH manifests (`.mpd`).
4. **Native Bridge Interface**:
   Discovered media candidates are formatted as JSON payloads and delivered over the WebExtension runtime messaging channel to `GeckoMediaBridge`.
5. **Candidate Ranking & Selection**:
   The candidate store collects, de-duplicates, and ranks stream candidates based on container quality, audio/video streams, resolution, and reported bitrate.
6. **yt-dlp Execution**:
   VRKA probes the top-ranked candidate with `yt-dlp` and executes the transfer using standard yt-dlp requests.
   > [!IMPORTANT]
   > User-defined custom yt-dlp template arguments and custom HTTP headers from normal mode are strictly isolated and not passed into Browser Fallback requests to ensure web player tokens and signatures are not corrupted.
7. **Local Packaging & Publication**:
   `FFmpeg` muxes streams, validates the container, and `OutputPublisher` saves the completed media file. Browser Fallback does not fetch subtitles; subtitle options apply to normal downloads.

---

## 5. WebExtension & Component Responsibilities

| Component | Nature | Role & Execution Boundary | Update Mechanism |
|---|---|---|---|
| **Mozilla GeckoView** | Core Engine | Headless / sandboxed browser engine hosting fallback navigation and extensions (`159.0.20261022150525`). | Static Gradle dependency (`geckoview-arm64-v8a`). Not updated via component updater. |
| **VRKA Media Detector** | First-Party WebExtension | Passive media observation (`media-detector@vrka.mvrk.com`). Shipped in `assets/extensions/media-detector/`. | Shipped with application APK. |
| **uBlock Origin** | Third-Party WebExtension | Network request and tracker filtering (`uBlock0@raymondhill.net` v1.74.0). Shipped as `assets/extensions/ublock.xpi`. | Updated in-app via `ComponentUpdateManager`. |
| **yt-dlp** | Core Transfer Engine | Primary media extractor and downloader for both normal and browser fallback acquisitions. | Updated in-app via `ComponentUpdateManager` or custom runtime selection. |
| **FFmpeg** | Local Media Engine | Local audio extraction, container muxing, validation, and optional H.264 transcoding. | Shipped via `youtubedl-android:ffmpeg` (`arm64-v8a`). |
| **Puemos Extension** | **RETIRED** | **Completely removed from production.** No assets shipped, no runtime registration, no update mechanism. | One-time migration cleanup deletes legacy files from existing installs. |
| **Dormant Transfer Prototype** | Prototype Code | `VrkaDownloadManager.downloadViaMediaEngine` and `MediaEngineRouter` exist in source only with no callers. | Dormant; not used in production. |

---

## 6. Application Self-Updater Subsystem

VRKA Android 5.1.2 retains the independent, cryptographically authenticated self-updater targeting official GitHub releases.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   Application Self-Updater Architecture                  │
│                                                                          │
│  GitHub Releases API (Official Repo: MaverickRox/VRKA-Android)           │
│  │                                                                       │
│  ├── 1. Query Latest Release (24h Automatic Gate / Settings Manual Check)│
│  │                                                                       │
│  ├── 2. Download Machine-Readable Manifest & Detached Signature:         │
│  │      • VRKA-Android-v5.1.0.manifest.json                              │
│  │      • VRKA-Android-v5.1.0.manifest.json.asc                          │
│  │                                                                       │
│  ├── 3. Cryptographic Verification (ReleaseManifestVerifier):            │
│  │      • Pinned 4096-bit RSA OpenPGP Key (0xD519380BEBA1B9F7)           │
│  │      • SHA-256 Digest Verification of Detached Signature             │
│  │      • Reject Weak Algorithms (MD5, SHA-1, RIPEMD-160, DSA)           │
│  │      • Anti-Freeze Check: Current UTC time < expires_at               │
│  │      • Anti-Rollback Check: metadata_revision >= stored_revision      │
│  │      • Monotonic Version Check: remote versionCode > installedCode    │
│  │                                                                       │
│  ├── 4. WorkManager Download:                                            │
│  │      • Stream APK to cacheDir/updates/app-update-<code >.download.tmp │
│  │      • Validate HTTPS origin and host bounds                          │
│  │                                                                       │
│  ├── 5. Two-Stage Installation Verification:                             │
│  │      • Stage 1 (Archive Integrity on .tmp): Zip archive validity,     │
│  │        SHA-256 digest match, package ID ("com.mvrk.vrka"),           │
│  │        versionCode, and signer certificate SHA-256.                   │
│  │      • Stage 2 (Staged File Integrity): Atomic rename to canonical    │
│  │        VRKA-Android-vX.Y.Z.apk, directory confinement, non-debuggable │
│  │        flag, and FileProvider content URI generation.                 │
│  │                                                                       │
│  └── 6. Android PackageInstaller:                                        │
│         • Launch ACTION_VIEW intent with FLAG_GRANT_READ_URI_PERMISSION  │
└──────────────────────────────────────────────────────────────────────────┘
```

### Trust Anchor & Verification Constants:
- **Pinned Key ID**: `0xD519380BEBA1B9F7`
- **Pinned Key Fingerprint**: `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **Public Key Resource**: `app/src/main/res/raw/vrka_release_pubkey.asc`
- **Production Signer Certificate SHA-256**: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

---

## 7. Component Updaters (yt-dlp & uBlock Origin)

In-app component updates are managed independently from application self-updates:
- **Target Components**: `yt-dlp` and `uBlock Origin` only.
- **Background Execution**: Backed by `WorkManager` to ensure update downloads survive activity destruction.
- **Concurrency Protection**: Strict single-flight locking per component prevents duplicate or spam requests.
- **Dynamic Version Introspection**: `InstalledComponentVersionReader` dynamically reads `yt_dlp/version.py` directly from the active zipapp runtime or from bundled `R.raw.ytdlp` using `ZipFile` stream inspection.
- **Network Guards**: Bounded connect (6s) and read (6s) timeouts, an 8-second per-component deadline, and a 64 KiB response cap protect against slow or hanging connections.

---

## 8. User Interface & Liquid Glass Optics

The UI is built with **Jetpack Compose** using design tokens (`VrkaTokens`):
- **Liquid Glass Navigation**: Two independently rendered layers. The outer capsule applies a 0.75× blur and translucent veil. The active pill samples `LocalAppBackdrop` directly at full resolution.
- **AGSL RuntimeShader (API 33+)**: High-performance lens transform with 1.35× center magnification, 20-dp refraction height, 14-dp displacement, normal-map edge curvature, and chromatic aberration channel splitting. Fallback glass rendering is provided on API 26–32.
- **Directional Luminous Wake Progress**: 18-track directional streak with cached analytic teardrop glow optimized for AMOLED black backgrounds.
- **Resolution-Aware Size Estimation**: `SizeEstimator` runs a metadata-only yt-dlp preflight with the same `-f` selector and 60 fps sort as the transfer. `requested_formats` from that document are authoritative; the complete `formats` table still supplies every resolution chip. `MetadataRepository` caches each document by source, format selection, playlist range, runtime version, and a header digest. Browser Fallback clears the page-level estimate, estimates the selected candidate independently, and never blocks the transfer. Estimates that cannot be supported by metadata are shown as unavailable.
- **Recoverable Job Storage**: `JobStore` writes a versioned JSON object through a temporary file and atomic move, returns explicit save results, and on malformed input preserves a bounded recovery copy while salvaging valid records. In-flight recovery and terminal-state handling are unchanged.
- **API-Range Destination Policy**: API 29+ publishes through `MediaStore.Downloads` (`Downloads/VRKA`); API 26–28 require a granted SAF tree before a job starts and return to the folder prompt if permission is revoked.
- **Dynamic Typography**: Monospace Space Mono (`VRKA Font`) and clean System (`Roboto`) font switching.

---

## 9. v5.1.2 Production Hardening: Publication Crash Recovery & Consistency

The official published release binary (VRKA-Android-v5.1.2.apk) incorporates critical reliability and crash-recovery hardening:

1. **Durable Per-Output Publication Journal (PublicationJournal.kt)**:
   - Persists a durable publication intent before calling ContentResolver or SAF document creation.
   - Durably tracks pending provider object creation, byte copy count, media validation, and finalization.
   - Eliminates the crash window where process termination between provider export and JobStore terminal write could leave published files on disk without History records.
   - On application startup, `reconcilePublicationJournalOnStartup()` inspects unreconciled transactions: fully completed outputs are restored to `JobState.DONE` in History, while incomplete/interrupted files are safely purged to prevent orphaned or corrupt media files.
2. **Unified Publication Marker**:
   - Synchronously persists publishStarted = true for both normal yt-dlp acquisitions and MediaEngine Browser Fallback paths before outputs leave staging, guaranteeing fail-closed semantics across all transfer modes.
3. **Reconciled Verification Records**:
   - Authoritative consolidation in DEVICE_VALIDATION.md detailing OnePlus 11 hardware testing, maintainer-reported physical-device v5.1.1-to-v5.1.2 upgrade validation, and explicit platform boundaries.
