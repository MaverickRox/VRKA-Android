# VRKA Android: v5.1.0 In-App Update Compatibility Audit & Technical Report

**Date**: 2026-11-01  
**Target Release**: VRKA Android v5.1.0 (`versionCode = 50100`, `versionName = "5.1.0"`)  
**Installed Client Baseline**: VRKA Android v5.0.2 (`versionCode = 50002`, `versionName = "5.0.2"`, commit `bdc3f4a`)  
**Application ID**: `com.mvrk.vrka`  
**GitHub Release**: `https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0`  
**Author**: Senior Android Release Engineer & Maintainer  

---

## 1. Executive Summary & Original Error

During in-app update testing from an existing, installed production build of **VRKA Android v5.0.2** attempting to upgrade to **v5.1.0**, the update dialog displays:
- **Version**: `5.1.0`
- **File Size**: `149.9 MB` (157,192,535 bytes)
- **Release notes**: Successfully retrieved from GitHub API.
- **Terminal Error Encountered**:
  ```text
  APK verification failed: APK filename does not match the expected release
  ```

This report documents the deep-dive source inspection of the v5.0.2 client binary, provides the mathematical proof demonstrating why no server-side GitHub asset or metadata change can overcome this defect in already-installed v5.0.2 clients, details the actual release asset inventory, and establishes the verified migration path.

---

## 2. In-App Updater Contract Comparison: v5.0.2 Expectation vs v5.1.0 Release

Inspection of `git show v5.0.2:app/src/main/java/com/mvrk/vrka/update/AppUpdateManager.kt` reveals the exact release contract required by the v5.0.2 parser:

| Contract Property | v5.0.2 Client Expectation | Actual Published v5.1.0 Release | Match Status |
| :--- | :--- | :--- | :--- |
| **Release Tag** | Matches `^v(0\|[1-9]\d*)\.(0\|[1-9]\d*)\.(0\|[1-9]\d*)$` | `v5.1.0` | **MATCH** |
| **Release Type** | `draft == false` and `prerelease == false` | `draft: false`, `prerelease: false` | **MATCH** |
| **Semantic Version** | `5.1.0` (higher than current `5.0.2`) | `5.1.0` (newer than `5.0.2`) | **MATCH** |
| **APK Asset Name** | Strictly equals `canonicalApkName(version)`: `VRKA-Android-v5.1.0.apk` | `VRKA-Android-v5.1.0.apk` | **MATCH** |
| **APK Download URL** | HTTPS URL from approved domain (`github.com`) | `https://github.com/MaverickRox/VRKA-Android/releases/download/v5.1.0/VRKA-Android-v5.1.0.apk` | **MATCH** |
| **Asset Checksum** | 64-char hex in asset `digest` or `SHA256SUMS` | `sha256:ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a` | **MATCH** |
| **APK File Size** | Positive `Long` matching asset length | `157,192,535` bytes | **MATCH** |
| **Checksum Asset Name** | `SHA256SUMS` or `checksums.sha256` | `SHA256SUMS` | **MATCH** |

**Conclusion on Discovery**:  
Release discovery, version comparison, asset selection, URL validation, and checksum extraction **match 100%**. There is zero defect in release discovery or metadata ingestion.

---

## 3. Root Cause Analysis: The Hard-Coded v5.0.2 Client Defect

The failure occurs **after download completion**, during internal verification inside the Android `WorkManager` background worker.

### 3.1 Source Inspection of v5.0.2 Execution Path

