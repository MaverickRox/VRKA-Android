# VRKA Android 5.1.0 — Final Documentation and Test-Evidence QA Report

**Audit Date:** 2026-11-01  
**Repository:** `https://github.com/MaverickRox/VRKA-Android`  
**Workspace:** `F:\Projects\VRKA-Android-v5.1.0-release-base`  
**Target Release:** `v5.1.0` (commit `2e5b8228b581af606a7a6c40758c2aaaef0914c9`)  
**Production APK:** `VRKA-Android-v5.1.0.apk` (`5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa`)  
**Role:** QA Engineer and Documentation Maintainer  

---

## Executive Summary

A comprehensive, evidence-based quality assurance audit and documentation reconciliation was executed across the VRKA Android repository on the `main` branch. 

All primary objectives have been verified and reconciled:
1. **Unit-Test Reconciliation (635 vs 639)**: The discrepancy between 635 and 639 tests was traced to 4 OpenPGP manifest timestamp regression tests added in prompt 9. During documentation modernization in prompt 10, Gradle executed with cached `UP-TO-DATE` tasks, causing previous report text citing the historical 635 baseline to be quoted. A full rerun (`--rerun-tasks`) confirms **639 executed unit tests across 61 suites**, with **0 failures, 0 errors, and 0 skipped**.
2. **Regression Test Verification**: All four timestamp regression tests exist in `ReleaseManifestVerifierTest.kt`, execute, and pass with rigorous cryptographic assertions.
3. **Screenshot & Asset Audit**: Verified that `README.md` references only four valid, modern UI screenshots (`download-dark.png`, `queue-dark.png`, `history-dark.png`, `download-light.png`). The legacy screenshot `browser-subsystems-dark.png` depicting Puemos is unreferenced in public markdown. An obsolete copy in Fastlane metadata (`05_browser_subsystems_dark.png`) was purged from phone screenshots.
4. **Public README & Mermaid Flowchart**: Reconciled and verified. Prominently features the 14-language localization suite with RTL support, accurately represents `yt-dlp` as primary transfer engine, GeckoView and the first-party VRKA Media Detector (`media-detector@vrka.mvrk.com`), `uBlock Origin` content filtering, and renders a clean, valid GitHub-flavored Mermaid flowchart.
5. **Git & Release Preservation**: Remote origin retains strictly **one** branch (`main`), release tag `v5.1.0` remains immutably targeted to commit `2e5b822`, and all 5 GitHub release assets match bit-for-bit.

---

## A. Unit-Test Reconciliation

### A.1 Background & Root Cause of 635 vs 639 Discrepancy
- **Pre-Timestamp Baseline**: Following the secure in-app updater redesign in commit `2e5b822`, the test suite comprised 635 tests across 60 suites.
- **Manifest Timestamp Hardening (Prompt 9)**: To address OpenPGP timestamp skew and ISO 8601 UTC dynamic generation, 4 new unit regression tests were added to `app/src/test/java/com/mvrk/vrka/update/security/ReleaseManifestVerifierTest.kt`:
  1. `testFutureIssuedTimestampBeyondToleranceRejected`
  2. `testClockSkewWithinToleranceAccepted`
  3. `testIso8601UtcParsingAndConversion`
  4. `testMetadataRevision3MonotonicUpgradeAccepted`
  These tests expanded `ReleaseManifestVerifierTest` from 10 to 14 test cases, bringing the total test count to **639** across **61 suites**.
- **Documentation Run (Prompt 10)**: The test task `:app:testDebugUnitTest` evaluated as `UP-TO-DATE` because no test source had changed in that documentation step. Consequently, earlier documentation files (`RELEASE_EVIDENCE.md`, `SECURITY_REVIEW.md`, `KNOWN_LIMITATIONS.md`) which recorded the earlier 635 baseline were cited.
- **Verification with `--rerun-tasks`**: Running `.\gradlew.bat testDebugUnitTest --rerun-tasks --offline` generated fresh JUnit XML test results proving:
  - **Total Test Suites:** 61
  - **Total Tests Executed:** 639
  - **Total Failures:** 0
  - **Total Errors:** 0
  - **Total Skipped:** 0
  - **Pass Rate:** 100%

### A.2 Four Named Timestamp Regression Tests
All four regression tests reside in `com.mvrk.vrka.update.security.ReleaseManifestVerifierTest`:

| # | Test Method Name | Intent & Assertion Scope | Result |
|---|---|---|---|
| 1 | `testFutureIssuedTimestampBeyondToleranceRejected` | Asserts that a manifest with `issued_at` set 4 hours in the future is rejected with `ManifestValidationException: Manifest issued_at is in the future`. | **PASS** |
| 2 | `testClockSkewWithinToleranceAccepted` | Asserts that client-server clock skew within the 1-hour tolerance window (e.g. 5 minutes in future) is accepted without error. | **PASS** |
| 3 | `testIso8601UtcParsingAndConversion` | Asserts strict ISO 8601 UTC parsing requiring explicit trailing `'Z'`, rejecting malformed or non-UTC datetime strings. | **PASS** |
| 4 | `testMetadataRevision3MonotonicUpgradeAccepted` | Asserts that manifest `metadata_revision = 3` upgrades monotonically from stored revision 2, while rejecting rollback to revision 4. | **PASS** |

