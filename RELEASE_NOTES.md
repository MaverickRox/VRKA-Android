# VRKA Android 5.0.2

## 5.0.2

- Fixed in-app application update detection and APK selection.
- Improved background application updates with persistent WorkManager downloads.
- Added retry handling for temporary network failures and connection interruptions.
- Improved APK staging, verification, and update-ready notification handling.
- Improved update installation and recovery behavior.

## 5.0

- Redesigned the interface with the Liquid Glass navigation system and horizontal swipe navigation.
- Added configurable concurrent downloads with bounded scheduling and independent cancellation/failure handling.
- Added Android Share Sheet support with robust shared-URL extraction and Download URL pre-filling.
- Added actual downloaded-resolution tracking and improved Queue/History media metadata.
- Added safer History management with record-only deletion, file deletion, multi-select, batch actions, and destructive-action safeguards.
- Improved download state persistence across process restarts and hardened terminal job-state handling.
- Restored and hardened Browser Fallback discovery and transfer handoff through GeckoView and yt-dlp.
- Improved Browser Fallback reliability by separating browser UI/session lifecycle from active transfers and removing blocking worker operations.
- Added validated custom yt-dlp argument templates with shell-free execution, safe staging/output handling, and fallback isolation.
- Improved Android yt-dlp compatibility, including handling of unsupported impersonation targets.
- Added optional, default-off H.264 Compatibility Mode using local FFmpeg conversion and post-conversion validation.
- Reworked subtitle handling with targeted language selection, rate-limit/failure isolation, container-aware embedding, and final subtitle-track verification.
- Improved YouTube subtitle handling for creator/manual captions and automatic-caption fallback.
- Upgraded GeckoView to 156.0.20260921121718 and improved browser-component/runtime reporting.
- Improved component updater reliability, installed-version detection, state transitions, cancellation, and channel switching.
- Hardened request/header handling, private browser-candidate state, diagnostics, output validation, and staged-file cleanup.
- Improved Settings descriptions and component/status presentation to reflect the final production architecture.

## Known Limitations

- Browser Fallback downloads do not currently fetch subtitles.
- H.264 Compatibility Mode is optional and disabled by default.
- Browser Fallback remains dependent on site discovery and yt-dlp/site compatibility.
