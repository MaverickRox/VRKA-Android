# VRKA Android 5.0.1

VRKA Android 5.0.1 repairs the in-app application updater and hardens release, APK, and installation validation. The 4.5.3 updater parsed the published `v5.0` tag as `5.0.0`, but its APK matcher accepted only three-part filenames. Because `VRKA-Android-v5.0.apk` did not match, it found no usable asset and reported the app as up to date. The canonical three-part 5.0.1 tag and filename restore the existing update path, and invalid release metadata now fails visibly for manual checks.

## Changes

- Uses canonical stable tags such as `v5.0.1` and requires the exact asset `VRKA-Android-v5.0.1.apk`.
- Rejects malformed, draft, prerelease, duplicate, or version-mismatched release metadata.
- Validates HTTPS URLs, allowed GitHub hosts, redirect targets, and a five-hop limit for metadata and APK downloads.
- Verifies the downloaded APK's private-cache location, package ID, filename/version metadata, non-downgrade version code, and signing certificate before installation.
- Adds regression tests for release parsing and validation, automatic/manual checks, downloads, redirects, downgrade protection, and installer metadata.

The complete account of the shipped 5.0 features is in [CHANGELOG.md](CHANGELOG.md). The final 5.0 architecture and known limitations remain in their dedicated documentation.

## Release artifact

- **File:** `VRKA-Android-v5.0.1.apk`
- **Package:** `com.mvrk.vrka`
- **Version:** `5.0.1` (`versionCode 50001`)
- **SHA-256:** Recorded in `SHA256SUMS` and [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) after the production build is verified.
- **Signing certificate:** Must match the established production signing certificate; see [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md).

## Validation

Unit tests, lint, the production build, APK metadata, signing certificate, and checksum are recorded in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) after verification. Physical-device validation for 5.0.1 has not been performed as part of this release work.
