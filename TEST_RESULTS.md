# Test results

## Release candidate

- Version: VRKA Android 1.0.0.
- Package: `com.mvrk.vrka`.
- ABI: `arm64-v8a`.
- Candidate APK: `release/VRKA-Android-1.0.0-arm64-v8a.apk`.
- Candidate SHA-256: `B3E19AAAD9E997679A8B9A91286E10DF3E1343E617AB9082AE1A36D8459BADAF`.
- Candidate size: 55,400,698 bytes.
- Device: CPH2447, Android API 36, serial `897f6ce5`.
- Runtime: yt-dlp `2026.07.04` with youtubedl-android 0.18.1 and its FFmpeg package.

The candidate build passed JUnit, Debug Kotlin compile, Release Kotlin compile, lint-vital, R8, resource shrinking, signing, and release assembly.
The candidate installed successfully. The final device smoke tests passed.

## Physical media tests

| ID | Test | Result | Evidence |
| --- | --- | --- | --- |
| A | YouTube Best available | PASS | Accepted APK `46765E78...`. The normal extractor completed. FFprobe found 1920x1080 AV1 video and Opus audio in MP4. |
| B | YouTube 2160p cap on a 1080p source | PASS | Accepted APK `46765E78...`. The output stayed 1920x1080. It contained AV1 video and Opus audio. |
| C | Instagram direct extraction | PASS | Accepted APK `46765E78...`. The supplied Reel completed without Browser fallback. FFprobe found VP9 video and AAC audio. |
| D | Twitter/X | PASS | The user accepted the physical result before this pass. The shared path did not change. |
| E | MP3 320 kbps | PASS | Physical output completed and FFprobe identified a playable MP3. |
| F | MP3 192 kbps | PASS | Physical output completed and FFprobe identified a playable MP3. |
| G | WAV | PASS | Physical output completed and FFprobe identified a playable WAV. |
| H | FLAC | PASS | Physical output completed and FFprobe identified a playable FLAC. |
| I | Trim | NOT RUN ON DEVICE | JUnit verifies the yt-dlp trim section and keyframe option. |
| J | Playlist and range | NOT RUN ON DEVICE | JUnit verifies inclusive playlist start/end command construction. |
| K | Subtitles | NOT RUN ON DEVICE | JUnit verifies subtitle, automatic-caption, language, and embed options. |
| L | SponsorBlock | NOT RUN ON DEVICE | JUnit verifies the structured SponsorBlock category option. |
| M | Cancel | PASS | An active physical transfer reached Cancelled. No final output was published. |
| N | Retry | PASS | A controlled failed/cancelled job created a new queued attempt and completed normally. |
| O | Idle lifecycle | PASS | The release UI returned after Home without an unusable surface. |
| P | Active download lifecycle | PASS | The foreground service continued. The UI reconnected to progress after return. |
| Q | Browser lifecycle | PASS | Final APK `72DA74B1...`. Example Domain remained visible after Home and return. Back returned to Queue. |
| R | Long filename regression | PASS | JUnit used a 2,000-character signed token. Staging and final names stayed bounded. |
| S | Eporner | PASS | APK `E913CCA1...` used normal yt-dlp. The job completed without Browser fallback. The MP4 is 1,348,190,582 bytes. VLC opened it in the video player. |
| T | HentaiHaven | PASS | The user accepted the correct playable 1080p file. This test was not repeated. |
| U | Appearance | PASS | Final APK `B3E19AAA...`. Light, Dark, AMOLED off, and AMOLED on worked. The two settings persisted after process recreation. |

## Browser diagnosis

The VRKA fallback uses Android System WebView 150.0.7871.124 on the test device.
JavaScript and DOM storage are enabled.
File and content access are disabled.
First-party and third-party cookies are enabled for fallback compatibility.
Mixed HTTP content remains disabled.
Safe Browsing keeps the WebView default.
The WebView does not expose a JavaScript interface to native application APIs.
The document observer can only send media URL text to the application.
The request interceptor blocks only confident junk hosts or paths.
The interceptor protects media, challenge, login, verification, and player paths.

One earlier Eporner run timed out in both yt-dlp and WebView.
The phone/router resolver returned unreachable address `49.44.79.236` during that run.
The Windows resolver returned Eporner's `94.75.220.x` pool.
Android Firefox loaded the page and player immediately.
Android Chromium-based Brave failed its renderer during the comparison.
The Android resolver later returned the real Eporner address pool.
The unchanged VRKA APK then downloaded the exact URL directly and completed it.

