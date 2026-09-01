# VRKA Android 4.0.1

VRKA Android 4.0.1 is a high-performance native ARM64 Android media downloader and passive web stream discovery suite, built for strict parity with VRKA Desktop Build 017.

## Architecture & Subsystems

- **Core Engine**: `yt-dlp` and `FFmpeg` pipeline executing in a foreground foreground service (`DownloadService`) with atomic state tracking and `JobStore` persistence.
- **Embedded Browser Subsystem**: Mozilla `GeckoView 153.0` bundled runtime with persistent cookies, SSL security validation, and popup/redirect protection.
- **Ad & Tracker Protection**: Built-in `uBlock Origin 1.74.0` WebExtension registered directly into GeckoView.
- **Media Stream Discovery**: Puemos-adapted HLS/DASH/direct media sniffer WebExtension communicating over native port messaging (`GeckoMediaBridge`).
- **Branding**: Official VRKA Desktop Build 017 canonical wolf artwork and typography.

## Navigation & Workflows

1. **Direct Download**: Paste direct media URLs into the Home hero input, configure quality/subtitles/trim/SponsorBlock, and tap *Add to queue*.
2. **Browser Discovery & Fallback**: Access browser sessions seamlessly for challenging sites, anti-bot checks, and multi-stream playlist detection with one-tap handoff to the core downloader.
3. **Queue & History**: Monitor active/queued jobs, inspect speeds and ETA, retry or open completed media in system players.
4. **Settings & Updaters**: Manage download paths (MediaStore / SAF), update yt-dlp, refresh uBlock Origin / Puemos rules, and customize AMOLED appearance.

## Requirements

- **JDK**: JDK 17 or newer (bundled with Android Studio JBR)
- **Target OS**: Android 8.0+ (API 26 to API 36+), optimized for `arm64-v8a`
- **Build Tools**: Android SDK Platform 36.1 and Gradle 8.13

## Build Instructions

```powershell
# Compile debug APK and run unit tests
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# Run on-device instrumented downloader test
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Release Artifacts

Structured release bundles for each version are available under:
`release/4.0.1/` containing `APK/`, `SOURCE/`, `CHECKSUMS/`, `SECURITY/`, `TESTS/`, and `METADATA/`.
