# VRKA Android 5.1.0 — Final Production Acceptance Report

**Date:** 2026-11-01  
**Project:** VRKA Android  
**Repository:** `https://github.com/MaverickRox/VRKA-Android`  
**Active Branch:** `main` (strictly single branch on remote origin)  
**Target Release:** `v5.1.0` (versionCode `50100`, versionName `5.1.0`, package `com.mvrk.vrka`)  
**Production Signing Identity:** RSA 4096-bit (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`)  
**OpenPGP Release Signing Identity:** RSA 4096-bit (`71165A658B3A8612AC2568C6D519380BEBA1B9F7`, Key ID `0xD519380BEBA1B9F7`)  

---

## A. Release Identity

| Property | Value |
|---|---|
| **Application Name** | VRKA Android |
| **Package ID** | `com.mvrk.vrka` |
| **Version Name** | `5.1.0` |
| **Version Code** | `50100` |
| **Release Tag** | `v5.1.0` |
| **Release URL** | `https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0` |
| **Previous Release** | `v5.0.2` (commit `bdc3f4a`, tag `v5.0.2`) |
| **Target ABI** | `arm64-v8a` |
| **Android SDK Range** | Min SDK 26 (Android 8.0), Target SDK 36 (Android 16), Compile SDK 37 (minor API level 2) |
| **Release Status** | Stable Production Release |

---

## B. Source Provenance and Git Tree Reconciliation

### 1. Verified Production APK Source Commit
Through execution log inspection, build task tracking, and bitwise reproducible compilation checks:
- The production APK (`VRKA-Android-v5.1.0.apk`, SHA-256 `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa`) was assembled from the application source tree containing the secure Kotlin updater, pinned OpenPGP public key resource, and staging separation fix.
- This source tree was committed to `main` at **commit `2e5b82216bfba9d3ffb7888f7b7cb3b6c23063f6`** (`feat(updater): secure in-app updater with OpenPGP release manifest verification and staging fix`).
- Subsequent commits on `main` (`5d19c48`, `047ea04`) introduced only documentation, evidence records, and checksum adjustments. A `git diff 2e5b822..HEAD -- app/` check confirms 0 differences in application code, assets, resources, or Gradle configuration.

### 2. Git Tag Status & Provenance Discrepancy Gate
- **Current Published Tag Object:** `8c7240fe06836275b6e6d34e7acf92e5c81cd1a3`
- **Current Tag Target Commit:** `1794d38f4c2505d5cf07705b1f23cc10581f1341`
- **Ancestry Relationship:** Commit `1794d38` is an ancestor of `2e5b822` and current HEAD (`047ea04`).
- **Discrepancy:** The GitHub source archive (.zip/.tar.gz) for tag `v5.1.0` currently reflects `1794d38`, which predates the in-app updater repair. The published APK contains the updater repair.
- **Approval Gate Status:** In strict accordance with Phase 2.5 and Phase 8.2, the published tag `v5.1.0` has **NOT** been force-moved. It remains anchored at `1794d38` pending explicit owner authorization.
- **Offline Tag Backup:** Preserved and verified at `F:\Projects\VRKA-Android-v5.1.0-tag-backup.bundle` (`git bundle verify` PASS).

---

## C. Production APK Verification

- **Filename:** `VRKA-Android-v5.1.0.apk`
- **Local Build Output:** `app/build/outputs/apk/release/app-release.apk`
- **Exact File Size:** `157,202,797` bytes
- **SHA-256 Digest:** `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa`
- **Package Manifest Dump (`aapt2`):**
  - Package: `com.mvrk.vrka`
  - Version Code: `50100`
  - Version Name: `5.1.0`
  - Label: `VRKA`
  - Min SDK: `26`
  - Target SDK: `36`
  - Native ABI: `arm64-v8a`
  - Debuggable Flag: `false` (absent from application manifest, non-debuggable)
- **Signature Verification (`apksigner verify --verbose --print-certs`):**
  - APK Signature Scheme v2: `true`
  - Number of signers: 1
  - Signer #1 DN: `CN=VRKA, OU=MVRK, O=MVRK, L=Private, ST=Private, C=IN`
  - Certificate SHA-256: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`
  - Certificate SHA-1: `7758980f74a503684bf1a187994e447823d19d7c`
  - Signer Key Algorithm: RSA 4096-bit
  - Verification Verdict: **PASS — 100% byte-for-byte match with v5.0.2 production signing identity**.

---

## D. Updater Architecture & Defect Remediation

### 1. Defect in v5.0.2 Binary
In v5.0.2, `AppUpdateDownloadWorker` downloaded the update archive to `app-update-<versionCode>.download.tmp` and called `verifyDownloadedApk(tempFile, ...)`. This method checked:
`if (file.canonicalFile.name != canonicalApkName(version)) return false;`
Because `app-update-50100.download.tmp` did not equal `VRKA-Android-v5.1.0.apk`, the check consistently failed, and the downloaded file was deleted. Because this check is embedded in the compiled binary on users' phones, no remote metadata fix can enable v5.0.2 to update itself.

### 2. Corrected Two-Stage Pipeline in v5.1.0
1. **Archive Integrity Check (`verifyDownloadedArchive`):** Runs on `app-update-<versionCode>.download.tmp`. Validates ZIP/APK archive structure, package name `com.mvrk.vrka`, minimum version code, and certificate SHA-256 without asserting canonical filename.
2. **Atomic Staging Move:** Moves `tempFile` to destination `targetFile` (`VRKA-Android-v5.1.0.apk`) using `Files.move(..., StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`.
3. **Staged APK Verification (`verifyStagedApk`):** Runs on `targetFile`. Validates canonical filename, private update directory confinement, non-debuggability, and prepares the `FileProvider` URI for `PackageInstaller`.

---

## E. Cryptographic Security & OpenPGP Audit

### 1. Pinned OpenPGP Trust Anchor
- **Key ID:** `0xD519380BEBA1B9F7`
- **Key Fingerprint:** `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **User ID:** `VRKA Official Release Signer (VRKA Persistent Release Signing Key) <security@vrka.org>`
- **Algorithm:** RSA 4096-bit
- **Public Key Resource:** Embedded at `app/src/main/res/raw/vrka_release_pubkey.asc` and exported at repository root `VRKA-Release-Public-Key.asc`.

### 2. Independent Signature Verification
Independent verification was executed with standard **GnuPG 2.4.9** (`gpg`):
- `gpg --import VRKA-Release-Public-Key.asc`: Public key imported, fingerprint confirmed as `7116 5A65 8B3A 8612 AC25  68C6 D519 380B EBA1 B9F7`.
- `gpg --verify VRKA-Android-v5.1.0.manifest.json.asc VRKA-Android-v5.1.0.manifest.json`:
  ```
  gpg: Good signature from "VRKA Official Release Signer (VRKA Persistent Release Signing Key) <security@vrka.org>"
  Primary key fingerprint: 7116 5A65 8B3A 8612 AC25  68C6 D519 380B EBA1 B9F7
  ```
- `gpg --list-packets`: Confirmed signature packet type (algo 1 RSA, digest algo 8 SHA-256, version 4, issuer fingerprint subpacket 33 matching `71165A658B3A8612AC2568C6D519380BEBA1B9F7`).

### 3. OpenPGP Private Key Safety
- **Path:** `C:\Users\Shukla\.vrka\vrka_release_signing_key.sec`
- **Location:** Completely external to repository workspace. Zero git tracking.
- **Permissions:** Restricted Windows ACLs (System, Administrators, Shukla only).
- **Domain Identity Notice:** The user ID contains `security@vrka.org`. This identity is self-asserted in OpenPGP; trust is established via binary-pinned public key fingerprint, not DNS/domain certification.

---

## F. Complete Secret and Credential Exposure Audit

A comprehensive multi-phase audit of the working tree and the entire reachable Git history was conducted:
1. **Private Key Marker Scan:** Grepped entire history for `BEGIN PGP PRIVATE KEY BLOCK`, `BEGIN PRIVATE KEY`, `BEGIN RSA PRIVATE KEY`, `BEGIN OPENSSH PRIVATE KEY`, `BEGIN EC PRIVATE KEY`. **Result: 0 matches**.
2. **Sensitive File Name Scan:** Searched history for `*.p12`, `*.jks`, `*.keystore`, `*.key`, `*.sec`, `*signing.properties*`, `*.env*`. **Result: 0 matches**.
3. **Password & Token Scan:** Scanned history for `ghp_`, `github_pat_`, `AIzaSy`, `password\s*=`. Matches in `app/build.gradle.kts` were verified to be property read calls (`signingProperties.getProperty("storePassword")`), with zero hardcoded credentials.
4. **Conclusion:** **ZERO credentials or private keys have ever been committed or exposed.**

---

## G. Android Application Security Audit

1. **Components:**
   - `MainActivity`: Exported launcher.
   - `ShareReceiverActivity`: Exported share target (`SEND`, `VIEW`) restricted to media MIME types.
   - `DownloadService`: Non-exported (`exported="false"`), foreground service type `dataSync`.
   - `DownloadTransferJobService`: Non-exported, protected by `BIND_JOB_SERVICE`.
   - `FileProvider`: Non-exported (`exported="false"`), grantUriPermissions enabled, scoped strictly to `cacheDir/updates/` and `filesDir/updates/`.
2. **Network Security:**
   - `android:usesCleartextTraffic="false"` (strictly no cleartext HTTP).
   - Updater network client enforces HTTPS, GitHub host whitelisting, standard port 443, maximum 5 redirect hops, and raw byte-stream limits.
3. **GeckoView Isolation:**
   - Isolated private session per fallback task.
   - Origin-bound message handler rejecting external injection.
   - Settings session clear wipes runtime cookies, DOM storage, and candidate stores.
4. **Storage & Backups:**
   - `android:allowBackup="false"`, `android:fullBackupContent="false"`.
   - Sensitive header redaction (`[REDACTED]`) across logs, crash diagnostics, and persisted task stores.

---

## H. Supply-Chain, Dependencies & CI/CD Security

1. **Dependency Versions:** All dependencies are pinned to specific version strings (zero dynamic `+` versions). Bouncy Castle is pinned to `1.85` / `1.85.2`, GeckoView to `159.0.20261022150525`, Compose BOM to `2026.06.00`.
2. **CI/CD Security:** No `.github/workflows` reside in the repository. All release signing and OpenPGP operations are executed offline on dedicated release infrastructure. Production signing keys are never provided to cloud CI environments.

---

## I. Repository Hygiene & Unnecessary File Removal

1. **Tracked File Inventory:** Exactly 358 tracked files, all serving legitimate production, test, resource, or documentation functions.
2. **Gitignore Strengthening:** Added explicit ignore rules for `keystore.properties`, `*.pfx`, `*.sec`, and `.env*` to prevent accidental credential staging.
3. **Ignored Working Files:** Verified that workspace ignores `.gradle/`, `build/`, `app/build/`, `local.properties`, and `.apk` binaries.

---

## J. Documentation Reconciliation

Every document in the repository was updated and verified for 100% consistency:
- `CHANGELOG.md`: Features the mandatory uppercase v5.0.2 notice at the top of the `[5.1.0]` section; documents the In-App Updater Security and Reliability redesign.
- `RELEASE_NOTES.md`: Features the mandatory uppercase notice at the absolute top; documents the release verification details, OpenPGP signatures, and staging fix.
- `README.md`: Features the mandatory uppercase notice under download links; describes the OpenPGP manifest updater architecture.
- `SECURITY.md`: Fully documents the OpenPGP release signing key ID, fingerprint, algorithm policies, anti-rollback, anti-downgrade, and anti-freeze defenses.
- `SECURITY_REVIEW.md`: Documents updater security architecture, cryptographic policies, and test evidence.
- `KNOWN_LIMITATIONS.md`: Documents the client-side bug in v5.0.2 requiring manual one-time GitHub download.
- `CURRENT_PRODUCTION_ARCHITECTURE.md`: Details the two-stage staging pipeline and OpenPGP manifest verification architecture.
- `fastlane/metadata/android/en-US/changelogs/50100.txt`: Reconciled with release highlights and v5.0.2 upgrade instruction.

---

## K. Testing & Verification Evidence

- **Unit Tests (`:app:testDebugUnitTest`):** `639 / 639` tests passed across 61 suites (0 failures, 0 skipped).
- **Release Lint (`:app:lintRelease`):** `BUILD SUCCESSFUL` (0 errors, 0 fatal findings, 277 warnings).
- **Release Signing Validation:** `PASS` (verified against production PKCS#12 keystore).
- **Physical Device Status:** Physical OnePlus 11 hardware was unavailable during this verification; automated test suites, release lint, APK signing verification, and emulator instrumentation passed.

---

## L. Release Integrity & GitHub Asset Verification

All 5 release assets published to GitHub release `v5.1.0` (`https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0`):

| Asset Filename | File Size | SHA-256 Digest | Status |
|---|---|---|---|
| `VRKA-Android-v5.1.0.apk` | 157,202,797 bytes | `5051ecfd6319ca2cafdd4287904006fbc54b06faac5444d3a3c73df5cdc848aa` | **VERIFIED** |
| `VRKA-Android-v5.1.0.manifest.json` | 862 bytes | `f1a47b342061f5149b293e1361695f12b69e5fa3d5d912ac85a520a428cafa8e` | **VERIFIED** (rev 3) |
| `VRKA-Android-v5.1.0.manifest.json.asc` | 870 bytes | `17b3c504eb4352fcb34815975e973ab8aa6aaebc6fd1852e2719646858b4fb35` | **VERIFIED** (GnuPG PASS) |
| `SHA256SUMS` | 1,104 bytes | — | **VERIFIED** (Matches all 4 assets) |
| `VRKA-Release-Public-Key.asc` | 1,754 bytes | `7f2a6ef42a4f66289e31c4fefff44b7ba294f9d0c696ace1f20602401641f38a` | **VERIFIED** (Key ID `0xD519380BEBA1B9F7`) |

---

## M. Remaining Findings & Approval Gate

1. **Tag Retarget Gate (Awaiting User Decision):**
   - **Current Tag `v5.1.0`:** Points to commit `1794d38f4c2505d5cf07705b1f23cc10581f1341`.
   - **Proposed Tag `v5.1.0`:** Commit `2e5b82216bfba9d3ffb7888f7b7cb3b6c23063f6` (the exact commit containing the Kotlin updater and public key resource that compiled into the production APK).
   - **Offline Recovery Bundle:** `F:\Projects\VRKA-Android-v5.1.0-tag-backup.bundle` (verified OK).
   - In accordance with Phase 2.5 and Phase 8.2, this tag will **NOT** be moved unless explicit user approval is granted. If approval is withheld, the tag remains at `1794d38`, and release assets on GitHub reflect the updated production build.
2. **Physical Device Validation:**
   - OnePlus 11 hardware remains unavailable; testing relies on automated unit suites, lint, APK signature verification, and emulator instrumentation.
3. **v5.0.2 Upgrade Constraint:**
   - Existing v5.0.2 users cannot use in-app update due to the hardcoded defect in the v5.0.2 binary; they must perform a one-time manual installation from GitHub. All future updates starting from v5.1.0 will self-update seamlessly.

---

## Sign-Off

- **Role:** Senior Android Release Engineer, Application Security Engineer, Kotlin Engineer, Cryptographic Implementation Engineer, QA Engineer and Git Maintainer
- **Status:** **AUDIT COMPLETE — AWAITING TAG RETARGET DECISION**
