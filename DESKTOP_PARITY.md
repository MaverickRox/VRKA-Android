# VRKA Android 4.0.1 — Desktop Build 017 Parity Matrix

## Component Parity Analysis

| Feature / Domain | VRKA Desktop Build 017 | VRKA Android 4.0.1 | Parity Status |
| :--- | :--- | :--- | :--- |
| **Download Pipeline** | `yt-dlp` + `FFmpeg` with process tree management | Native `youtubedl-android` + `ffmpeg-android` foreground service | Parity |
| **Direct URL Download** | URL input with Paste button & format switches | URL input with Paste button & format switches | Parity |
| **Queue & History** | Active/queued task cards with live progress, retry, cancel | Persistent `JobStore` active queue and terminal history screens | Parity |
| **Browser Subsystem** | Protected WebView2 browser window with uBOL | Mozilla `GeckoView 153.0` bundled runtime with built-in uBlock Origin | Parity |
| **Media Detection** | `puemos/hls-downloader` v5.5.0 MV3 extension | `media-detector@vrka.mvrk.com` WebExtension + Native Port Bridge | Parity |
| **Ad & Popup Filtering** | `uBlock Origin Lite` | Official `uBlock Origin 1.74.0` | Parity |
| **Component Updates** | Individual updaters for `yt-dlp` and `Puemos` | Dedicated update controls for `yt-dlp`, `uBlock`, and `Puemos` | Parity |
| **Branding & Assets** | Canonical `vrka-wolf-1024.png` / `vrka-wolf-256.png` | Mipmap adaptive icons, splash icon, About screen logo from 1024px master | Parity |
