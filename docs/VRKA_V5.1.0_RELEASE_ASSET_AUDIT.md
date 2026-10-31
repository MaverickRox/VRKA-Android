# VRKA Android: v5.1.0 Release Asset & Repository Audit Report

**Date**: 2026-10-31  
**Project**: VRKA Android  
**Target Release**: v5.1.0 (versionCode `50100`, versionName `"5.1.0"`)  
**Package Name**: `com.mvrk.vrka`  
**Release Tag**: `v5.1.0`  
**Release Commit**: `1794d38f4c2505d5cf07705b1f23cc10581f1341`  
**Repository**: `https://github.com/MaverickRox/VRKA-Android`  
**Audit Author**: Senior Android Release Engineer & Repository Maintainer  

---

## 1. Executive Summary & Scope Constraints

This audit was conducted to investigate reported in-app update issues for devices running VRKA Android v5.0.2 attempting to upgrade to v5.1.0, verify the existing GitHub release asset configuration, evaluate whether server-side asset adjustments could remediate the failure, and reconcile repository documentation.

### Scope Boundaries & Directives
1. **No Version Increment**: Under no circumstances shall v5.1.1 or any other intermediate release version be minted.
2. **No Code Modification**: Application source code is fixed at release commit `1794d38`.
3. **No History Rewrites**: The Git commit graph on `main` is published and permanent.
4. **No Additional Branches**: Exactly one branch exists on GitHub: `refs/heads/main`.
5. **Preservation of Signing Key**: Production RSA 4096-bit signing identity is strictly maintained across releases.
6. **Stop & Report Mandate**: If investigation demonstrates a Category D defect (a client-side code defect in the compiled v5.0.2 APK installed on user devices), release engineers must not attempt fragile workarounds or premature new releases, but document the root cause and provide clear manual migration instructions.

---

## 2. Phase 1: Repository & Release Integrity Audit

### 2.1 Git Topology and Branch Consolidation
- **Active Local Branch**: `main`
- **Remote Tracking**: `origin/main` (up to date)
- **Single Remote Branch**: GitHub repository contains exactly one branch: `refs/heads/main`.
- **Exclusion of Contaminated Commits**:
  - `8ca03090dc8bdaf4b95f9e390fc4d74576b302ee` (Excluded; NOT in `main` history)
  - `25f7cb4db5cc93a531e9ae8787d7b32e56ee26f2` (Excluded; NOT in `main` history)
  Both commits are absent from `main` ancestry (`git merge-base --is-ancestor` returns false).
- **Offline Recovery Bundle**:
  - Bundle Path: `F:\Projects\VRKA-Android-main-before-v5.1.0-correction.bundle`
  - Size: 31,316,078 bytes (29.8 MB)
  - Verification: `git bundle verify` passed successfully with HEAD pointing to `8ca0309`.
- **Release Tags**:
  - `v5.0.2` -> `bdc3f4a43fc833074d2b27a37213456ff8452ef3`
  - `v5.1.0` -> `1794d38f4c2505d5cf07705b1f23cc10581f1341` (HEAD of `main`)

---

## 3. Phase 2: In-App Updater Root Cause Analysis & Defect Mechanics

### 3.1 Reported Failure
Users on v5.0.2 attempting in-app self-update to v5.1.0 encounter the following error during the download/verification phase:
```text
APK verification failed: APK filename does not match the expected release
```

### 3.2 Code Path Dissection in v5.0.2

Inspection of the source code at release tag `v5.0.2` (`git show v5.0.2:app/src/main/java/com/mvrk/vrka/data/updater/...`) reveals the exact sequence of events:

#### Step 1: Release Discovery & Validation
In `AppUpdateManager.kt` (`parseReleaseJsonUnchecked`, lines 403–462):
```kotlin
val expectedApkName = canonicalApkName(cleanVersion) // Returns "VRKA-Android-v5.1.0.apk"
val apkAsset = releaseAssets.firstOrNull { it.name == expectedApkName }
```
- The discovery logic checks that the asset name equals `VRKA-Android-v5.1.0.apk`.
- It validates that asset size > 0 and matches the expected size.
- It parses SHA-256 digests from `SHA256SUMS` or the release body matching the pattern `sha256:ca5c74...`.
- **Result**: Release discovery **succeeds completely**. The update object `AppUpdate(version="5.1.0", downloadUrl=..., apkFileName="VRKA-Android-v5.1.0.apk", ...)` is created.