#### Step 1: Worker Enqueue & Input Data
In `AppUpdateManager.kt` (`downloadAndInstall`):
```kotlin
workManager.enqueueUniqueWork(
    AppUpdateDownloadWorker.WORK_NAME,
    AppUpdateDownloadPolicy.UNIQUE_WORK_POLICY,
    AppUpdateDownloadPolicy.createWorkRequest(release)
)
```
InputData values passed to the worker:
- `KEY_DOWNLOAD_URL` = `"https://github.com/MaverickRox/VRKA-Android/releases/download/v5.1.0/VRKA-Android-v5.1.0.apk"`
- `KEY_APK_NAME` = `"VRKA-Android-v5.1.0.apk"`
- `KEY_EXPECTED_SIZE` = `157192535L`
- `KEY_EXPECTED_SHA256` = `"ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a"`
- `KEY_TARGET_VERSION` = `"5.1.0"`

#### Step 2: File Instantiation in `AppUpdateDownloadWorker.kt`
Lines 49–54 of `AppUpdateDownloadWorker.kt`:
```kotlin
val updatesDirectory = File(applicationContext.filesDir, "updates").canonicalFile
val targetFile = File(updatesDirectory, apkFileName).canonicalFile
val tempFile = File(updatesDirectory, "$apkFileName.download.tmp")
val fallbackStageFile = File(updatesDirectory, "$apkFileName.stage.tmp")
```
For `v5.1.0`:
- `targetFile` = `/data/user/0/com.mvrk.vrka/files/updates/VRKA-Android-v5.1.0.apk`
- `tempFile` = `/data/user/0/com.mvrk.vrka/files/updates/VRKA-Android-v5.1.0.apk.download.tmp`

#### Step 3: Download Streaming
The worker connects to `downloadUrl`, follows the redirect to `objects.githubusercontent.com`, and writes 157,192,535 bytes into `tempFile`.
`AppUpdateDownloadPolicy.validatePayload` verifies that:
- `tempFile.length() == 157192535L`
- `sha256(tempFile) == "ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a"`
Both match perfectly.

#### Step 4: The Fatal Execution Line
At **line 177** of `AppUpdateDownloadWorker.kt` (commit `bdc3f4a`):
```kotlin
val archiveError = AppUpdateManager.verifyDownloadedApk(
    context = applicationContext,
    file = tempFile,                     // <-- FATAL DEFECT: Passing tempFile!
    targetVersion = targetVersion,
    expectedSizeBytes = expectedSizeBytes,
    expectedSha256 = expectedSha256,
)
if (archiveError != null) throw PermanentAppUpdateException(archiveError)
```

#### Step 5: Inside `verifyDownloadedApk`
At **lines 614–617** of `AppUpdateManager.kt` (commit `bdc3f4a`):
```kotlin
val canonicalFile = runCatching { file.canonicalFile }.getOrNull()
    ?: return "APK verification failed: update file path is invalid"
...
if (!isValidDownloadTarget(targetVersion, canonicalFile.name)) {
    return "APK verification failed: APK filename does not match the expected release"
}
```
And `isValidDownloadTarget` (line 580):
```kotlin
fun isValidDownloadTarget(targetVersion: String, apkFileName: String): Boolean {
    val version = parseCanonicalVersion(targetVersion) ?: return false
    return apkFileName == canonicalApkName(version)
}

private fun canonicalApkName(version: SemanticVersion): String = "VRKA-Android-v$version.apk"
```

#### Step 6: The Filename Comparison Failure
- `canonicalFile.name` evaluated on `tempFile`:
  $$\text{canonicalFile.name} = \text{"VRKA-Android-v5.1.0.apk.download.tmp"}$$
- `canonicalApkName(version)` for `"5.1.0"`:
  $$\text{canonicalApkName("5.1.0")} = \text{"VRKA-Android-v5.1.0.apk"}$$
- Equality check:
  $$\text{"VRKA-Android-v5.1.0.apk.download.tmp"} == \text{"VRKA-Android-v5.1.0.apk"} \implies \mathbf{FALSE}$$

Because this evaluates to `false`, `verifyDownloadedApk` returns:
`"APK verification failed: APK filename does not match the expected release"`.

