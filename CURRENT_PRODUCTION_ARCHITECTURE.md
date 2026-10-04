# VRKA Android 5.0 — Current Production Architecture

This is the source-audited architecture after the VRKA 5.0 correction pass. The verified APK, test, and lint results are in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

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

Components & Updates lists only yt-dlp and uBlock Origin. Rows follow the v4.5.3 pattern: component title, `Installed: v…`, state badge, and the result message with checked/latest version when available. The Stable/Nightly selector appears on the `yt-dlp` row. The section has no explanatory implementation text. The check button has deliberate spacing before its following separator.

yt-dlp's installed version is queried from `YoutubeDL.versionName(context)` after runtime initialization, with the manager's and library's persisted installed-version values as fallbacks. The app's `app/src/main/res/raw/ytdlp` is the effective bundled payload and was verified both in source and in the release APK as yt-dlp `2026.08.19` (Git head `594bd50c2c78ac432f81600d309fdc4e0a92d82c`). It is not hard-coded into the UI: an updated runtime displays its actual installed version. While the runtime version is unresolved, the row says `Resolving…`; if the installed version remains unavailable, an update check reports an error instead of claiming the component is current.

Check All Updates runs both component checks concurrently on IO. Each request has a six-second connect timeout, six-second read timeout, an eight-second total check deadline, and a 64 KiB response-body cap. The blocking `HttpURLConnection` is run behind a cancellable continuation; coroutine cancellation disconnects the active connection, so a server that slowly drips response bytes cannot keep the check job waiting forever. Cancellation propagates to the batch. Batch timeout/failure/cancellation settles any remaining checking rows and releases the single-flight mutex in `finally`; per-component failures remain visible and never become “Up to date.”

## Floating navbar glass

The navbar uses the existing Compose backdrop architecture to capture the live pager behind the fixed navigation surface. The outer capsule applies a downsampled (0.75×) background blur, translucent tint, and edge highlight, then exports that rendered capsule surface into a `LayerBackdrop`. The moving active pill samples that exported outer layer, applies a separate low-radius blur, and uses the vendored rounded-rectangle AGSL lens shader for a smooth center magnification (1.12×) plus edge refraction/displacement. The pill tint remains low-alpha so sampled imagery remains visible. The selected tab content also scales smoothly by up to 4.5% as it passes under the pill.

Every tab reports its measured left edge and width with `onGloballyPositioned`. The pill interpolates the adjacent measured bounds and applies the same four-dp inset on every edge; it is vertically centered using the existing 56-dp pill in the 64-dp capsule. The active index follows `pagerState.currentPage + currentPageOffsetFraction`, or the live nav drag position, so page swipes, taps, and nav drags share one continuous position. Label content receives four-dp internal horizontal padding; the existing font, 24-dp icon, 11-sp label, and icon-to-label gap are preserved.

Runtime shader lens distortion is supported on Android API 33 and later. API 31–32 retains backdrop blur with a translucent capsule but no AGSL distortion; API 26–30 uses a translucent surface fallback without live backdrop blur or lens distortion. The APK does not add a new dependency. The active sampling region is small and both glass layers render at 0.75× resolution to control GPU work while retaining continuous sampling as the pager scrolls.

The optical layering follows the material behaviors observed in SpotiFLAC-Mobile's floating bar: a blurred/tinted outer glass surface; a separate animated active lens that samples and alters the surface beneath it; stronger optical change in the selected area; and selected icon/label magnification. VRKA implements those behaviors with its own Compose/AGSL pipeline rather than Flutter code or Flutter dependencies. The reference sources are [main_shell.dart](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/screens/main_shell.dart), [mornye_bottom_bar.dart](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/widgets/mornye_bottom_bar.dart), and [mornye_chrome.dart](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/widgets/mornye_chrome.dart).