#### Step 2: Download Execution in Worker
In `AppUpdateDownloadWorker.kt` (`doWork`, lines 45–185):
```kotlin
val apkFileName = inputData.getString(KEY_APK_NAME) ?: return Result.failure(...)
val targetFile = File(updatesDirectory, apkFileName)
val tempFile = File(updatesDirectory, "$apkFileName.download.tmp")
```
- For v5.1.0, `apkFileName` is `"VRKA-Android-v5.1.0.apk"`.
- The worker creates `tempFile`:
  `File(updatesDirectory, "VRKA-Android-v5.1.0.apk.download.tmp")`
- The bytes are streamed from GitHub via HTTPS into `tempFile`.

#### Step 3: Verification Pre-Condition Failure
In `AppUpdateDownloadWorker.kt` (lines 175–182):
```kotlin
AppUpdateManager.verifyDownloadedApk(
    context = applicationContext,
    file = tempFile,                     // <-- NOTE: Passing tempFile!
    expectedDigest = expectedDigest,
    targetVersion = targetVersion,
    targetVersionCode = targetVersionCode,
    isDevelopmentBuild = isDevelopmentBuild
)
tempFile.renameTo(targetFile)
```
- The worker invokes `verifyDownloadedApk` on `tempFile` *before* renaming it to `targetFile`.

#### Step 4: The Defect in `verifyDownloadedApk`
In `AppUpdateManager.kt` (lines 610–617):
```kotlin
fun verifyDownloadedApk(
    context: Context,
    file: File,
    expectedDigest: String?,
    targetVersion: String,
    targetVersionCode: Long,
    isDevelopmentBuild: Boolean = false
) {
    val canonicalFile = file.canonicalFile
    if (!isValidDownloadTarget(targetVersion, canonicalFile.name)) {
        throw AppUpdateVerificationException("APK filename does not match the expected release")
    }
    ...
}
```
And `isValidDownloadTarget` (line 697):
```kotlin
fun isValidDownloadTarget(version: String, candidate: String): Boolean {
    return candidate == canonicalApkName(version) // "VRKA-Android-v5.1.0.apk"
}
```
- Because `file` is `tempFile`, `canonicalFile.name` is `"VRKA-Android-v5.1.0.apk.download.tmp"`.
- `"VRKA-Android-v5.1.0.apk.download.tmp"` does NOT equal `"VRKA-Android-v5.1.0.apk"`.
- `isValidDownloadTarget` returns `false`.
- `AppUpdateVerificationException("APK filename does not match the expected release")` is thrown immediately.
- The download is aborted, `tempFile` is deleted, and the user receives the verification error.

---

### 3.3 Logical Proof: Why Release Asset Manipulation Cannot Solve the Defect

Can modifying GitHub release assets or metadata allow v5.0.2 clients to update in-app?

Let $A$ be the asset filename published on GitHub.
Let $V$ be the version string (`"5.1.0"`).
Let $C(V)$ be the canonical APK name (`"VRKA-Android-v5.1.0.apk"`).

1. **Discovery Requirement (v5.0.2 Client)**:
   For `parseReleaseJsonUnchecked` to recognize an asset as the valid APK, the asset name $A$ must satisfy:
   $$A = C(V) = \text{"VRKA-Android-v5.1.0.apk"}$$
   If $A \neq C(V)$, the release parser ignores the asset entirely and reports "no update available".

2. **Download Target Construction (v5.0.2 Client)**:
   When $A$ is selected, the download worker assigns:
   $$T_{\text{temp}} = A + \text{".download.tmp"} = C(V) + \text{".download.tmp"}$$

3. **Verification Requirement (v5.0.2 Client)**:
   `verifyDownloadedApk` checks whether:
   $$\text{name}(T_{\text{temp}}) = C(V)$$
   Substituting $T_{\text{temp}}$:
   $$C(V) + \text{".download.tmp"} = C(V)$$
   Since the string `".download.tmp"` is non-empty, this equality is **false for all possible version strings**:
   $$\forall V: C(V) + \text{".download.tmp"} \neq C(V)$$

