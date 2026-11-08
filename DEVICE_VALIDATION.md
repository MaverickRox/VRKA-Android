# Device Validation — VRKA Android 5.1.1

**No v5.1.1 physical-device or emulator run was performed.**

- No physical OnePlus 11 or other compatible ARM64 device was connected during preparation.
- The available emulator AVDs are x86_64 (`BitChord_Experimental_API36`, `Medium_Phone_API_36.0`), while VRKA packages only `arm64-v8a`; they cannot install or run this APK.
- Android test sources compile (`compileDebugAndroidTestKotlin`), but the instrumentation tests, including the new progress-accessibility test, were not executed.
- Runtime verification of API 26–28 SAF destination handling, in-place update from v5.1.0, TalkBack announcements, FFmpeg processing, Browser Fallback candidate transfer, and notification behavior remains pending until a compatible device is available.

No claim of device validation is made for v5.1.1.

---

# Historical: Device Validation — VRKA Android 5.1.0

The 5.1.0 release APK was verified through automated unit tests (601 tests across 58 test suites, 0 failures), release lint (275 warnings, 0 errors), R8 minified release compilation, APK metadata and signature verification via `aapt2` and `apksigner`, and emulator-based UI verification.

Physical OnePlus 11 testing was unavailable for the final 5.1.0 publication pass. The historical [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md) documents the earlier 5.0 APK and does not represent physical hardware validation for 5.1.0.

Browser Fallback candidates, uBlock Origin extension filtering, preflight download size estimations, and language resource parity were validated in the automated test suite and development verification passes.

No physical device verification is claimed for the final production-signed 5.1.0 release artifact.
