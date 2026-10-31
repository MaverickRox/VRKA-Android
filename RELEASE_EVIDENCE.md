# VRKA Android 5.1.0 — Release Evidence

**Verification date:** 2026-10-31

**Source version:** `5.1.0` (`versionCode` 50100)
**Validation state:** automated tests, release lint, production release build, APK metadata, signing certificate, checksum, and OpenPGP release manifest verification. Physical OnePlus 11 was unavailable for this release; emulator instrumentation and automated test suites passed.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 635 tests in 60 suites; 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 275 warnings, 0 hints; no errors or fatal findings |
| `:app:assembleRelease` | PASS: production-signed release APK built |
| Release signing validation | PASS: verified with production PKCS#12 keystore |
| OpenPGP manifest signature | PASS: verified with pinned 4096-bit RSA release key (`0xD519380BEBA1B9F7`) |

Result: `BUILD SUCCESSFUL`.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, OpenJDK 25.0.3, Java source/target 17. The APK reports compile SDK 37 (minor API level 2), target API 36, minimum API 26, and `arm64-v8a` only.

## APK identity and integrity

- Canonical release asset: `VRKA-Android-v5.1.0.apk`
- Local verified build: `app/build/outputs/apk/release/app-release.apk`
- Size: 157,202,797 bytes
- SHA-256: `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
- Application ID: `com.mvrk.vrka`
- Version: `5.1.0` (`versionCode` 50100)
- Minimum / target / compile SDK: 26 / 36 / 37 (minor API level 2)
- Packaged native ABI: `arm64-v8a`
- Debuggable: false (non-debuggable)

`aapt2 dump badging` confirmed package `com.mvrk.vrka`, `versionCode= 50100`, `versionName=5.1.0`, and application label `VRKA`. `apksigner verify --verbose --print-certs` verified one RSA 4096-bit signer using APK Signature Scheme v2. The production certificate SHA-256 is `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; SHA-1 is `7758980F74A503684BF1A187994E447823D19D7C`. The fingerprints match the established production signing certificate. No signing secrets are recorded.

## Cryptographically Authenticated Release Manifest

- Release manifest: `VRKA-Android-v5.1.0.manifest.json` (862 bytes)
  - SHA-256: `F35CDB2127402ED7ABB2B634E505DD31D448D106C7F3DD01F00FBD76315476FD`
- Detached OpenPGP signature: `VRKA-Android-v5.1.0.manifest.json.asc` (870 bytes)
  - SHA-256: `5B537A4D327594545A526DB9B376743B378F724856D3BA5C636079D86D1442B4`
- Exported public key: `VRKA-Release-Public-Key.asc` (1,754 bytes)
  - SHA-256: `7F2A6EF42A4F66289E31C4FEFFF44B7BA294F9D0C696ACE1F20602401641F38A`
  - Key ID: `0xD519380BEBA1B9F7`
  - Fingerprint: `71165A658B3A8612AC2568C6D519380BEBA1B9F7`

---

# VRKA Android 5.0.2 — Release Evidence

**Verification date:** 2026-10-05

**Source version:** `5.0.2` (`versionCode` 50002)
**Validation state:** automated tests, release lint, production build, APK metadata, signing certificate, and checksum verified. 5.0.2 has not been physically validated.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 373 tests in 34 suites; 0 failures, 0 errors, 0 skipped |
| `:app:lintRelease` | PASS: 88 warnings, 1 hint; no errors or fatal findings |
| `:app:assembleRelease` | PASS: production-signed release APK built |
| Release signing validation | PASS |

Result: `BUILD SUCCESSFUL`.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, Android Studio JBR OpenJDK 25.0.3, Java source/target 17. The APK reports compile SDK 37, target API 36, minimum API 26, and `arm64-v8a` only.

## APK identity and integrity

- Canonical release asset: `VRKA-Android-v5.0.2.apk`
- Local verified build: `app/build/outputs/apk/release/app-release.apk`
