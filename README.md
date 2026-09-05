# VRKA Android

**VRKA Android** is a native, high-performance Android media downloader and passive web stream discovery application. Built with Jetpack Compose and Kotlin coroutines, it provides a dedicated mobile interface for downloading web media, featuring background queue orchestration, integrated GeckoView browser fallback with built-in ad/tracker blocking, and a bespoke Liquid Glass design system.

---

## Origin

> [!NOTE]
> **Mobile Port**: VRKA Android is an independent native Android application developed as a port of the original desktop application [**VRKA Desktop**](https://github.com/MaverickRox/VRKA).
>
> - **Original Desktop Application**: [https://github.com/MaverickRox/VRKA](https://github.com/MaverickRox/VRKA)
> - **Android Mobile Port**: [https://github.com/MaverickRox/VRKA-Android](https://github.com/MaverickRox/VRKA-Android)

---

## Features

- **Direct Extraction & Download**: Powered by `yt-dlp` and `FFmpeg`, supporting video and audio extraction, quality selection, format conversion, audio extraction, subtitles, and SponsorBlock integration.
- **Background Orchestration**: Resilient foreground `DownloadService` with atomic `JobStore` persistence, notification progress tracking, pause/resume, and sequential queue processing to avoid thermal throttling.
- **Passive Browser Fallback**: Embedded Mozilla `GeckoView` session automatically engages when direct extraction fails due to anti-bot challenges or client-side JavaScript requirements.
- **Built-in Content Filtering**: Integrated `uBlock Origin` WebExtension blocks ads, trackers, and popup redirects inside fallback browser sessions.
- **Stream Discovery**: Integrated `Puemos` HLS/DASH packet inspection listens to browser network traffic to automatically capture and rank media stream candidates.
- **Components & Updates**: In-app management and updates for runtime components (`yt-dlp`), supporting both Stable and Nightly channels with rate-limit protection and binary validation.
- **Liquid Glass Interface**: Floating bottom navigation bar utilizing hardware-accelerated RenderEffect backdrop blurring across both AMOLED Dark and Light modes, with exact concentric capsule geometry.

---

## Architecture

VRKA Android is structured into distinct, loosely coupled architectural layers:

```
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│   (HomeScreen, JobsScreen, HistoryScreen, Settings)   │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│                 VrkaDownloadManager                    │
│      (Single-flight Queue, State Transitions, Events)  │
└────────────┬─────────────────────────────┬─────────────┘
             │                             │
┌────────────▼────────────┐   ┌────────────▼─────────────┐
│      Direct Engine      │   │     Fallback Engine      │
│  (yt-dlp CLI / FFmpeg)  │   │   (GeckoView Runtime)    │
└─────────────────────────┘   └────────────┬─────────────┘
                                           │
                              ┌────────────▼─────────────┐
                              │  GeckoMediaBridge        │
                              │  - uBlock Origin         │
                              │  - Puemos HLS Detector   │
                              │  - Candidate Ranker      │
                              └──────────────────────────┘
```

### 1. UI Layer (Compose)
- **Design System**: Strict design tokens (`VrkaTokens`) supporting true AMOLED Dark (`#000000`) and refined Light modes.
- **Floating Navigation Bar**: Closed-form mathematical concentric geometry ($H_{outer}=66	ext{dp}$, $R_{outer}=33	ext{dp}$, $C=4.5	ext{dp}$, $H_{inner}=57	ext{dp}$, $R_{inner}=28.5	ext{dp}$) with critically damped spring animations and full-resolution hardware backdrop diffusion.

### 2. Download Pipeline
- **Direct Acquisition**: Directly invokes `yt-dlp` with targeted configuration options for format, resolution, audio extraction, and metadata.
- **Failure Classification**: On failure, `FailureClassifier` inspects standard error output and HTTP status codes to classify failures (e.g., bot detection, JS challenge, signature extraction) and determines whether the URL is eligible for browser fallback.

### 3. Browser Fallback Subsystem
- **GeckoView Runtime**: Bundled Mozilla GeckoView ARM64 engine instantiated on-demand.
- **Ad/Tracker Blocking**: Pre-packaged `uBlock Origin` extension suppresses ad-network scripts, popups, and tracking beacons.
- **Passive Sniffing**: The `Puemos` WebExtension intercepts network requests, analyzes MIME types, playlist manifests (`.m3u8`, `.mpd`), and video segments, reporting them to `GeckoMediaBridge`.
- **Candidate Ranking**: Evaluates candidates by stream type, resolution, and content length, selecting the optimal stream and extracting authenticated session cookies/headers for handoff back to the downloader.

### 4. Components & Updates Subsystem
- **Channel Switching**: Allows users to select between `Stable` and `Nightly` release channels for `yt-dlp`.
- **Concurrency & Rate Limiting**: Built-in 2-second debounce, 5-minute cache TTL, single-flight request guards, and structured HTTP 403 rate-limit reporting.
- **Post-Install Verification**: Confirms executable binary execution and version output before marking updates successful.

---

## Requirements

- **Device Architecture**: `arm64-v8a`
- **Minimum OS**: Android 8.0 (API level 26)
- **Target OS**: Android 16 (API level 36)
- **JDK**: Java 17 or Java 21 (e.g., Android Studio JBR)
- **Android SDK**: Build Tools 36.1+

---

## Build Instructions

### Prerequisites
Set your `JAVA_HOME` environment variable to a valid JDK 17+ installation:
```powershell
$env:JAVA_HOME = "F:\Android\Android Studio\jbr"
```

### Compiling & Running Unit Tests
```powershell
# Run unit test suite
.\gradlew.bat testDebugUnitTest --offline

# Assemble debug APK
.\gradlew.bat assembleDebug --offline
```

### Assembling Release APK
```powershell
.\gradlew.bat assembleRelease --offline
```
The compiled APK will be located at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## Installation

1. Download the latest `VRKA-Android-v4.0.0.apk` from the [Releases](https://github.com/MaverickRox/VRKA-Android/releases) page.
2. Verify the SHA-256 checksum against `SHA256SUMS`.
3. Sideload the APK onto an `arm64-v8a` Android device running Android 8.0 or later:
   ```bash
   adb install -r VRKA-Android-v4.0.0.apk
   ```

---

## Third-Party Notices & Attribution

VRKA Android incorporates open-source libraries and components:
- **yt-dlp**: Unlicense / Public Domain ([https://github.com/yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp))
- **Mozilla GeckoView**: Mozilla Public License 2.0 (MPL-2.0) ([https://wiki.mozilla.org/Mobile/GeckoView](https://wiki.mozilla.org/Mobile/GeckoView))
- **uBlock Origin**: GNU General Public License v3.0 (GPL-3.0) ([https://github.com/gorhill/uBlock](https://github.com/gorhill/uBlock))
- **Puemos HLS/DASH Detector**: Integrated WebExtension stream sniffing logic
- **Space Mono Font**: SIL Open Font License 1.1 (OFL-1.1)

---

## License

VRKA Android is free software licensed under the [GNU General Public License v3.0](LICENSE).
See the [LICENSE](LICENSE) file for details.