Line 185 of `AppUpdateDownloadWorker.kt` then executes:
```kotlin
throw PermanentAppUpdateException("APK verification failed: APK filename does not match the expected release")
```
The exception is caught, `tempFile` is deleted, `STATE_FAILED` is written to `SharedPreferences`, and the user is shown the exact error reported.

---

## 4. Mathematical Proof: Impossibility of Server-Side / Asset-Only Remediation

Can any reconfiguration, renaming, or aliasing of assets on GitHub Releases resolve this defect for existing v5.0.2 installations?

Let:
- $V \in \text{SemanticVersion}$ be the target release version (`"5.1.0"`).
- $C(V) = \text{"VRKA-Android-v"} + V + \text{".apk"}$ be the canonical APK filename.
- $A$ be the filename of any asset published in the GitHub Release.

### Constraint 1: Discovery Gate (Compiled in v5.0.2)
In `parseReleaseJsonUnchecked`:
```kotlin
val expectedVersionApk = canonicalApkName(version)
if (assetName == expectedVersionApk) { chosenAsset = asset }
```
Therefore, for the client to select asset $A$ for download:
$$A = C(V) = \text{"VRKA-Android-v5.1.0.apk"}$$
If $A \neq C(V)$, `chosenAsset` is `null`, and the client aborts with "no update available".

### Constraint 2: Temporary File Creation (Compiled in v5.0.2)
The worker hardcodes:
$$T_{\text{temp}} = A + \text{".download.tmp"}$$

### Constraint 3: Verification Gate (Compiled in v5.0.2)
`verifyDownloadedApk` is invoked on $T_{\text{temp}}$ and requires:
$$\text{name}(T_{\text{temp}}) = C(V)$$

### The Contradiction
Substituting Constraint 2 and Constraint 1 into Constraint 3:
$$C(V) + \text{".download.tmp"} = C(V)$$
Since the suffix `".download.tmp"` is a non-empty string of 13 ASCII characters:
$$\text{length}(C(V) + \text{".download.tmp"}) = \text{length}(C(V)) + 13 > \text{length}(C(V))$$
Two strings of different lengths cannot be equal.

$$\forall V \in \text{SemanticVersion}, \quad C(V) + \text{".download.tmp"} \neq C(V)$$

Thus, the set of asset names $\mathcal{S}$ that can simultaneously satisfy Discovery and Verification in v5.0.2 is empty:
$$\mathcal{S} = \emptyset$$

**Conclusion**: The defect is **Category D (Hard-Coded Client Logic Defect)**. It is physically and logically impossible for any GitHub release asset configuration, renaming, aliasing, or metadata injection to allow an installed v5.0.2 client to self-update.

---

## 5. Official v5.1.0 Release Asset Inventory & Verification

The existing official release assets for `v5.1.0` have been audited and verified:

| Property | Value |
| :--- | :--- |
| **Release Tag** | `v5.1.0` |
| **Target Commit** | `1794d38f4c2505d5cf07705b1f23cc10581f1341` |
| **APK Asset Name** | `VRKA-Android-v5.1.0.apk` |
| **APK Download URL** | `https://github.com/MaverickRox/VRKA-Android/releases/download/v5.1.0/VRKA-Android-v5.1.0.apk` |
| **APK Size** | `157,192,535` bytes |
| **APK SHA-256** | `ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a` |
| **Checksum Asset Name** | `SHA256SUMS` |
| **Checksum Download URL** | `https://github.com/MaverickRox/VRKA-Android/releases/download/v5.1.0/SHA256SUMS` |
| **Checksum Size** | `815` bytes |
| **Checksum SHA-256** | `717b6123eca4d85727493d003e927ba237c5e3e65bf49ad97109dd98ba7ed4ca` |
| **Production Key Fingerprint** | `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA` |
| **Key Match with v5.0.2** | **100% Exact Match** (RSA 4096-bit, APK Signature Scheme v2) |
| **Target Architecture** | `arm64-v8a` |
| **Min SDK / Target SDK** | `26` / `36` |
| **Debuggable Flag** | `android:debuggable="false"` |

