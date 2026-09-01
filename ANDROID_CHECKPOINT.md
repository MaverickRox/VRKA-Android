# VRKA Android final checkpoint

## Interrupted-state recovery checkpoint — 2026-07-29

- The workspace does not contain a Git repository. Git status, commits, and diffs are unavailable.
- The interrupted source archive preserves the recoverable pre-final source. The final package now uses `release/VRKA-Android-1.0.0-source.zip`.
- The preserved pre-stabilization APK remains `release/VRKA-Android-1.0.0-pre-stabilization.apk`.
- The interrupted APK is `release/VRKA-Android-1.0.0-interrupted-2026-07-27.apk`.
- The interrupted APK SHA-256 is `6E72B1871437229BBDDF42AFFAD4BD311ECB16564E79F1E5E01ADE9AB2094786`.
- The interrupted source is `release/VRKA-Android-1.0.0-interrupted-2026-07-29-source.zip`.
- The interrupted source SHA-256 is `D553F243C0515C21514F59F9E14EC373F76E5C1E4ECE7B9A4EEB7EC1967745CC`.
- The interrupted APK does not match the current source.
- The interrupted APK contains the experimental capped selector with a strict `height` filter and an unfiltered fallback tier.
- Physical test YT-2 failed with that APK. The 2160p selection entered Browser fallback for `jNQXAC9IVRw`.
- A prior APK completed YT-1 for `jNQXAC9IVRw` as a 320x240 AV1 video with Opus audio.
- The current source replaces the strict selector filter with `-S res:<cap>`.
- The current source also rejects an audio-only output before publishing it.
- That recovery source was experimental. The final release section below supersedes it.
- The source already contains bounded staging names, bounded final names, persisted Browser handoff ordering, and WebView resume reconstruction.
- Do not overwrite either interrupted recovery artifact until a tested release exists.

## YouTube selector acceptance — 2026-07-29

- Accepted APK: `release/VRKA-Android-1.0.0-youtube-accepted.apk`.
- APK SHA-256: `46765E78FF21202C4A4DBB2E215667F434D8F263392C2D569BF90EAB48C72648`.
- APK size: 55,269,341 bytes.
- Build result: unit tests, lint-vital, R8, signing, and release assembly passed.
- Selector: `bestvideo+bestaudio/best[height>0]`.
- Quality cap: yt-dlp format sort `-S res:<height>`.
- Publication guard: Video mode rejects an output that has no video stream. The guard also rejects a known height above the selected cap.
- YT-1 source: `https://www.youtube.com/watch?v=2Vv-BfVoq4g`.
- YT-1 result: PASS. The normal extractor completed. FFprobe found 1920x1080 AV1 video and Opus audio in MP4.
- YT-2 used the same source with the 2160p cap.
- YT-2 result: PASS. The source maximum was 1080p. FFprobe found 1920x1080 AV1 video and Opus audio in MP4.
- Active-download lifecycle result: PASS. The foreground service stayed active after Home. The UI reconnected after return.
- The prior `dQw4w9WgXcQ` source now exposes 2160p. It is no longer a valid 1080p-maximum cap fixture.

## Instagram direct acceptance — 2026-07-29

- Test URL: `https://www.instagram.com/reel/DX9k2DiApan`.
- Result: PASS.
- The normal yt-dlp path handled the Reel. VRKA did not open Browser fallback.
- Queue progress reached completion. History showed Complete.
- Published file: `Video by gormtheold25.mp4`.
- Published size: 12,498,808 bytes.
- FFprobe found 1280x850 VP9 video and AAC audio.
- The filename is human-readable and bounded.
- No Instagram-specific extractor or parser was added.

## Browser lifecycle acceptance — 2026-07-29

- Accepted APK: `release/VRKA-Android-1.0.0-browser-accepted.apk`.
- APK SHA-256: `E913CCA1AE6E6006F464DC27C114C4B007795ADF259CA815C323DAA2F4B9CC3A`.
- The Browser screen releases its WebView after Activity pause.
- The Browser screen reconstructs the WebView from the same pending job URL after Activity resume.
- VRKA displays a loading surface while the reconstructed WebView loads.
- Lifecycle test source: `https://example.com/`.
- Lifecycle result: PASS. The page was visible immediately after Home and return. Back returned to Queue.
- Eporner test URL: `https://www.eporner.com/video-gOWdvzmM4Yp/skip-trace-3-hardcore/`.
- Earlier Eporner run: the extractor and WebView both reported connection timeout.
- Network diagnosis: the Windows host resolved `www.eporner.com` to `94.75.220.x`, while the phone/router resolver temporarily returned unreachable Reliance address `49.44.79.236`.
- Android Firefox loaded the exact page and player immediately. Android Chromium-based Brave failed its renderer during the same comparison.
- Android command-line TLS to Eporner's real host returned HTTP 200. The resolver later returned the real `94.75.220.x`/`2001:1af8:...` pool.
- Latest unchanged-APK Eporner run: PASS. Normal yt-dlp downloaded the exact URL. Browser fallback did not open. History showed Complete. The MP4 is 1,348,190,582 bytes. VLC opened it in the video player.
- Conclusion: do not classify Eporner as inherently slow or unsupported. The earlier failure was an intermittent Android resolver/Chromium-path event, not a site-specific extractor gap.
- The manager now skips an equivalent direct recovery attempt after a network timeout.
- No Eporner-specific extractor was added.
- HentaiHaven was not rerun. The change did not modify media observation or handoff persistence.