### A.3 Test Suite Inventory & JUnit XML Verification
JUnit XML reports in `app/build/test-results/testDebugUnitTest/` verify test counts across the update security packages:
- `TEST-com.mvrk.vrka.update.security.ReleaseManifestVerifierTest.xml`: **14 tests** (0 failures)
- `TEST-com.mvrk.vrka.update.security.ReleaseManifestTest.xml`: **20 tests** (0 failures)
- `TEST-com.mvrk.vrka.update.security.ReleasePipelineEndToEndTest.xml`: **4 tests** (0 failures)
- `TEST-com.mvrk.vrka.update.AppUpdateManagerTest.xml`: **34 tests** (0 failures)
- `TEST-com.mvrk.vrka.update.AppUpdateDownloadPolicyTest.xml`: **7 tests** (0 failures)
- 56 additional test suites across core, download, engine, navbar, audio, and subtitle modules: **560 tests** (0 failures)
- **Sum Total:** **639 passing tests**.

### A.4 Test Cleanliness Enhancement
In `ReleaseManifestVerifierTest.generateAndVerifyReleaseManifestAsc`, modified test signature generation to output to a `File.createTempFile("manifest-test", ".asc")` with `deleteOnExit()`, preventing the test from unintentionally dirtying the tracked `VRKA-Android-v5.1.0.manifest.json.asc` file in the working directory during test runs.

---

## B. Screenshot and Image Audit

### B.1 Active Screenshots in `README.md`
`README.md` embeds exactly four UI screenshots within the central table:
1. `docs/screenshots/download-dark.png` (Download Screen, Dark Mode): Displays format switches, resolution selector, advanced options, and Liquid Glass navigation.
2. `docs/screenshots/queue-dark.png` (Task Queue Screen, Dark Mode): Displays persistent queue empty state with brand icon.
3. `docs/screenshots/history-dark.png` (Download History Screen, Dark Mode): Displays completed tasks archive empty state.
4. `docs/screenshots/download-light.png` (Download Screen, Light Mode): Displays adaptive high-contrast light theme.

All four linked screenshots:
- Accurately represent the current Compose UI in v5.1.0.
- Contain zero references to Puemos, legacy components, or obsolete settings.
- Resolve cleanly on disk with non-zero byte size.

### B.2 Audit of `docs/screenshots/browser-subsystems-dark.png`

> v5.1.1 note: this retired screenshot was moved to
> `docs/archive/browser-subsystems-legacy-2026.png` and must not be presented as a current
> product screenshot. The findings below are retained as historical evidence.

- **File Existence:** File exists at `docs/screenshots/browser-subsystems-dark.png` (250,400 bytes, added in commit `3c69a642` on 2026-09-05).
- **Reference Check:** Verified via `git grep` that this file is **not referenced** in `README.md`, `CURRENT_PRODUCTION_ARCHITECTURE.md`, or any active documentation file in `docs/`. Its link was removed in commit `ea30c00` during the v5.0 release overhaul.
- **Image Content:** Depicts legacy settings showing "Puemos HLS Detection v1.0.0" and "GeckoView 153.0".
- **Disposition:** Preserved as an unreferenced historical repository artifact. No active markdown document links to it.

### B.3 Purge of Obsolete Fastlane Screenshot
- In `fastlane/metadata/android/en-US/images/phoneScreenshots/`, file `05_browser_subsystems_dark.png` was an identical copy of the obsolete Puemos screenshot.
- Because Fastlane directories are automatically ingested by store deployment pipelines (e.g. F-Droid), `05_browser_subsystems_dark.png` was deleted via `git rm`.
- `06_download_light.png` was renamed to `05_download_light.png` to maintain contiguous ordering (`01` through `05`).

---

## C. Public README & Architecture Diagram Verification

### C.1 14-Language Localization Visibility
The public repository landing page prominently highlights interface localization:
- **Subtitle:** *"Native Android media downloader, passive web stream discovery, and 14-language localization."*
- **Overview:** Highlights 14-language localization with full RTL support and 100% resource key parity.
- **Features Inventory:** Detailed entry for *14-Language Interface Localization & RTL Support* listing all 14 languages in alphabetical order:
  1. Arabic (`العربية` — full bidirectional Right-to-Left layout mirroring)
  2. Bengali (`বাংলা`)
  3. Simplified Chinese (`简体中文`)
  4. English (`English` — default baseline)
  5. French (`Français`)
  6. German (`Deutsch`)
  7. Gujarati (`ગુજરાતી`)
  8. Hindi (`हिन्दी`)
  9. Kannada (`ಕನ್ನಡ`)
  10. Portuguese (Brazil) (`Português (Brasil)`)
  11. Portuguese (Portugal) (`Português (Portugal)`)
  12. Spanish (`Español`)
  13. Tamil (`தமிழ்`)
  14. Telugu (`తెలుగు`)
  Plus System Default selection via Settings or Android 13+ per-app language settings.

