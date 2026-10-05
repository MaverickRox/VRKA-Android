# Historical Physical Validation — VRKA Android 5.0 Release APK

This report applies only to the 5.0 artifact identified below. It is retained as historical release evidence and does not validate VRKA Android 5.0.1. The 5.0.1 release has not been physically tested.

The inspection described below covered the 5.0 APK and its on-device product state. It did not test the 4.5.3-to-5.0 GitHub release asset lookup that is corrected in 5.0.1.

**APK:** `F:\Projects\VRKA-Android\app\build\outputs\apk\release\app-release.apk`
**SHA-256:** `D821B65BE0D054F3232E7A3BB051408BC0150EB2A66CDF5E7D3FC9170D8DA487`
**Status:** Physically inspected and accepted by the maintainer on the OnePlus 11.

The maintainer has manually inspected this exact production-signed artifact on the OnePlus 11 and confirmed that:
- The dynamic yt-dlp installed-version resolution operates correctly.
- Component update checks, timeouts, and batch lifecycles are resolved and accepted.
- Navigation pill geometry, equal slot-based dimensions, and liquid-glass optics are accepted for 5.0.
- The product state is approved for official release.

No automated emulator, ADB, or device test framework was used in the engineering run; validation was performed directly by the maintainer. Automated build, unit-test (348 passed), lint, resource, JavaScript, and signature evidence is in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).