The Browser screen reconstructs its WebView after Activity pause and resume.
This fixes the whole-task return symptom in the generic lifecycle test.
A separate main-frame failure now shows a bounded error state.
The error state has Retry and Close actions.
The loading surface stops after 20 seconds.

## Automated tests

JUnit passed these checks:

- Video selectors require a video stream.
- Resolution values are native yt-dlp caps.
- The optional 60 FPS sort keeps resolution first.
- Audio formats and MP3 bitrates map to structured options.
- Playlist, subtitle, trim, metadata, thumbnail, and SponsorBlock options remain structured.
- Video custom arguments cannot force audio-only output.
- Staging and published names stay bounded.
- Browser session headers use the allowlist.

## Final release smoke test

- PASS: `adb install -r` installed the final APK.
- PASS: Package data and History remained present.
- PASS: The app launched with the expected package, version, target SDK, and ARM64 ABI.
- PASS: The advanced control surface opened and showed the new structured options.
- PASS: A `.invalid` main frame showed `ERR_NAME_NOT_RESOLVED` with Retry and Close.
- PASS: Retry reconstructed the WebView. Back closed the session and returned to an empty Queue.
- PASS: Five bottom-navigation cycles rendered 788 frames with 12 janky frames (1.52%).
- PASS: Frame percentiles were p50 5 ms, p90 7 ms, p95 7 ms, and p99 19 ms.
- PASS: Hot foreground return took 39 ms TotalTime and 43 ms WaitTime.
- PASS: Idle application PSS was 116,353 KB after the navigation run.

## Tests not repeated

Twitter/X was not repeated because the shared default path did not change.
HentaiHaven was not repeated because media observation and handoff ordering did not change.
The final report does not convert an unrun physical test into a pass.

## Final product polish pass

- PASS: JUnit, lint-vital, R8, resource shrinking, signing validation, and release assembly completed.
- PASS: The exact final APK installed on device `897f6ce5`.
- PASS: The app reported version 1.0.0, version code 1, target SDK 36, and ARM64 ABI.
- PASS: Light, Dark charcoal, and Dark with AMOLED black rendered correctly.
- PASS: AMOLED remained checked but disabled and de-emphasized in Light.
- PASS: Dark and AMOLED settings restored after force-stop and relaunch.
- PASS: The four bottom destinations use matching vector icons and retain their navigation behavior.
- PASS: The Home plan shows output, quality, FPS preference, and enabled extras without clipping at the tested large font scale.
- PASS: Queue and History rows use consistent metadata, status pills, actions, and bounded error copy.
- PASS: History persisted across theme changes, APK replacement, and process recreation.
- PASS: One active job showed the compact strip above navigation.
- PASS: The foreground notification showed VRKA branding, current phase, media configuration, and progress treatment.
- NOTE: The final rebuild changed only Home plan wrapping and Browser-closed wording after the active-strip and notification capture.

## Final security gate

- PASS: Gradle resolved 147 unique Maven coordinates for `releaseRuntimeClasspath`.
- PASS: The OSV API scanned all 147 coordinates on 2026-07-29.
- REVIEW: OSV returned 22 version matches in Jackson 2.11.1, Commons IO 2.5, and Commons Compress 1.12.
- PASS: Bytecode and final R8-output review found no reachable P0 or P1 path for those advisories.
- PASS: The focused `DownloadRequestFactoryTest` suite passed.
- PASS: `apkanalyzer` reported `debuggable=false`.
- PASS: The final manifest, exported surfaces, permissions, WebView settings, and packaged files were inspected.
- PASS: Secret-pattern and sensitive-filename scans found no credential or signing material in the final source tree or source ZIP.
- REVIEW P2: The library updater relies on GitHub TLS. It does not verify upstream checksum/signature assets and does not replace the runtime atomically.
- REVIEW P2: Android System WebView can retain cookies in app-private WebView storage. VRKA does not persist them in JobStore or expose them in History, notifications, or logs.
- PASS: `THIRD_PARTY_NOTICES.md` covers the final dependency and asset set.
- PASS: No application source, resource, manifest, dependency, or APK byte changed during this security gate.
- NOT RUN: The APK was not rebuilt or reinstalled because this gate changed external Markdown and release evidence only.
