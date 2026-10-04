# Device Validation — Final Local Build

The final v5.0 release APK has not been installed or tested on a device. Maintainer validation on the OnePlus 11 is pending and must be associated with the APK SHA-256 in [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md).

The maintainer previously reported Browser Fallback results for MissAV, Anikoto, StreamingUnity, and JAV Guru on an earlier build. Those results remain historical context and are not evidence for the final production-signed artifact.

This release-engineering pass used unit tests, release lint, resource/Kotlin/Java compilation, JavaScript syntax checks, JSON parsing, APK metadata inspection, and `apksigner`. No emulator, ADB, device farm, or physical-device automation was used.
