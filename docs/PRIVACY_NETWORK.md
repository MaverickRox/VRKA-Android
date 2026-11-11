# Privacy and Network Transparency

VRKA Android does not include an analytics, advertising, crash-reporting, or telemetry SDK. It
does not upload History, queue state, diagnostics, device identifiers, or account credentials to
an application-controlled backend. This is a narrower, source-verifiable claim than "zero
tracking"; the network paths below still exist and are disclosed here.

## Application-controlled requests

| Destination | Initiator | Trigger | Data sent | Persistent identifier |
|---|---|---|---|---|
| `api.github.com/repos/MaverickRox/VRKA-Android/releases/latest` | `AppUpdateManager` | Automatic once per 24 h at app start, or manual Settings check | HTTPS request, `User-Agent: VRKA-Android-AppUpdater`; GitHub receives normal network metadata | No app-generated account or device ID |
| Approved GitHub release asset hosts (`github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, `raw.githubusercontent.com`) | `AppUpdateManager` / `AppUpdateDownloadWorker` | User accepts an available update | HTTPS download of the signed manifest, signature, and APK; `User-Agent: VRKA-Android-AppUpdater` | No app-generated ID |
| `api.github.com/repos/gorhill/uBlock/releases/latest` and GitHub release assets | `ComponentUpdateManager` / `SecureComponentUpdater` | Automatic startup check with 24 h gate, or manual Components check | HTTPS request/User-Agent `VRKA-Android-Updater` or `VRKA-Android-SecureUpdater`; component version checks | No app-generated ID |
| `api.github.com/repos/yt-dlp/*/releases/latest` and GitHub release assets | Component updater / custom runtime manager | Automatic/manual component check, or user-added custom runtime | HTTPS request for release metadata and the selected `yt-dlp` zipapp | No app-generated ID |
| User-supplied media/provider URLs | `yt-dlp`, `MediaHttpTransport`, FFmpeg media assembly | User starts a download | URL, selected format, request headers/cookies required by that site, and transfer bytes | Only data required by the user's request; no analytics ID |
| Websites loaded in GeckoView Browser Fallback | GeckoView private session, uBlock Origin, VRKA Media Detector | User initiates Browser Fallback | Normal browser HTTP(S) traffic, cookies/site storage for that private session, page requests | Site-specific cookies; no VRKA account ID |
| User-entered custom runtime repository | `YtDlpRuntimeManager` through `SecureComponentUpdater.HttpTransport` | User adds/updates a custom runtime | GitHub API request and download of the `yt-dlp` asset | No app-generated ID |

The GitHub requests necessarily reveal the user's IP address and normal HTTP metadata to GitHub,
as any HTTPS client does. VRKA does not add an advertising ID, Android ID, account token, or
device fingerprint. Update checks can occur automatically at startup; they can be avoided by
using a network-restricted device, but the application does not provide a separate "disable
update checks" switch. Manual checks are initiated from Settings.

## Local and diagnostic data

- Source URLs may be retained in failed-download diagnostics for reproducibility. Credential-like
  query values, signed CDN URLs, source-userinfo, cookies, and authorization headers are redacted.
  Successful downloads do not add a source URL.
- Diagnostic export is local and requires explicit user action. The export screen warns that a
  full original source URL can be included only with an explicit opt-in.
- History, queue metadata, Room migration records, and cleanup tasks are stored on-device. They
  are not uploaded by VRKA.
- Custom runtime binaries run as native code with the application's privileges; this is a local
  security boundary, not a network telemetry path.

## Component and browser boundaries

- yt-dlp and its site traffic are user-requested media acquisition, not application telemetry.
- Browser Fallback runs a task-scoped private GeckoView session. It loads the user's selected page
  and website resources; uBlock Origin filters requests locally, and the VRKA Media Detector
  observes requests locally. Website traffic is governed by the site's own privacy policy.
- No Discord integration, scrobbling service, lyrics provider, crash reporter, or application
  backend is present in the v5.1.4 (or historical v5.1.3) source.

## Source references

`AppUpdateManager`, `AppUpdateDownloadWorker`, `ComponentUpdateManager`, `ComponentUpdateWorker`,
`SecureComponentUpdater`, `YtDlpRuntimeManager`, `CustomRuntimeSource`, `VrkaDownloadManager`,
`OutputPublisher`, `MediaHttpTransport`, `GeckoRuntimeManager`, and `DiagnosticStore` are the
relevant source classes. Network host allowlists and URL validation are enforced in code; this
document does not claim that third-party sites or GitHub handle data identically.