## Desktop parity source checkpoint — 2026-07-29

- Desktop audit: `DESKTOP_PARITY.md` records the bounded Windows build-007 comparison.
- Font: the source now contains the Desktop Space Mono Regular and Bold assets.
- Font license: the source contains the SIL Open Font License 1.1 text.
- Appearance: one persisted Light/Dark switch controls the primary mode.
- AMOLED: one separate persisted switch changes Dark mode only.
- Migration: an existing AMOLED selection becomes Dark with AMOLED enabled.
- About: the user-facing text is only `VRKA Android 1.0.0 • MVRK`.
- Added controls: Prefer 60 FPS, automatic captions, subtitle embedding, metadata, audio thumbnail, and SponsorBlock categories.
- Input validation: VRKA rejects invalid playlist ranges and invalid trim ranges before enqueue.
- Browser errors: a failed main-frame load now shows the WebView error with Retry and Close.
- Browser timeout: the loading surface changes to the error state after 20 seconds.
- Automated result: the Debug Kotlin compile and all focused JUnit tests passed.
- Physical result: PASS on the final signed APK.

## Final release candidate — 2026-07-29

- APK: `release/VRKA-Android-1.0.0-arm64-v8a.apk`.
- APK SHA-256: `72DA74B1CBF06E03D58A9F1B4AF320C2D0551EFD4F7202F0970C831F2550960E`.
- APK size: 55,381,810 bytes.
- Build: JUnit, lint-vital, R8, resource shrinking, signing, and release assembly passed.
- Signing: APK Signature Scheme v2 passed with one RSA-4096 signer.
- Certificate SHA-256: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`.
- Install: `adb install -r` returned Success on device `897f6ce5`.
- Package: `com.mvrk.vrka`, version 1.0.0, min SDK 24, target SDK 36, ARM64 ABI.
- Runtime UI: yt-dlp `2026.07.04` reported Ready after first use.
- History: the Eporner Complete row remained after APK replacement and process recreation.
- Appearance: Light, Dark, AMOLED off, and AMOLED on passed. Both preferences persisted after process recreation.
- Browser resume: Example Domain remained visible after Home and return. Back returned to Queue.
- Browser error: `.invalid` showed `ERR_NAME_NOT_RESOLVED` with Retry and Close.
- Performance: 788 frames, 12 janky frames (1.52%), p50 5 ms, p90 7 ms, p95 7 ms, p99 19 ms.
- Hot return: 39 ms TotalTime and 43 ms WaitTime.
- Idle PSS after navigation: 116,353 KB.

## Pre-stabilization recovery checkpoint — 2026-07-27

- Original baseline source SHA-256: `D4F6B682AA9B2A35A3F3C9533864F53AA1FB4FB49F99B72B08366470F7E46309`. The final source package supersedes the original archive path.
- Recoverable baseline APK: `release/VRKA-Android-1.0.0-arm64-v8a.apk`, SHA-256 `81E57DE0DA5B1EDF9DF35F9376A11AA8EE64572D19B00D8F1F5CA2113BE2E13A`.
- Source state: the complete 43-entry buildable source tree represented by that archive plus matching `SHA256SUMS.txt`; no signing secrets, local SDK paths, or build outputs are included.
- Architecture: native Kotlin/Jetpack Compose application; one coroutine/StateFlow download manager and persisted state machine; youtubedl-android/FFmpeg execution; foreground service; MediaStore/SAF publisher; temporary AndroidX WebKit fallback feeding the same queue job.
- Toolchain: AGP 9.2.0, Gradle 9.4.1, Kotlin/Compose compiler plugin 2.3.21, Compose BOM 2026.06.00; Android SDK/compile 36.1, target 36, min 24; JDK `F:\Android\Android Studio\jbr`.
- Package/application ID: `com.mvrk.vrka`; version 1.0.0 (code 1); release ABI `arm64-v8a` only.
- Media runtime: youtubedl-android 0.18.1, bundled native FFmpeg, stable yt-dlp runtime updated and validated at `2026.07.04`.
- Proven working baseline: launch, ordinary video, MP3/WAV/FLAC, queue/progress/speed/ETA/cancel/retry/history, foreground/background transfer, Downloads/VRKA and SAF publishing, Open/Share/Delete, nested-media browser handoff, conservative popup/ad containment, Twitter/X, and user-accepted HentaiHaven 1080p.
- Known stabilization bugs: Video/Best may select audio-only; quality choices must be upper bounds; WebView fallback may resume as a black surface; browser-candidate staging can derive an overlong signed-URL name; Android direct Instagram/Eporner extraction differs from Windows; optional theme/about P2 cleanup remains.
- Read-only Windows VRKA build-007 reference: `C:\Users\Shukla\Documents\Codex\2026-07-23\v\work\vrka2007\VRKA-2.0.0-build007-source`. Never modify it.
- Build command: set `JAVA_HOME=F:\Android\Android Studio\jbr`, set `VRKA_SIGNING_PROPERTIES=C:\Users\Shukla\.vrka-android-signing\signing.properties`, then run `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease`.
- Install command: `F:\Android\Sdk\platform-tools\adb.exe -s 897f6ce5 install -r release\VRKA-Android-1.0.0-arm64-v8a.apk`.
- Physical test setup: connected CPH2447, serial `897f6ce5`, Android 16/API 36, arm64-v8a, high-refresh 1440x3216 display.
- Recovery rule: use the dated interrupted source archive and the pre-stabilization APK. Do not touch the Windows reference or signing secrets.

## Pre-stabilization frozen release

- Status: buildable, signed, minified ARM64 release frozen on 2026-07-27.
- APK: `release/VRKA-Android-1.0.0-pre-stabilization.apk`.
- APK size: 55,266,521 bytes.
- APK SHA-256: `81E57DE0DA5B1EDF9DF35F9376A11AA8EE64572D19B00D8F1F5CA2113BE2E13A`.
- Package/version: `com.mvrk.vrka`, version `1.0.0` (`versionCode 1`).
- SDK/ABI: min SDK 24, target SDK 36, compile SDK 36.1; APK contains only `arm64-v8a` native libraries.
- Signing: APK Signature Scheme v2, one RSA-4096 signer; certificate SHA-256 `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`.
- Protected signing material remains outside the source tree at `C:\Users\Shukla\.vrka-android-signing`; do not copy it into source archives or disclose its passwords.
- Final build/test command: `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease` with `VRKA_SIGNING_PROPERTIES` set.
- Final Gradle result: BUILD SUCCESSFUL; unit tests, lint vital, R8, resource shrinking, signing validation, and release assembly passed.

## Physical-device acceptance

- Device: CPH2447, Android 16/API 36, arm64-v8a.
- Final exact artifact: `adb install -r` returned Success; installed package metadata remained version 1.0.0, min SDK 24, target SDK 36, primary ABI arm64-v8a.
- Runtime: youtubedl-android 0.18.1, bundled native FFmpeg, yt-dlp updated successfully in the minified release to stable `2026.07.04`.
- Ordinary path: `https://media.w3.org/wai/perspective-videos/large-links-buttons-controls.mp4` completed and published a playable 9,757,446-byte MP4 to `Downloads/VRKA`; the final minified release repeated this smoke test successfully.
- Difficult-site path (user-verified): exact `https://hentaihaven.xxx/watch/megane-no-megami/episode-1/` flow exercised VRKA WebView fallback, detected and handed off the intended media, showed real progress/speed/ETA, completed, published to `Downloads/VRKA`, and opened as the correct playable 1080p video.
- Do not repeat that difficult-site test unless a later code change directly risks WebView media detection or handoff.
- Earlier Vimeo and storage.googleapis.com failed history rows are development artifacts, not current acceptance failures.