4. **Hypothetical Asset Renaming**:
   If we rename the GitHub asset to $A' = \text{"VRKA-Android-v5.1.0.apk.download.tmp"}$:
   - `parseReleaseJsonUnchecked` tests:
     $$A' \stackrel{?}{=} C(V) \iff \text{"VRKA-Android-v5.1.0.apk.download.tmp"} == \text{"VRKA-Android-v5.1.0.apk"} \implies \text{False}$$
   - Discovery fails; the worker never runs.

**Conclusion**:
The set of asset names satisfying both Discovery and Verification in v5.0.2 is empty:
$$\mathcal{S}_{\text{valid}} = \{ A \mid A = C(V) \land A + \text{".download.tmp"} = C(V) \} = \emptyset$$

The defect is **Category D**: an immutable client-side logic error compiled into the v5.0.2 APK running on users' physical devices. No remote release configuration, asset renaming, or metadata injection can alter the compiled execution flow of the installed client.

---

## 4. Phase 3: Release Asset Inventory & Cryptographic Verification

The published v5.1.0 assets on GitHub have been audited against the official production build:

| Property | Value | Verification Status |
| :--- | :--- | :--- |
| **Release Tag** | `v5.1.0` | Verified on GitHub & local Git |
| **Commit Target** | `1794d38f4c2505d5cf07705b1f23cc10581f1341` | Verified |
| **APK Asset Name** | `VRKA-Android-v5.1.0.apk` | Verified matching canonical schema |
| **APK File Size** | `157,192,535` bytes | Verified exact byte match |
| **APK SHA-256** | `ca5c74f0c59a98fe225d133a79e98a9e2913e8f82bfdac0c841a27f2dcd1f28a` | Verified |
| **Checksum Asset Name** | `SHA256SUMS` | Verified |
| **Checksum File Size**| `93` bytes | Verified exact byte match |
| **Checksum SHA-256** | `6fe8a32a4e23ee07ff5832a82fa98317e17ddb02bcf9da248fe60803c4fa6aa0` | Verified |
| **Signing Certificate** | SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA` | 100% Match with v5.0.2 |
| **Signing Scheme** | APK Signature Scheme v2 (RSA 4096-bit) | Verified via `apksigner` |
| **Debuggable Flag** | `android:debuggable="false"` | Verified via `aapt2 dump xmltree` |
| **Min SDK / Target SDK**| `26` / `36` | Verified |
| **Version Code / Name** | `50100` / `"5.1.0"` | Verified |

The release assets conform strictly to the standard VRKA Android release format and specifications.

---

## 5. Phase 4: Migration & Manual Upgrade Pathway for v5.0.2 Users

Because the v5.0.2 in-app updater cannot self-execute the installation due to the `tempFile` validation bug, existing v5.0.2 users must perform a **one-time manual update** to v5.1.0.

### Why Data is Fully Preserved
- Both v5.0.2 and v5.1.0 are compiled with the identical `applicationId`: `com.mvrk.vrka`.
- Both are signed with the identical RSA 4096-bit production certificate:
  `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- Android's Package Manager enforces cryptographic signature equality for in-place upgrades. Because the signing identities match, Android treats this as a standard application update, preserving all local DataStore preferences, download queue, history, and stored media files.

### Upgrade Instructions for Users
1. Download `VRKA-Android-v5.1.0.apk` directly from the official release page:
   `https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0`
2. Open the downloaded file and tap **Update** when prompted by Android Package Installer.
3. Alternatively, install via ADB:
   ```bash
   adb install -r VRKA-Android-v5.1.0.apk
   ```

---

## 6. Phase 5: Reconciled Documentation Checklist

The following repository documentation files have been audited and updated:
1. `KNOWN_LIMITATIONS.md`: Documents the v5.0.2 client-side in-app updater validation failure and the manual upgrade requirement.
2. `README.md`: Notes the manual upgrade path for v5.0.2 users alongside standard installation instructions.
3. `RELEASE_EVIDENCE.md`: Retains complete verification records for unit tests (601 passing), release build artifacts, SHA-256 hashes, and certificate fingerprints.
4. `CHANGELOG.md`: Fully captures v5.1.0 feature additions, bug fixes, and upgrade notices.
