# Known Limitations — VRKA Android 5.1.0

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 MUST DOWNLOAD AND INSTALL v5.1.0 DIRECTLY FROM THE OFFICIAL GITHUB RELEASE PAGE. THE v5.0.2 IN-APP UPDATER CANNOT COMPLETE THIS UPDATE. DO NOT USE THE RETRY BUTTON.**
> 
> **OFFICIAL DOWNLOAD PAGE:**
> https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0
> 
> **INSTALLATION:** Download `VRKA-Android-v5.1.0.apk` from the Assets section and install it over your existing VRKA installation. DO NOT UNINSTALL v5.0.2 FIRST.

- The app and packaged native media libraries target `arm64-v8a`; other ABIs are not shipped.
- VRKA does not bypass DRM, login, payment, CAPTCHA, consent, or other access controls. Site changes may require a yt-dlp or app update.
- Preflight download size estimates are approximate calculations based on available metadata streams and format plans; actual bytes transferred may vary based on server encoding or post-processing.
- Browser Fallback discovers media in GeckoView and transfers the selected candidate with yt-dlp. Browser Fallback currently does not request or publish subtitles; subtitle options apply to the normal yt-dlp acquisition path.
- H.264 Compatibility Mode is optional and off by default. It needs a downloaded local video, an available FFmpeg `libx264` encoder, temporary storage and extra processing time/battery. Conversion fails safely if the codec cannot be probed or the output does not validate. Audio and subtitle streams are copied where the input/container permits.
- Custom yt-dlp templates apply to the explicitly selected normal attempt. They do not apply to Browser Fallback. VRKA blocks shell execution and path/output overrides, but a permitted yt-dlp option can still alter extraction behavior or fail on a particular site.
- The Browser Session clear action clears GeckoView runtime cookies, DOM storage, auth sessions, site data, caches and in-memory discovered candidates. It does not cancel an active private fallback session or clear app settings, history, queue, or diagnostics.
- Upgrading from previous releases may carry legacy extension or update state; migration logic cleans obsolete state upon first run.
- **In-App Updater Migration from v5.0.2**: Devices currently running v5.0.2 encounter an `APK verification failed: APK filename does not match the expected release` error during in-app self-update due to a client-side verification check on the temporary download file in the v5.0.2 binary. Existing v5.0.2 users must manually install `VRKA-Android-v5.1.0.apk` from the GitHub release. Because both releases share the identical production signing key, all application data, settings, and download histories are preserved.
- Component version is shown as unknown when the app cannot read a runtime, packaged-file or verified-update version. “Up to date” appears only after a check or verified component update.
- Android may stop the application process under memory pressure; interrupted background work can be resumed or retried.
- The physical OnePlus 11 device was unavailable during final 5.1.0 release publication. The historical OnePlus 11 report documents only the earlier 5.0 artifact and does not establish physical validation for 5.1.0.

## Validation Scope

Automated test and build verification results are recorded in `RELEASE_EVIDENCE.md`. Verification includes 635 unit tests across 60 suites, release lint, production release build compilation, signature verification, and emulator execution.
