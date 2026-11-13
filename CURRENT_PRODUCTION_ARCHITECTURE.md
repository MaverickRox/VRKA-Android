# VRKA Android 5.1.5 (Local Remediation) — Current Architecture

This document defines the architecture of **VRKA Android v5.1.5** (`versionCode 50105`, local remediation tree relative to published `v5.1.4`). Release-specific build, cryptographic, and test evidence is recorded in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md). Subsystem verification and device validation evidence are recorded in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

> [!NOTE]
> **v5.1.3 introduced and v5.1.5 retains (with Issue #23 queue/deletion remediation) the Room/SQLite storage authority.** The v5.1.2 APK used JSON `JobStore`,
> `PublicationJournal`, and `StagingCleanupStore` files. v5.1.3+ imports their data through a
> crash-safe legacy importer and uses Room for current jobs, publication state, and cleanup
> obligations. The migration is documented separately in
> [docs/STORAGE_ARCHITECTURE.md](docs/STORAGE_ARCHITECTURE.md) and
> [docs/STORAGE_MIGRATION.md](docs/STORAGE_MIGRATION.md). The v5.1.2 and v5.1.3 artifacts and
> evidence remain historical and unchanged.

---

## 1. Architectural Overview

VRKA Android is a native Android media downloader and passive web stream discovery application built with **Jetpack Compose** and **Kotlin Coroutines**. It has no application analytics, advertising, or telemetry SDK and does not upload History, diagnostics, or queue data to an application backend. Documented update checks and user-requested media/browser traffic are described in [docs/PRIVACY_NETWORK.md](docs/PRIVACY_NETWORK.md).

The 5.1.0 and 5.1.1 releases established the following core architectural pillars, retained and hardened through 5.1.5:
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

VRKA Android 5.1.5 retains complete interface localization across **14 distinct languages** plus **System Default**:

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
│ Room (`VrkaDatabase`) &  │      │ Foreground DownloadService│
│ `StateFlow` Cache Sync   │      │ Notification & Workers   │
└──────────────────────────┘      └──────────────────────────┘
```

### 3.1 Normal Download Flow
1. **Intake & Request Construction**:
   - The user inputs a URL directly or shares a link via the Android Share Sheet.
   - `VrkaDownloadManager` persists a durable job entry in Room (`VrkaDatabase`) via `RoomStorageRepository` and updates the UI `StateFlow` cache after commit.
   - `DownloadRequestFactory` constructs argument lists (`argv`) for `yt-dlp` without invoking a shell, enforcing `CustomCommandPolicy` consistently with Settings custom-template validation to reject shell hooks, external downloaders, config/cookie file flags, and output-path overrides.
   - HTTP headers (`Referer`, `Origin`, custom headers) are validated against RFC 7230 token specifications and CRLF injection rejection. Sensitive tokens and cookies are redacted (`[REDACTED]`) from logs, crash reports, and UI state.
2. **Extraction & Transfer**:
   - `yt-dlp` executes stream extraction and downloading directly to an isolated temporary staging directory (`cacheDir/staging/<jobId>`).
   - Progress callbacks stream bytes transferred, ETA, speed, and percentage to the foreground notification and Compose UI.
3. **Local Post-Processing & Packaging**:
   - If audio extraction is requested, `FFmpeg` extracts and encodes the selected format (MP3 with selectable 128–320 kbps bitrate, native Opus stream-copy with transcode fallback, or uncompressed 16-bit PCM WAV).
   - If H.264 Compatibility Mode is active and the video codec is not H.264, `FFmpeg` transcodes video via `libx264` while copying audio/subtitle tracks.
4. **Validation & MediaStore Export**:
   - Staged files are verified: non-zero byte size, valid container headers, and expected format.
   - `OutputPublisher` publishes the final file to the user's destination directory (`Downloads/VRKA` or user-selected SAF tree), and commits the terminal `DONE` record, ordered outputs, reconciliation, and staging cleanup task atomically in Room (with `StateFlow` cache refreshed after commit).

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

VRKA Android 5.1.5 retains the independent, cryptographically authenticated self-updater targeting official GitHub releases.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   Application Self-Updater Architecture                  │
│                                                                          │
│  GitHub Releases API (Official Repo: MaverickRox/VRKA-Android)           │
│  │                                                                       │
│  ├── 1. Query Latest Release (24h Automatic Gate / Settings Manual Check)│
│  │                                                                       │
│  ├── 2. Download Machine-Readable Manifest & Detached Signature:         │
│  │      • VRKA-Android-vX.Y.Z.manifest.json                              │
│  │      • VRKA-Android-vX.Y.Z.manifest.json.asc                          │
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
│  │      • Enforce HTTPS, approved GitHub hosts, and MAX_REDIRECTS = 5    │
│  │                                                                       │
│  ├── 5. Two-Stage Installation Verification:                             │
│  │      • Stage 1 (Archive Integrity on .tmp): Zip archive validity,     │
│  │        SHA-256 digest match, package ID ("com.mvrk.vrka"),           │
│  │        versionCode, and signer certificate SHA-256.                   │
│  │      • Stage 2 (Staged File Integrity): Atomic rename to canonical    │
│  │        VRKA-Android-vX.Y.Z.apk, KEY_TARGET_VERSION_CODE persistence,  │
│  │        restore-time re-verification, non-debuggable flag, and URI.    │
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
- **Custom Runtime Controls (`YtDlpRuntimeManager`)**: Custom `yt-dlp` binaries require a verified SHA-256 pin for trusted activation, gate the verified status pill strictly on `verifiedAt > 0L`, warn and require confirmation before installing unverified or experimental binaries, repair corrupted local runtime files on re-install/update when the upstream binary hash matches the recorded digest while still rejecting silent upstream mutations, and reset active working state cleanly on activation failures.

---

## 8. User Interface & Liquid Glass Optics

The UI is built with **Jetpack Compose** using design tokens (`VrkaTokens`):
- **Liquid Glass Navigation**: Two independently rendered layers. The outer capsule applies a 0.75× blur and translucent veil. The active pill samples `LocalAppBackdrop` directly at full resolution.
- **AGSL RuntimeShader (API 33+)**: High-performance lens transform with 1.35× center magnification, 20-dp refraction height, 14-dp displacement, normal-map edge curvature, and chromatic aberration channel splitting. Fallback glass rendering is provided on API 26–32.
- **Directional Luminous Wake Progress**: 18-track directional streak with cached analytic teardrop glow optimized for AMOLED black backgrounds.
- **Resolution-Aware Size Estimation**: `SizeEstimator` runs a metadata-only yt-dlp preflight with the same `-f` selector and 60 fps sort as the transfer. `requested_formats` from that document are authoritative; the complete `formats` table still supplies every resolution chip. `MetadataRepository` caches each document by source, format selection, playlist range, runtime version, and a header digest. Browser Fallback clears the page-level estimate, estimates the selected candidate independently, and never blocks the transfer. Estimates that cannot be supported by metadata are shown as unavailable.
- **Recoverable Job Storage**: Room stores current jobs, attempts, ordered publication batches and outputs, migration evidence, and cleanup obligations. Job state, outputs, and terminal completion commit atomically. The v5.1.5 UI state cache refreshes only from committed rows (architecture introduced in v5.1.3 and remediated for queue dispatch and History deletion in v5.1.5); malformed rows stay in quarantine and appear as safe damaged History entries. The v5.1.2 JSON stores remain historical migration inputs.
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

---

## 10. Historical: Post-v5.1.2 Publication Integrity Remediation

This section records the intermediate publication-integrity work after v5.1.2. The v5.1.3 release now uses Room as the storage authority. The v5.1.2 tag, APK, and evidence remain unchanged.

1. **JobStore retention and durable verification**:
   - Nonterminal jobs and jobs pinned by unresolved journal transactions are never evicted by the 250-record history cap; only safely retained terminal history is bounded.
   - A manager-level persistence lock serializes snapshot selection, retention, payload construction, atomic replacement, and read-back verification. A stale snapshot cannot replace a newer terminal snapshot.
   - Terminal completion pins the completing job, persists it, re-reads the exact durable record, and only then retires that attempt's transaction IDs and removes staging. Failures leave journal, provider outputs, and staging recoverable.
2. **Publication journal integrity and transaction scope**:
   - The decoder requires the exact supported schema version and validates unique transaction IDs, job/output identity, timestamps, batch totals and indexes, the exact `.vrka_pending_${transactionId}_${filename}` relationship, provider URI structure, and phase/URI/status consistency.
   - Multiple attempts for one logical job are segmented by output index (newest transaction per index is current); older transactions are cleaned or reconciled independently. Reconciliation is always by exact transaction IDs, never by broad job ID.
   - Invalid records are preserved in bounded recovery snapshots. Valid jobs are isolated only when the corrupt records cannot belong to them; otherwise recovery fails closed.
3. **Provider verification and destructive-operation ownership**:
   - API 29+ finalization requires an existing row, the expected display name, and `IS_PENDING = 0`; a missing pending column is unverifiable, never success. API 26–28 uses name-only semantics.
   - Startup recovery re-inspects `FINALIZED`/`RECORDED` transactions before marking `DONE`, and a definitively missing output demotes the durable job rather than leaving a false completion.
   - Pending-object discovery is tri-state (`FOUND`, `DEFINITELY_ABSENT`, `UNKNOWN`). Deletion requires the single provider object carrying the transaction's unique pending name and structural membership in the declared MediaStore collection or SAF tree. Unknown or mismatched objects are never deleted.
   - Failed cleanup is durably recorded as `CLEANUP_PENDING` and retried at startup.
4. **Staging lifetime and cleanup**:
   - `publishAll()` retains its staging source. Staging is removed only after verified terminal persistence and reconciliation, and deletion failure is recorded in a bounded `StagingCleanupStore` retry list.
   - Staging is never deleted while an unresolved transaction or live job depends on the directory.
5. **Verification boundary**:
   - The original JVM tests used controlled provider and filesystem dependencies. Current release verification is recorded in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md). OEM provider differences and physical-device crash recovery remain boundaries.

---

## 11. v5.1.3 Unified Room Storage and Recovery

- Room is the durable authority for jobs, attempts, publication batches and outputs, migration
  records, and cleanup tasks. The in-memory StateFlow is a cache only.
- A job can become DONE only when one current attempt owns one complete ordered batch and Room
  commits the outputs and terminal state together. A stale callback cannot complete another
  attempt.
- The legacy importer preserves raw evidence for ambiguous journal rows. Synthetic migration
  identities do not become trusted historical attempt or batch identities.
- Cache refresh and paging keep malformed rows visible as damaged History entries. Quarantine
  evidence remains durable and private request data stays out of the placeholder.
- Cleanup obligations survive History deletion and database restart. Dead-letter tasks remain
  visible in Diagnostics and require an ownership-checked retry.
- Unsupported persisted states and enum values are quarantined and cannot trigger provider work.
- API 36 instrumentation covers MediaStore and SAF behavior against the emulator and a controlled
  DocumentsContract test provider. This is not evidence for every OEM provider or physical device.