## Regression closure

- Physical audio outputs: MP3 320 kbps, MP3 192 kbps, WAV, and FLAC all completed and were validated with FFprobe; WAV and FLAC also confirmed serial queue execution.
- Playlist/range, subtitle, trim, format, bitrate, quality, SponsorBlock, and browser-session-header command construction is covered by passing JUnit regression tests.
- Cancellation reached `CANCELLED` on an active large transfer; the post-metadata cancellation race is fixed.
- Background FLAC transfer kept the foreground service active after Home and completed normally.
- Queue/history/retry/request reconstruction, duplicate-name collision handling, MediaStore publishing, Open/Share/Delete actions, progress, speed, and ETA were verified during the device run.
- WebView containment remains conservative and fail-open: popups/new windows are suppressed, obvious ad/tracker resources are blocked, and media/segment candidates needed for extraction are preserved. The accepted difficult-site flow confirms reliable operation for the required target.

## Release performance sanity

- Cold start after final APK install: 307 ms TotalTime and 320 ms WaitTime.
- Hot foreground return after Home: 39 ms TotalTime and 43 ms WaitTime.
- Bottom-navigation run: 788 frames, 12 janky frames (1.52%); p50 5 ms, p90 7 ms, p95 7 ms, p99 19 ms.
- Idle memory after the navigation run: 116,353 KB PSS. No crash occurred.

