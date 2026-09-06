# Known Limitations

> [!NOTE]
> **Historical Baseline Document**: This document records the known limitations identified during the original VRKA Android 1.0.0 release audit. It is preserved for historical traceability and baseline reference. For current v4.0.1 architecture and security policies, see [README.md](README.md) and [SECURITY.md](SECURITY.md).

## Platform

- The release APK supports `arm64-v8a` only.
- Android can stop the application process. VRKA marks an interrupted job as Failed. The user can retry the job.
- Android notification permission controls foreground-download notification visibility.

## Media and sites

- VRKA does not bypass DRM.
- VRKA does not bypass login, payment, CAPTCHA, consent, or access controls.
- A site change can require a newer yt-dlp runtime.
- Browser fallback depends on the installed Android System WebView and the Android network resolver.
- An intermittent device/router DNS answer caused one Eporner timeout. A later attempt used the normal downloader and succeeded.
- Browser fallback now reports a main-frame error after a bounded wait. Retry and Close remain available.
- Browser session headers remain in memory. Android process death during handoff requires a job retry.
- Android System WebView can keep fallback cookies in its app-private cookie jar. VRKA does not copy these cookies into History, notifications, or logs.

## Desktop controls not in the Android 1.0 UI

- Android does not import cookies from a desktop browser profile.
- Android does not expose proxy, rate-limit, or force-IPv4 controls.
- Android does not expose free-text yt-dlp arguments.
- Android does not expose raw output templates or a Desktop-style archive file.
- Android uses MediaStore or the Storage Access Framework for final output.

These omissions avoid untested controls and unsafe free-text command input.
The omissions do not change the accepted YouTube, Instagram, Twitter/X, or Browser fallback paths.

## Dependency review

- youtubedl-android 0.18.1 supplies the Python and FFmpeg runtime integration.
- Its transitive graph includes Commons IO 2.5, Commons Compress 1.12, and Jackson 2.11.1.
- The final gate queried OSV for all 147 resolved release coordinates. OSV returned 22 matches in these old transitive libraries.
- Reachability review found no release-blocking P0 or P1 path. `SECURITY_REVIEW.md` lists each advisory.
- This gate did not override the transitive versions. A later compatible stack should update them.
- The yt-dlp updater relies on GitHub HTTPS. It does not verify the published checksum/signature files and does not use an atomic replacement.
- `THIRD_PARTY_NOTICES.md` lists the significant runtime licenses.

## Test scope

- The final test report identifies each physical test and each automated test.
- A test that was not run is not a pass.
