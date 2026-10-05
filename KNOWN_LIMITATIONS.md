# Known Limitations — VRKA Android 5.0.2

- The app and packaged native media libraries target `arm64-v8a`; other ABIs are not shipped.
- VRKA does not bypass DRM, login, payment, CAPTCHA, consent, or other access controls. Site changes may require a yt-dlp or app update.
- Browser Fallback discovers media in GeckoView and transfers the selected candidate with yt-dlp. Browser Fallback currently does not request or publish subtitles; subtitle options apply to the normal yt-dlp acquisition path.
- H.264 Compatibility Mode is optional and off by default. It needs a downloaded local video, an available FFmpeg `libx264` encoder, temporary storage and extra processing time/battery. Conversion fails safely if the codec cannot be probed or the output does not validate. Audio and subtitle streams are copied where the input/container permits.
- Custom yt-dlp templates apply to the explicitly selected normal attempt. They do not apply to Browser Fallback. VRKA blocks shell execution and path/output overrides, but a permitted yt-dlp option can still alter extraction behavior or fail on a particular site.
- The Browser Session clear action clears GeckoView runtime cookies, DOM storage, auth sessions, site data, caches and in-memory discovered candidates. It does not cancel an active private fallback session or clear app settings, history, queue, or diagnostics.
- An upgrade from the previous release may carry retired extension and update state; the app cleans that state during migration. The final APK includes no corresponding extension asset or active update/registration path. Media3 custom transfer code is dormant; N_m3u8DL-RE is gated off.
- Component version is shown as unknown when the app cannot read a runtime, packaged-file or verified-update version. “Up to date” appears only after a check or verified component update.
- Android may stop the application process; interrupted work may need to be retried. Network access and available storage affect downloads and updates.
- The 5.0.2 APK has not been physically validated. The OnePlus 11 report documents only the earlier 5.0 artifact and does not establish validation for 5.0.2.

## Validation Scope

Automated test/build results are recorded in `RELEASE_EVIDENCE.md`. No emulator, ADB, or device automation was used for 5.0.2, and no physical-device validation is claimed for this release.