## Packaging and limitations

- Source archive: `release/VRKA-Android-1.0.0-source.zip` (generated after this checkpoint).
- Checksums: `release/SHA256SUMS.txt`.
- Notices: `THIRD_PARTY_NOTICES.md`.
- `KNOWN_LIMITATIONS.md` records the final limitations and unrun physical tests.
- Playlist/range/subtitle/trim parity is regression-tested at command construction level but was not each exercised as a separate online end-to-end physical download in this final quota-conscious pass.

## Pre-polish recovery checkpoint — 2026-07-29

- Status: the accepted stabilized 1.0.0 build was frozen before final presentation changes.
- APK: `release/VRKA-Android-1.0.0-stabilized-pre-polish.apk`.
- APK SHA-256: `72DA74B1CBF06E03D58A9F1B4AF320C2D0551EFD4F7202F0970C831F2550960E`.
- Source: `release/VRKA-Android-1.0.0-stabilized-pre-polish-source.zip`.
- Source SHA-256: `3243B1516CEBB82F2C592F56C930635CB972B1901509FAA64684CA39AD2B5CD8`.
- Scope after this checkpoint: Compose presentation, notification presentation, and Android branding resources only.
- Protected core: yt-dlp, FFmpeg, selectors, WebView fallback, persistence, staging names, publishing, and networking remain unchanged.

## Final polished release — 2026-07-29

- Version: VRKA Android 1.0.0, version code 1.
- Package: `com.mvrk.vrka`.
- ABI: `arm64-v8a`.
- APK: `release/VRKA-Android-1.0.0-arm64-v8a.apk`.
- APK size: 55,400,698 bytes.
- APK SHA-256: `B3E19AAAD9E997679A8B9A91286E10DF3E1343E617AB9082AE1A36D8459BADAF`.
- Signing: APK Signature Scheme v2 passed with one RSA-4096 signer.
- Certificate SHA-256: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`.
- Build: JUnit, lint-vital, R8, resource shrinking, signing validation, and release assembly passed.
- Install: `adb install -r` returned Success on device `897f6ce5`.
- Appearance: compact Light/Dark control; separate remembered AMOLED preference; dynamic system bars.
- Navigation: matching vector icons and a compact active-download strip.
- Presentation: Home plan summary, polished Queue/History rows, phase labels, bounded failure copy, and one foreground notification.
- Branding: adaptive launcher icon, monochrome themed icon, branded splash, and notification icon.
- Persistence: Dark+AMOLED and History survived APK replacement and force-stop/relaunch.
- Screenshots: `release/screenshots`.

## Final security freeze — 2026-07-29

- Recovery baseline APK: `release/VRKA-Android-1.0.0-polished-pre-security.apk`.
- Recovery baseline APK SHA-256: `B3E19AAAD9E997679A8B9A91286E10DF3E1343E617AB9082AE1A36D8459BADAF`.
- Recovery baseline source: `release/VRKA-Android-1.0.0-polished-pre-security-source.zip`.
- Recovery baseline source SHA-256: `9B2BD2045EDFB571A42F4E4F82F53E7DF4BFEBA526220D41CBE79626562A32AD`.
- Scanner: direct OSV `querybatch` against all 147 resolved Maven release coordinates.
- Result: 22 advisory matches in four old youtubedl-android transitive components. Reachability review found no P0 or P1 release path.
- Updater: GitHub HTTPS and controlled app storage passed. Checksum verification, redirect-host restrictions, and atomic replacement remain P2 actions.
- Secrets: source tree and source ZIP pattern/file scans passed. Signing material remains external.
- Android surface: release APK is non-debuggable. MainActivity is the only unprotected exported application entry point. DownloadService is non-exported. Backup and cleartext traffic remain disabled.
- Tests: focused input, path, filename, selector, and structured-argument JUnit tests passed.
- Application changes: none.
- APK rebuild: not required. The final APK remains byte-for-byte unchanged.
- Evidence: `release/security`.
- Final source ZIP and `SHA256SUMS.txt` are generated after this checkpoint update.
- Freeze status: no unresolved P0 or P1 security finding blocks Android 1.0.0.
