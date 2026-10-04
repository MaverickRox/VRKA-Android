# VRKA Android 5.0 — Current Production Architecture

This is the source-audited architecture after the final hardening changes. The verified APK, test, and lint results are in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## Normal downloads

The UI submits a request to `VrkaDownloadManager`. `DownloadRequestFactory` creates the yt-dlp invocation. yt-dlp performs the transfer; FFmpeg performs local processing where requested. VRKA validates the staged output before `OutputPublisher` publishes it and updates history.

## Browser Fallback

An eligible yt-dlp failure can enter Browser Fallback. GeckoView navigates the public page in a task-scoped private session. The VRKA media-detector WebExtension reports candidates to the native bridge; the candidate store ranks and stabilizes them. VRKA probes a selected candidate with yt-dlp, then uses the standard yt-dlp transfer path. Local FFmpeg processing, output validation, and publication follow as needed. Browser Fallback does not use an app-owned HLS/DASH transfer engine and does not request or publish subtitles.

## Component ownership

- **GeckoView:** browser runtime, navigation, and extension host.
- **VRKA media detector:** passive candidate discovery and native bridge messages.
- **uBlock Origin:** browser request filtering.
- **yt-dlp:** normal and Browser Fallback transfers; component update support.
- **FFmpeg:** local processing, probing, validation, and optional H.264 conversion.
- **Media3 / remote FFmpeg router:** code remains in the source but has no active `VrkaDownloadManager` call site.
- **N_m3u8DL-RE:** capability gate remains disabled.
- **Retired Puemos extension:** no shipped asset, runtime registration, component row, or updater path remains. One-time migration code removes the previous extension and its old update state from existing installs.

No dormant transfer route was reconnected by the UI or component cleanup changes.

## Custom yt-dlp templates and headers

Templates are parsed as argument lists and passed to yt-dlp without a shell. VRKA retains control of the public URL, staging path, and publication. The policy rejects shell hooks and unsafe path, config, credential, proxy, external-downloader, and extra-URL arguments. Browser Fallback clears selected templates and typed custom arguments/headers and uses the standard request builder.

## User-facing component state

Components & Updates lists only yt-dlp and uBlock Origin. The yt-dlp installed version is queried from the initialized youtubedl-android runtime (with the library's persisted installed-version data as a fallback); it is not hard-coded. Unknown stays unknown. “Update available” is distinct from the installed version. Stable/Nightly is the yt-dlp channel selector; Check All Updates checks the two active update-managed components.
