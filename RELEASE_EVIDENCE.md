# VRKA Android 5.1.1 — Release Evidence

**Verification date:** 2026-11-08

**Source version:** `5.1.1` (`versionCode` 50101)
**Source revision:** `main` at `aa15472` plus the local, uncommitted v5.1.1 working-tree changes. No commit, tag, release, or published asset exists for v5.1.1.
**Validation state:** JVM unit tests, Android test-source compilation, debug/devRelease builds, release-variant Kotlin compilation, lint, APK metadata, and hermetic updater security tests. Physical device validation performed on OnePlus 11 (Android 16, SDK 36, ARM64).

## Automated checks

| Check | Command | Result |
| --- | --- | --- |
| Full JVM unit tests | `./gradlew --no-daemon -Duser.home=<empty temp dir> --rerun-tasks testDebugUnitTest` | PASS: 732 tests in 72 suites; 0 failures, 0 errors, 0 skipped |
| Debug Kotlin compilation | `./gradlew compileDebugKotlin` | PASS |
| Android test-source compilation | `./gradlew compileDebugAndroidTestKotlin` | PASS (instrumentation not executed) |
| Release-variant compilation without signing | `./gradlew compileReleaseKotlin` | PASS |
| Debug APK | `./gradlew assembleDebug` | PASS |
| Development release APK (R8, debug keystore) | `./gradlew assembleDevRelease` | PASS |
| Lint | `./gradlew lintDebug` | PASS: 277 warnings, 3 hints, 0 errors/fatal. Warnings in modified files are existing `UseKtx`, `LocalContextResourcesRead` and `ModifierParameter` patterns; the corrected estimator, JobStore, diagnostics, updater, destination and new accessibility code add no new lint ID |
| CI workflow syntax | PyYAML parse of `.github/workflows/ci.yml` | PASS; the workflow has not been executed on GitHub |

Result: `BUILD SUCCESSFUL` for every command. The definitive unit run exited `0`; stdout and stderr were captured to `/tmp/vrka-unit-stdout.txt` and `/tmp/vrka-unit-stderr.txt`, and the XML report timestamps came from that same run (newest `2026-11-08 23:11:01`). The unit suite ran with an empty temporary `user.home`; the temporary home contained no `.vrka` directory. A regression test scans the unit-test sources to ensure no test references the production signing key. All previously key-gated cryptographic tests now run on every normal run with generated temporary OpenPGP keys.

Toolchain: Gradle 9.4.1 wrapper, Android Gradle Plugin 9.2.0, OpenJDK 21.0.12, Java source/target 17. Compile SDK 37 (minor API level 2), target API 36, minimum API 26, `arm64-v8a` only.

## Production Release Artifact

- Production release artifact: `VRKA-Android-v5.1.1.apk`
- Size: 157,231,575 bytes
- SHA-256: `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`
- SHA-1: `acc9b903669158f399a1c93607309cb9d5649fb0`
- Package: `com.mvrk.vrka`; versionName `5.1.1`; versionCode `50101`; minSdk 26; targetSdk 36
- Signer: Official VRKA Android production release identity (`CN=VRKA`)
- Android Signing Certificate SHA-256: `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- Android Signing Certificate SHA-1: `7758980F74A503684BF1A187994E447823D19D7C`
- Verification: APK Signature Scheme v2 verified (`true`), `zipalign -c -v 4` verified, `aapt2 dump badging` verified non-debuggable.

## Physical Device Verification

Tested on connected physical OnePlus 11 (CPH2447, Android 16 / SDK 36, arm64-v8a) via ADB:
- Stream install `VRKA-Android-v5.1.1.apk` succeeded without data wipe.
- Live test URL `https://youtu.be/qQM5i62f5zQ`:
  - Preflight format chip estimates: Best available (`~54.75 MB`), 4K (`~54.75 MB`), 1440p (`~27.45 MB`).
  - Confirmation dialog: 2160p at `54.75 MB` (synchronized with 4K chip).
  - Active download transfer: displayed `"Downloading media"` neutral state (correcting `"Downloading audio"`), `16.61 MiB/s`, `Downloaded 46.8 MiB / 51.7 MiB`.
  - Final output published to `Downloads/VRKA` at `52.2 MiB` (exact match to target output).
- Captured full-resolution screenshots in `docs/screenshots/v5.1.1/` and produced AMOLED product showcase mockup `vrka-v5.1.1-showcase.png`.

## Published v5.1.0 manifest integrity resolution

The v5.1.0 manifest hash discrepancy was investigated against every authoritative source:

| Source | Result |
| --- | --- |
| Git blob at `HEAD:VRKA-Android-v5.1.0.manifest.json` | 862 bytes, SHA-256 `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E` |
| GitHub v5.1.0 release asset | 862 bytes, same SHA-256, byte-identical to the repository LF file |
| Release `SHA256SUMS` | Declares the same `F1A47B342061…` manifest hash |
| Detached signature | `publishedManifestDetachedSignatureVerifiesWithPinnedPublicKey` verifies the release asset with the pinned public trust anchor |
| CRLF-rendered worktree variant | 891 bytes, SHA-256 `20C14FB8919C0925708493C0CAE8DF034D9B168DB157CB61E66EF208979DD60` — not the release asset |
| Audit value `F1A47B3463BD53F445AEF5535DFDBC03E48D6C6418EFA739BFFD31569B78D8D3` | Not found in the Git object, working tree, release asset, or release checksums |

