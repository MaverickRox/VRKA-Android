# VRKA Android 5.1.1 — Release Security Review (Local Candidate)

**Date:** 2026-11-08
**Scope:** Local v5.1.1 reliability changes to size-estimation metadata handling, queue/history persistence, Android 8/9 destination policy, Browser Fallback diagnostics, updater timestamp parsing, and updater test isolation. No production artifact was built or signed.

## v5.1.1 security-relevant changes

- **Updater timestamps:** `ReleaseManifestVerifier.parseIso8601` now uses strict `java.time` ISO-8601 parsing. Impossible calendar dates, invalid clock values, incomplete timestamps, and trailing text are rejected. Signature verification, the pinned trust anchor, the one-hour issued-at clock skew, expiry, metadata revision, and version-code anti-rollback checks are unchanged.
- **Hermetic updater tests:** Unit tests no longer search for `.vrka/vrka_release_signing_key.sec` or any production signing material. Every cryptographic test generates an in-memory temporary OpenPGP key and executes on each run. A source-scan regression test fails if a test references the production key or signing-properties environment variable. The published v5.1.0 manifest signature is verified with the public trust anchor only.
- **Diagnostic privacy:** Browser Fallback diagnostic entries are sanitized before persistence and again at export. Sensitive query keys are detected case-insensitively and after percent-decoding; duplicates are retained but redacted; credential-like path segments and opaque tokens are replaced. No raw signed URL is stored.
- **Persistence integrity:** JobStore never overwrites a malformed file during load. It preserves a bounded recovery copy, salvages valid records, reports skipped/unsupported/schema states, and returns explicit save results. A failed publish-marker write aborts publication so a terminal duplicate cannot be created silently.
- **Destination policy:** API 26–28 cannot reach the legacy `MediaStore.Files` path without a granted SAF tree, because VRKA does not request `WRITE_EXTERNAL_STORAGE`. Revoked permissions return the job to the folder prompt. API 29+ scoped storage behavior is unchanged.
- **No trust weakening:** The production verifier, pinned certificate constant, package ID, and signing identity were not modified. The local candidate has no production signature.

---

# Historical: VRKA Android 5.1.0 — Release Security Review

**Date:** 2026-10-31
**Scope:** Source changes for v5.1.0 localization, preflight download size estimation, custom runtime management, secure in-app updater redesign with OpenPGP release manifest verification, and the resulting production-signed APK.

## In-App Updater Redesign and Cryptographic Verification

- **Authenticated Release Manifests:** Switched application update metadata verification from unauthenticated GitHub API fields to a versioned machine-readable manifest (`VRKA-Android-v5.1.0.manifest.json`) cryptographically authenticated by a detached OpenPGP signature (`VRKA-Android-v5.1.0.manifest.json.asc`).
- **Pinned OpenPGP Trust Anchor:** Pinned the 4096-bit RSA OpenPGP release signing key (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) in the application binary (`app/src/main/res/raw/vrka_release_pubkey.asc`).
- **Cryptographic Policy Enforcement:** The updater strictly rejects weak hash algorithms (MD5, SHA-1, RIPEMD160) and insecure key algorithms (DSA). Detached signatures must enforce issuer fingerprint subpackets matching the pinned key.
- **Freeze Attack Prevention:** Release manifests declare an expiration timestamp (`expires_at`), preventing freeze attacks where an attacker serves validly signed but outdated metadata.
- **Rollback and Downgrade Defense:** Monotonically increasing `metadata_revision` numbers prevent replay attacks, while `versionCode` checks ensure updates never downgrade an existing installation.
- **Staging Verification Defect Resolution:** Fixed the defect in `AppUpdateManager` and `AppUpdateDownloadWorker` where `.download.tmp` was checked against the final canonical filename. Archive structure, package identity, and certificate checks are now performed on the temporary download, while canonical filename checks run on the final staged APK.

## Custom runtimes and component updates

- Custom yt-dlp runtimes configured in Settings are validated for integrity and execution safety without shell interpolation.
- Upstream official yt-dlp component updates continue to enforce OpenPGP signature verification before installation.
- uBlock Origin updates enforce Firefox WebExtension XPI structure and checksum checks.

## Localization and resource security

- All 14 application locale string resources were audited for XML structure, encoding integrity, and key parity.
- Format strings preserve positional specifiers without formatting string injection risks.
- Right-to-left layout handling for Arabic ensures UI elements remain bounded and readable.

## Release signature and integrity verification

- The final production APK (`VRKA-Android-v5.1.0.apk`) was verified with `apksigner`:
  - One RSA 4096-bit signer
  - APK Signature Scheme v2 verified
  - Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
  - Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
  - Exact match with established production signing identity.
- Artifact properties:
  - File: `VRKA-Android-v5.1.0.apk`
  - Size: `157,202,797` bytes
  - SHA-256: `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
  - Manifest SHA-256: `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`
  - Manifest Signature SHA-256: `17B3C504EB4352FCB34815975E973AB8AA6AAEBC6FD1852E2719646858B4FB35`
- No keystores, private keys, or passwords exist in the repository or public documentation.

## Verification boundary

- Unit tests: 639 passing tests across 61 suites, 0 failures.
- Release lint: 275 warnings, 0 hints, 0 errors.
- Production APK build: non-debuggable, minified with R8, shrunk resources.
- Physical OnePlus 11 hardware was unavailable; emulator validation and automated suites passed.

---

# VRKA Android 5.0 — Release Security Review

**Date:** 2026-10-04
**Scope:** source changes for final local release preparation and the resulting production-signed APK. This is not a dynamic penetration test.

## Custom yt-dlp templates

- Templates are parsed as argument lists and passed to yt-dlp without shell execution.
- VRKA retains control of the public URL, staging path, and output publication.
- Existing policy rejects shell hooks and unsafe output/config paths, cookie files, external downloaders, credential-bearing header arguments, proxies, and extra positional URLs.
- Eligible failed custom attempts may enter Browser Fallback. The fallback request clears template IDs/arguments and user-entered headers and uses the standard yt-dlp path.

## URLs, headers, and persisted state

- External job URLs are constrained to HTTP(S).
- Header validation rejects malformed/CRLF values and restricts sensitive browser-session headers to approved use.
- Browser candidate URLs and captured headers are held in memory and are excluded from persisted job data.
- Diagnostics sanitize URLs, headers, and free text; Browser Fallback failures do not expose private candidate URLs.

## Runtime components and release signature

- GeckoView hosts navigation and the active VRKA media-detector and uBlock WebExtensions.
- yt-dlp owns normal and Browser Fallback transfers; FFmpeg handles local processing. Media3/remote-FFmpeg code has no active manager call site, and N_m3u8DLRE is gated off.
- The retired extension is absent from the APK and active updater/registration paths. Upgrade-only cleanup cancels its queued update, clears old preferences, uninstalls the existing Gecko extension ID, and deletes its old copied XPI.
- The final APK was verified with `apksigner`: one RSA 4096-bit signer, v2 scheme verified, certificate SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`. This matches the known production certificate. Signing secrets and passwords are not included in the repository or this report.

## Verification boundary

The clean build passed 348 unit tests and release lint (67 warnings, one hint, no errors or fatal findings). APK metadata and packaged assets were inspected. No emulator, ADB, physical device, or dynamic network interception was used. Browser Fallback remains dependent on yt-dlp and site behavior and does not bypass DRM, authentication, payment, CAPTCHA, or other access controls. See [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) for exact artifact metadata and [PHYSICAL_VALIDATION_REPORT.md](PHYSICAL_VALIDATION_REPORT.md) for device-test status.