### C.2 Component Architecture & Naming
- **yt-dlp**: Identified as the sole media acquisition engine for both normal and Browser Fallback transfers.
- **GeckoView**: Documented as the headless/isolated private browser runtime (Gradle dependency `159.0.20261022150525`).
- **VRKA Media Detector**: Identified as VRKA's first-party WebExtension (`media-detector@vrka.mvrk.com`, `assets/extensions/media-detector/`).
- **uBlock Origin**: Documented as the content/tracker filtering WebExtension (`uBlock0@raymondhill.net` v1.74.0, `assets/extensions/ublock.xpi`).
- **Puemos**: Documented as fully retired and removed.
- **FFmpeg**: Documented for local stream muxing, audio extraction, validation, and optional H.264 transcoding.

### C.3 Mermaid Diagram Integrity
The flowchart rendered in [README.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/README.md) under `## Architecture` follows strict GitHub-Flavored Mermaid standards:
- Structure: Top-to-bottom flowchart (`flowchart TD`).
- Distinct visual subgraphs for UI, Core Pipeline, Normal Flow, Browser Fallback Flow (GeckoView + uBlock Origin + VRKA Media Detector), Post-Processing, Component Updater, and Application Self-Updater.
- No orphaned nodes, syntax errors, or unescaped characters.
- Clearly illustrates Browser Fallback candidate handoff returning to `yt-dlp` for probing and transfer.

### C.4 Internal and External Link Audit
- All internal section anchors (`#screenshots`, `#features`, `#architecture`, `#build-instructions`, `#release-signing`) match headings.
- All file links (`RELEASE_EVIDENCE.md`, `SECURITY.md`, `LICENSE`) resolve.
- Installation SHA-256 matches verified production APK: `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa`.

---

## D. Documentation Consistency Audit

The following files were inspected and reconciled:
- `README.md`
- `CURRENT_PRODUCTION_ARCHITECTURE.md`
- `ANDROID_COMPATIBILITY_MATRIX.md`
- `KNOWN_LIMITATIONS.md`
- `CHANGELOG.md`
- `RELEASE_NOTES.md`
- `RELEASE_EVIDENCE.md`
- `SECURITY_REVIEW.md`
- `THIRD_PARTY_NOTICES.md`
- `fastlane/metadata/android/en-US/full_description.txt`

### Reconciled Metrics Table:

| Metric / Artifact | Documented Value | Source of Truth | Verification Status |
|---|---|---|---|
| Application ID | `com.mvrk.vrka` | `app/build.gradle.kts` | **VERIFIED** |
| Version Name | `5.1.0` | `app/build.gradle.kts` | **VERIFIED** |
| Version Code | `50100` | `app/build.gradle.kts` | **VERIFIED** |
| Target ABI | `arm64-v8a` only | `app/build.gradle.kts` | **VERIFIED** |
| SDK Range | Min 26, Target 36, Compile 37.2 | `app/build.gradle.kts` | **VERIFIED** |
| GeckoView Runtime | `159.0.20261022150525` | `app/build.gradle.kts` | **VERIFIED** |
| Interface Locales | 14 languages + System Default | `app/src/main/res/values-*` | **VERIFIED** |
| Production APK SHA-256 | `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa` | `VRKA-Android-v5.1.0.apk` | **VERIFIED** |
| Manifest Revision | `3` | `VRKA-Android-v5.1.0.manifest.json` | **VERIFIED** |
| Manifest SHA-256 | `f1a47b342061f5149b293e1361695f12b69e5fa3d5d912ac85a520a428cafa8e` | `VRKA-Android-v5.1.0.manifest.json` | **VERIFIED** |
| Manifest Sig SHA-256 | `17b3c504eb4352fcb34815975e973ab8aa6aaebc6fd1852e2719646858b4fb35` | `VRKA-Android-v5.1.0.manifest.json.asc` | **VERIFIED** |
| Pinned OpenPGP Key ID | `0xD519380BEBA1B9F7` | `vrka_release_pubkey.asc` | **VERIFIED** |
| Passing Unit Tests | **639 / 639 across 61 suites** | Gradle `:app:testDebugUnitTest` | **VERIFIED** |

---

## E. Git and Repository Verification

1. **Current Branch**: `main`
2. **Branch Tracking**: `origin/main`
3. **Single Remote Branch Invariant**: Confirmed via `git ls-remote --heads origin`:
   ```
   refs/heads/main (only remote branch)
   ```
4. **Release Tag Invariant**: Confirmed via `git ls-remote --tags origin`:
   ```
   refs/tags/v5.1.0 points to 2e5b8228b581af606a7a6c40758c2aaaef0914c9
   ```
5. **No Production Code Alterations**: No application code under `app/src/main/`, Gradle build definitions, or signing keystores were modified.
6. **Clean Working Tree**: All verification tasks and documentation adjustments leave the working tree clean and synchronized.
