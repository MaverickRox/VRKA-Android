# Features & Settings Guide

## Video & Audio Acquisition (`yt-dlp` + `FFmpeg`)

- **Video Resolution & Frame Rate:** Select **Best available** or a target cap (`2160p 4K`, `1440p`, `1080p`, `720p`, `480p`, `360p`), plus **Prefer 60 FPS**.
- **Audio Extraction:** Choose **MP3** (selectable `128`–`320 kbps`), **Opus** (remuxed where native Opus exists, transcoded when needed), or uncompressed 16-bit PCM **WAV** (note: embedded cover thumbnails apply to MP3/Opus, not WAV).
- **Trim, Subtitles & Playlists:** Expand **Advanced Options** to specify a start/end trim range (`Download Sections`), embed subtitles or auto-captions on normal `yt-dlp` transfers, or download a bounded playlist range.
- **Size Estimation Accuracy:**
  - Resolution chips show `~` approximate local estimates computed from the fetched format table so VRKA does not spawn multiple extra network requests on initial URL load.
  - The pre-download **Confirm download** modal runs metadata preflight using the exact `-f` format selector and 60 FPS sort used by the transfer. When a source omits byte sizes, VRKA displays `"Unavailable"` rather than guessing.
- **H.264 Compatibility Mode:** Off by default. Enable in Settings if your device player, gallery, or messaging app rejects VP9/AV1 tracks; FFmpeg re-encodes non-H.264 local video via `libx264` before publishing.

## Queue, History & Storage

- **Sequential vs Concurrent Downloads:** Sequential (`1` job at a time) is enabled by default for thermal and bandwidth predictability. Concurrent mode (`2–4` simultaneous jobs) can be enabled in **Settings -> Downloads & Concurrency**.
- **Crash-Safe Room Storage:** All queue jobs, retry attempts, publication transactions, and staging cleanup tasks are managed transactionally in SQLite/Room (`VrkaDatabase`). Completed files appear in **History** only after provider verification completes.

## Browser Fallback (`GeckoView`)

When a direct `yt-dlp` attempt fails with an eligible anti-bot or client-side player failure:

1. VRKA launches a task-scoped private Mozilla **GeckoView** browser session.
2. Bundled **uBlock Origin** filters intrusive ads/popups locally, while the first-party **VRKA Media Detector** WebExtension observes stream requests and `<video>`/`<audio>` candidates.
3. Discovered candidates are ranked in Kotlin, estimated independently, and downloaded through the normal `yt-dlp` + `FFmpeg` pipeline once you choose a candidate.

*Note: Browser Fallback transfers through `yt-dlp`; subtitle flags apply to direct downloads only, and DRM/login/payment/CAPTCHA gates are never bypassed.*

## Advanced Settings & Security Controls

### Custom HTTP Headers
- Add custom `Referer`, `Origin`, or arbitrary request headers on normal requests.
- VRKA validates header names/values against RFC 7230 and blocks CRLF injection.
- Credential-bearing headers (`Authorization`, `Cookie`, tokens) are redacted as `[REDACTED]` in logs, diagnostics, and stored job summaries.

### Custom `yt-dlp` Command Templates
- Create reusable `argv` templates for normal downloads (e.g., `--limit-rate 2M`, `--no-playlist`). Templates are tokenized directly without a shell.
- **Blocked options:** Execution hooks (`--exec*`, `--external-downloader*`, `--downloader*`), output path overrides (`-o`, `-P`), config/plugin/batch files, proxy/insecure SSL overrides (`--proxy`, `--no-check-certificates`, `--prefer-insecure`), and credential headers (`Cookie`, `Authorization`, `-H...`) are blocked both when saving templates and again when building `yt-dlp` requests.
- Allowed custom template headers are limited to non-credential metadata headers (`Accept`, `Accept-Language`, `Origin`, `Referer`, `User-Agent`, `X-Requested-With`).

### Custom `yt-dlp` Runtimes (Advanced)
- By default, VRKA uses the **stock** bundled `yt-dlp` binary (`app/src/main/res/raw/ytdlp`), which receives official OpenPGP-verified updates from **Component Updates**.
- Advanced users can add a GitHub `owner/repo` fork publishing a Python zipapp asset named `yt-dlp`.
- Before installation or activation, VRKA displays an **Untrusted custom runtime** warning dialog with the repository, version, and SHA-256 checksum, verifies downloaded size and CPython `--version` execution in staging, and pins the SHA-256 locally. Every subsequent activation or app startup re-verifies the binary's SHA-256 and automatically reverts to **stock** if missing or altered.

### Language & Appearance
- Supports **14 languages** (`ar` with full Right-to-Left layout, `bn`, `zh-rCN`, `en`, `fr`, `de`, `gu`, `hi`, `kn`, `pt-rBR`, `pt-rPT`, `es`, `ta`, `te`) plus System Default.
- Toggle between **VRKA Font** (`Space Mono`) and **System Font** (`Roboto`), and Dark/Light themes with adaptive Liquid Glass navigation bar rendering (AGSL `RuntimeShader` on API 33+).

### In-App Self-Updater & Diagnostics
- **App Updates:** Checks GitHub Releases once every 24 hours (or immediately via **Check for Updates**), verifies an OpenPGP-signed release manifest against the pinned RSA-4096 trust anchor (`0xD519380BEBA1B9F7`), checks version/versionCode monotonicity and expiration, and verifies APK SHA-256, package ID, target version code, and signing certificate before invoking Android's `PackageInstaller`.
- **On-Device Diagnostics:** Failed jobs retain a sanitized trace and optional local failure URLs; successful jobs clear URL context. Exporting diagnostics never sends data over the network unless you share the copied text yourself, and warns before an explicit opt-in for full original failure URLs.