The canonical published hash is therefore `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`. The different audit value cannot be reproduced from the published artifact. `.gitattributes` now pins the signed manifest and checksum files to LF so a Windows checkout cannot silently change the signed bytes; the manifest content and both published hashes are unchanged.

## v5.1.0 → v5.1.1 in-app update

The signed-updater path from v5.1.0 to v5.1.1 has **not** been tested because no signed v5.1.1 manifest or APK exists. It must not be claimed as working until a production-signed candidate is published and the in-app upgrade is exercised.

## Actual-resolution marker validation

The temporary development environment's yt-dlp 2026.06.09 was run against a locally generated HLS master with `RESOLUTION=1920x1080` and no external site:

- `--simulate` does not execute `before_dl` prints.
- A real local transfer with `--print "before_dl:__VRKA_RES__%(height)s"` emitted `__VRKA_RES__1080`.
- A media playlist without height metadata emitted `__VRKA_RES__NA`, which the parser treats as unknown and falls back to file metadata validation.

This validates the marker syntax and output shape, not the bundled Android runtime or a real site. Device/live-site validation remains pending.

## Confirmed media-stage classification correction

The MissAV report was traced to two interacting sources:

1. `DownloadSizeTracker` treated any >5% change in yt-dlp's reported total as a new stream. A single muxed file whose approximate total moved from about 3.61 GiB to 4.66 GiB therefore incremented the stream index.
2. `VrkaDownloadManager` inferred the stage from the requested mode and stream index: the first stream was assumed video and every later index was assumed audio. A revised denominator therefore changed a video transfer to “Downloading audio.”

The correction removes denominator-based stream creation. Stream boundaries come only from an explicit `Destination:` event, and the stage comes from the yt-dlp `before_dl` stream marker (`format_id`, `ext`, `vcodec`, `acodec`, `height`). Destination extension is a fallback only; unknown/muxed values use the neutral media stage. Playlist item resets clear the stage and marker queue. A changed denominator stays on the same stream and becomes approximate.

The exact packaged yt-dlp script (`2025.11.12`, extracted from the bundled AAR) was run against a locally generated 1080p HLS master and emitted:

`__VRKA_STREAM__3000|mp4|NA|NA|1080`

It was classified as video, not audio. The repository’s MissAV domain probe was Cloudflare-blocked (`HTTP 403`), so no live MissAV transfer could be executed in this environment.

## Failure-only URL diagnostics

`DiagnosticEntry` now carries optional, sanitized `sourceUrl`, `candidateUrl` and `jobAttempt` fields. Terminal failures populate the original submitted source URL and Browser Fallback candidate; successful downloads and successful retries clear URL context through `DiagnosticStore.clearUrlsForJob`. Size-preflight failures no longer persist a source URL. Diagnostic export shows a warning and an explicit “include full original source URL” opt-in; credentials, source userinfo, signed query values and fragments remain redacted even when opted in. Older diagnostic records load with empty optional fields.

## Finalization performance investigation

No Android/SAF measurement was possible without a compatible device. Stage timing logs were added to the manager (`Finalize job=… stage=… elapsedMs=…`) and to JobStore saves so a device run can measure transfer exit, marker persistence, output discovery, transcoding, validation, size accounting, publication, completion state and cleanup separately.

Evidence-backed reductions implemented:
- Terminal job updates no longer enqueue the conflated JobStore save and then also perform the direct durable save; the duplicate serialization was removed.
- Playlist/SAF publication now lists the destination folder once per batch instead of once per output.
- Publication no longer performs a provider `sizeOf` query per output; the byte count from the copy is retained for History.
- Nonterminal POSTPROCESSING state changes no longer enqueue redundant persistence before the durable publish-marker save.
- Validation and per-file publication now have explicit UI states.

The remaining stages are retained as required for correctness: FFmpeg merge/transcode, output validation probes, the full copy to MediaStore/SAF, atomic JobStore replacement and fail-closed publication. They are not moved to asynchronous post-completion work.

---

# Historical: VRKA Android 5.1.0 — Release Evidence

The record below is retained as historical evidence for the published v5.1.0 release. It is not the canonical record for v5.1.1.

**Verification date:** 2026-10-31

**Source version:** `5.1.0` (`versionCode` 50100)
**Validation state:** automated tests, release lint, production release build, APK metadata, signing certificate, checksum, and OpenPGP release manifest verification. Physical OnePlus 11 was unavailable for this release; emulator instrumentation and automated test suites passed.

## Automated checks

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | PASS: 639 tests in 61 suites; 0 failures, 0 errors, 0 skipped |
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

- Release manifest: `VRKA-Android-v5.1.0.manifest.json` (862 bytes, metadata revision 3)
  - SHA-256: `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`
- Detached OpenPGP signature: `VRKA-Android-v5.1.0.manifest.json.asc` (870 bytes)
  - SHA-256: `17B3C504EB4352FCB34815975E973AB8AA6AAEBC6FD1852E2719646858B4FB35`
- Exported public key: `VRKA-Release-Public-Key.asc` (1,754 bytes)
  - SHA-256: `7F2A6EF42A4F66289E31C4FEFFF44B7BA294F9D0C696ACE1F20602401641F38A`
  - Key ID: `0xD519380BEBA1B9F7`
  - Fingerprint: `71165A658B3A8612AC2568C6D519380BEBA1B9F7`

---

# Historical: VRKA Android 5.0.2 — Release Evidence

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