---

## 6. Full Update-Pipeline Test & Hardware Verification

### 6.1 Test Pipeline Results
1. **GitHub Releases API Query**: **PASS**. Returns valid JSON with tag `v5.1.0`, name `"VRKA Android 5.1.0"`, and assets `VRKA-Android-v5.1.0.apk` and `SHA256SUMS`.
2. **v5.0.2 Discovery Parsing**: **PASS**. `AppUpdateManager.parseReleaseJson` creates `AppReleaseInfo` with target version `5.1.0`.
3. **v5.0.2 Update Prompt**: **PASS**. `AppUpdateDialog` renders version `5.1.0`, size `149.9 MB`, and release notes.
4. **v5.0.2 Download Execution**: **PASS**. Streams 157,192,535 bytes to `/data/user/0/com.mvrk.vrka/files/updates/VRKA-Android-v5.1.0.apk.download.tmp`.
5. **Payload Size & SHA-256**: **PASS**. Byte length and SHA-256 hash match release metadata.
6. **Pre-Staging Verification**: **FAIL (Expected)**. Fails at `AppUpdateManager.verifyDownloadedApk` due to hardcoded `.download.tmp` comparison against `canonicalApkName`.

### 6.2 Target Hardware Verification
- **Target Device Specified**: `OnePlus 11 / CPH2447`.
- **Status**: The physical OnePlus 11 was **NOT connected** to the environment (`adb devices` reports only `emulator-5554`).
- **Hardware Finding**: In strict compliance with guidelines, physical OnePlus 11 hardware validation is recorded as **incomplete / unavailable**. No fabricated device results are reported.

---

## 7. Migration & Non-Version-Bumping Solution

### Why No Non-Version-Bumping Server Solution Exists
Because the validation check executes inside the compiled `AppUpdateDownloadWorker` Dalvik bytecode on users' physical devices, **no server-side or release-asset solution exists that can force a v5.0.2 client to bypass its own compiled code**.

### The Minimum Possible Recovery Route
Existing v5.0.2 users must perform a **one-time manual installation** of `VRKA-Android-v5.1.0.apk`:
1. Users download `VRKA-Android-v5.1.0.apk` from the official release page:
   `https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0`
2. Open the file on the device and tap **Update**.
3. *Alternative (via ADB)*:
   ```bash
   adb install -r VRKA-Android-v5.1.0.apk
   ```

### 100% Data Preservation Guarantee
- Both v5.0.2 and v5.1.0 share the identical package name `com.mvrk.vrka`.
- Both are signed with the exact same production RSA 4096-bit certificate (`9BEFDBF4FB...`).
- Android's Package Manager validates the signature match and performs an **in-place update**.
- **All application data, preferences, download queues, download history, and media files are completely preserved without data loss.**

---

## 8. Documentation Audit & Reconciliation

The following documentation files have been updated and synchronized with the actual implementation and release assets:
- [docs/VRKA_V5.1.0_UPDATE_COMPATIBILITY_AUDIT.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/docs/VRKA_V5.1.0_UPDATE_COMPATIBILITY_AUDIT.md): This report.
- [KNOWN_LIMITATIONS.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/KNOWN_LIMITATIONS.md): Documents the v5.0.2 in-app updater validation failure and the manual installation requirement.
- [README.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/README.md): Documents the manual upgrade note under the Installation section.
- [CHANGELOG.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/CHANGELOG.md): Documents the v5.0.2 upgrade requirement under Known Limitations.
- [RELEASE_NOTES.md](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/RELEASE_NOTES.md): Preserves the exact verified v5.1.0 checksums and signing certificate.
- [SHA256SUMS](file:///f:/Projects/VRKA-Android-v5.1.0-release-base/SHA256SUMS): Matches the published GitHub asset byte-for-byte.
