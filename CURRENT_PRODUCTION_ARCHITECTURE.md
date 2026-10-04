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

`InstalledComponentVersionReader` reads `yt_dlp/version.py` directly from the active installed yt-dlp zipapp and parses `__version__`. On first use it initializes the bundled runtime, whose embedded APK payload was verified as yt-dlp `2026.08.19` (source Git head `594bd50c2c78ac432f81600d309fdc4e0a92d82c`). Persisted manager/library versions are fallbacks. The UI displays the resolved installed version, while remote latest-version results remain a separate value. During resolution it shows `Resolving…`; a genuinely unavailable installed version produces an error rather than a false “Up to date” result.

Check All Updates runs both component checks concurrently on IO. Each request has a six-second connect timeout, six-second read timeout, an eight-second total check deadline, and a 64 KiB response-body cap. The earlier implementation performed blocking `HttpURLConnection` body reads inside coroutine timeouts. Coroutine timeout cannot interrupt a blocked socket read, and the socket read timeout resets as bytes arrive, so a server slowly dripping an unbounded response could keep the operation pending. The corrected request uses a cancellable continuation that disconnects the active connection, bounds response bytes, and enforces the per-component deadline. Cancellation propagates to the batch. Batch timeout/failure/cancellation settles any remaining checking rows and releases the single-flight guard in `finally`; per-component failures remain visible and never become “Up to date.”

## Navbar backdrop and active-pill optics

The navbar has two independently rendered layers. The outer capsule samples the live pager backdrop with the existing Compose backdrop API, applies a 0.75× blur, translucent veil, and edge highlight. The moving active pill samples `LocalAppBackdrop` directly at full resolution, so it transforms page pixels behind the pill rather than sampling the already tinted capsule. Its clipped rounded-rectangle layer applies a subtle blur and low-alpha tint, then a RuntimeShader/AGSL effect on API 33+.

The active shader performs a coordinate-space lens transform: it samples the backdrop through a 1.22× center magnification and bends sample coordinates through a 16-dp refraction band with up to 10-dp displacement around the rounded edge. The border/highlight adds an optical rim. API 31–32 keep live backdrop blur and tint but do not get this shader transform; API 26–30 use a translucent surface fallback. The code therefore contains real backdrop-sampled magnification/refraction on API 33+, but whether its strength reads clearly on the maintainer's display remains a physical-review item. No new Flutter or Android dependency was added.

Each navigation item measures its slot and icon-plus-label column using `onGloballyPositioned`. The indicator is calculated from those measured bounds: it centers on the measured content column, grows by a four-dp clearance on each side, and stays within a four-dp slot inset. It interpolates its left edge and width between measured neighboring items, avoiding fixed X offsets. Vertical centering follows the measured nav-item bounds. Horizontal item padding is now eight dp, preserving the existing font and 24-dp icon while adding space between labels and the pill. The indicator follows pager fractional position during page swipes and its drag fraction during nav drags; taps, drags, and pager swipes therefore share the same measured geometry. Selected content retains its subtle 4.5% scale change.

These are the relevant reference behaviors studied in SpotiFLAC-Mobile: a separate active optical pill sampling the page backdrop, center magnification with edge refraction, and a moving navigation indicator synchronized to page selection. The source uses the `liquid_glass_easy` Flutter package; VRKA does not copy Flutter code. References: [main_shell.dart](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/screens/main_shell.dart), [liquid_glass_easy 4.3.2](https://pub.dev/packages/liquid_glass_easy/versions/4.3.2).
