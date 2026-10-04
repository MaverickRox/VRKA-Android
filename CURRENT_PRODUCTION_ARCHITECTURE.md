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

`InstalledComponentVersionReader` reads `yt_dlp/version.py` dynamically from the active installed yt-dlp zipapp, initialized runtime `versionName()`, stored preferences, or directly from the active bundled `R.raw.ytdlp` resource by extracting `yt_dlp/version.py` through `ZipFile` (skipping the zipapp shebang line without hardcoding). Persisted manager/library versions serve as secondary fallbacks. The UI displays the resolved installed version, while remote latest-version results remain a separate value. During resolution it shows `Resolving…`, settling cleanly to the installed version without aborting or throwing false `INSTALLED_VERSION_UNAVAILABLE` exceptions.

Check All Updates runs both component checks concurrently on IO. Each request has a six-second connect timeout, six-second read timeout, an eight-second total check deadline, and a 64 KiB response-body cap. The earlier implementation performed blocking `HttpURLConnection` body reads inside coroutine timeouts. Coroutine timeout cannot interrupt a blocked socket read, and the socket read timeout resets as bytes arrive, so a server slowly dripping an unbounded response could keep the operation pending. The corrected request uses a cancellable continuation that disconnects the active connection, bounds response bytes, and enforces the per-component deadline. Cancellation propagates to the batch. Batch timeout/failure/cancellation settles any remaining checking rows and releases the single-flight guard in `finally`; per-component failures remain visible and never become “Up to date.”

## Navbar backdrop and active-pill optics

The navbar has two independently rendered layers. The outer capsule samples the live pager backdrop with the existing Compose backdrop API, applies a 0.75× blur, translucent veil, and edge highlight. The moving active pill samples `LocalAppBackdrop` directly at full resolution, so it transforms page pixels behind the pill rather than sampling the already tinted capsule. Its clipped rounded-rectangle layer applies a subtle blur, low-alpha glass sheen (`Color.White.copy(alpha = 0.05f)` / `0.08f`), and specular rim highlight, completely eliminating dark bubble tinting.

On API 33+, the active pill executes an AGSL `RuntimeShader` lens transform:
- 1.35× center magnification
- 20-dp refraction height and 14-dp refraction displacement around the rounded pill border
- Depth effect with normal-map edge curvature
- Chromatic dispersion / spectral aberration splitting red, green, and blue channels across the refraction boundary
API 31–32 retain live backdrop blur and tint; API 26–30 use a translucent glass fallback. No new Flutter or external dependencies are added.

The active indicator uses slot-based geometry (`slotWidthPx - 2 * slotInsetPx`) with a fixed height of 56 dp, ensuring uniform pill dimensions and identical corner radii across all destinations (e.g., "Queue" has the exact same pill geometry as "Download" and "Settings"). The indicator is positioned via layout `Modifier.offset` rather than `graphicsLayer.translationX`, ensuring `DrawBackdropNode` and `LayoutCoordinates` accurately sample the live backdrop directly beneath the active destination tab. The indicator smoothly interpolates position during pager swipes and nav drags.

These are the relevant reference behaviors studied in SpotiFLAC-Mobile: a separate active optical pill sampling the page backdrop, center magnification with edge refraction, and a moving navigation indicator synchronized to page selection. The source uses the `liquid_glass_easy` Flutter package; VRKA reproduces this natively in Compose and AGSL without copying Flutter code. References: [main_shell.dart](https://github.com/spotiflacapp/SpotiFLAC-Mobile/blob/main/lib/screens/main_shell.dart), [liquid_glass_easy 4.3.2](https://pub.dev/packages/liquid_glass_easy/versions/4.3.2).
