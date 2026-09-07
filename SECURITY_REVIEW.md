# Security Review

> [!NOTE]
> **Historical Baseline Document**: This review documents the original security audit performed for the VRKA Android 1.0.0 release. It is preserved verbatim for audit trail integrity and historical baseline reference. It does not certify later releases. For current security policies and release signing verification, refer to [SECURITY.md](SECURITY.md) and [README.md](README.md).

## Input and files

- PASS: URL validation permits HTTP and HTTPS only.
- PASS: Each job uses one UUID staging directory below the application staging root.
- PASS: Cleanup normalizes the root and child paths before recursive deletion.
- PASS: Single-file staging uses `media.%(ext)s`.
- PASS: Playlist staging bounds the title component.
- PASS: Final names remove separators, control characters, traversal markers, and invalid characters.
- PASS: Final names have a conservative UTF-8 byte limit.
- PASS: A long signed-CDN regression test passes.
- PASS: yt-dlp receives structured arguments. VRKA does not build a shell command string.
- PASS: The release UI does not expose free-text yt-dlp arguments.
- PASS: Video mode removes custom options that can force an audio-only result.

## Session data and errors

- PASS: User-facing errors redact URLs, cookies, authorization values, tokens, and signatures.
- PASS: Browser cookies and authorization headers do not appear in History.
- PASS: JobStore does not serialize resolved browser URLs or session headers.
- PASS: Signing properties and the private key remain outside the repository and source ZIP.
- PASS: The source scan found no embedded API key, private key, password, or service credential.

Browser session context remains in memory for the active same-job handoff.
Android process death can remove that context.
The user must retry the job if Android stops the process at that point.

## WebView

- PASS: The WebView has JavaScript and DOM storage because media sites require them.
- PASS: File access and content access are disabled.
- PASS: Cleartext network traffic is disabled in the application manifest.
- PASS: Mixed HTTP content is disabled.
- PASS: Safe Browsing keeps the platform default.
- PASS: The application does not add a privileged JavaScript interface.
- PASS: The document observer sends media URL text only.
- PASS: Resource blocking uses a small confident-junk list.
- PASS: Media, login, challenge, verification, and player paths remain protected.
- PASS: VRKA does not implement DRM key capture, license capture, or circumvention.

## Android release surface

- PASS: MainActivity is the only intentionally exported VRKA component. It is the launcher Activity.
- PASS: DownloadService is not exported.
- PASS: Application backup is disabled.
- PASS: The manifest requests only network, notification, foreground data-sync, and wake-lock permissions.
- PASS: The release build uses R8 and resource shrinking.
- PASS: APK Signature Scheme v2 verification passes with one RSA-4096 signer.

## Dependencies

- PASS: The parity pass adds no runtime code dependency.
- PASS: Space Mono includes the SIL Open Font License text.
- REVIEW: youtubedl-android 0.18.1 has old transitive Java libraries.
- PASS: The final gate queried OSV for the resolved release dependency graph.
- ACTION: Upgrade the youtubedl-android stack only after a compatible release is validated.

## Final security gate — 2026-07-29

This gate covers the signed VRKA Android 1.0.0 ARM64 release candidate.
The APK SHA-256 is `B3E19AAAD9E997679A8B9A91286E10DF3E1343E617AB9082AE1A36D8459BADAF`.
The application source, resources, manifest, dependencies, and runtime were not changed by this gate.

## Dependency vulnerability scan

- PASS: Gradle resolved `releaseRuntimeClasspath` successfully.
- PASS: The graph contains 147 unique Maven group, artifact, and version coordinates.
- PASS: The gate sent all 147 coordinates to the OSV `querybatch` API on 2026-07-29.
- REVIEW: OSV returned 22 version matches. All matches belong to four transitive components from `youtubedl-android 0.18.1`.
- PASS: OSV returned no advisory for the other 143 resolved coordinates.
- PASS: The gate inspected the youtubedl-android bytecode and the final R8 output for reachability.
- PASS: No advisory is a reachable P0 or P1 issue in the VRKA 1.0.0 execution paths.
- ACTION P2: Keep the current stack for 1.0.0. Validate a compatible youtubedl-android upgrade in a later release.

Evidence files are in `release/security`:

- `releaseRuntimeClasspath.txt`
- `resolved-maven-components.txt`
- `osv-querybatch-response.json`
- `osv-advisory-details.json`
- `osv-findings.tsv`
- `youtubedl-bytecode-review.txt`

### OSV findings

The severity is the OSV/GitHub advisory severity.
Each listed version is affected according to OSV.
“Not reachable” means that VRKA does not package or call the affected function with attacker-controlled input.

| Component and version | Advisory | Severity | Affected function | VRKA relevance | Action taken |
|---|---|---:|---|---|---|
| `jackson-core 2.11.1` | `GHSA-72hv-8253-57qq` | Moderate | Asynchronous parser number-length limit | Not reachable. VRKA uses the synchronous ObjectMapper path. R8 removed the asynchronous parser. | P2. Documented. No 1.0 change. |
| `jackson-core 2.11.1` | `GHSA-h46c-h94j-95f3` / `CVE-2025-52999` | High | Deep JSON nesting can exhaust the stack | No arbitrary JSON service exists. Inputs are trusted GitHub metadata or structured yt-dlp output. | P2. Documented. Upgrade with a compatible stack. |
| `jackson-core 2.11.1` | `GHSA-r7wm-3cxj-wff9` | High | Asynchronous chunked-number parsing | Not reachable. R8 removed the asynchronous parser. | P2. Documented. No 1.0 change. |
| `jackson-core 2.11.1` | `GHSA-wf8f-6423-gfxg` / `CVE-2025-49128` | Moderate | Source snippet disclosure from an offset byte array | Not reachable. VRKA does not use pooled byte arrays or the offset parser API. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-3wrr-7qpf-2prh` / `CVE-2026-50193` | Moderate | Deep JsonNode serialization with `toString()` | Not reachable. The updater does not serialize its JsonNode with `toString()`. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-3x8x-79m2-3w2w` / `CVE-2021-46877` | High | JDK serialization of JsonNode | Not reachable. VRKA does not use JDK serialization for JsonNode. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-57j2-w4cx-62h2` / `CVE-2020-36518` | High | Deep JSON nesting denial of service | No arbitrary JSON service exists. Inputs are trusted GitHub metadata or structured yt-dlp output. | P2. Documented. Upgrade with a compatible stack. |
| `jackson-databind 2.11.1` | `GHSA-5jmj-h7xm-6q6v` / `CVE-2026-54515` | Moderate | Case-insensitive binding can bypass `JsonIgnoreProperties` | Not reachable. VRKA does not configure this binding combination. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-hgj6-7826-r7m5` / `CVE-2026-54514` | Moderate | InetSocketAddress deserialization can cause DNS access | Not reachable. No InetSocketAddress target is deserialized. R8 removed the deserializer. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-j3rv-43j4-c7qm` / `CVE-2026-54512` | High | PolymorphicTypeValidator generic-type bypass | Not reachable. Polymorphic typing is not enabled. R8 removed the validator. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-jjjh-jjxp-wpff` / `CVE-2022-42003` | High | Deep wrapper arrays with `UNWRAP_SINGLE_VALUE_ARRAYS` | Not reachable. VRKA does not enable this feature. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-rgv9-q543-rqg4` / `CVE-2022-42004` | High | Deep arrays with `UNWRAP_SINGLE_VALUE_ARRAYS` | Not reachable. VRKA does not enable this feature. | P2. Documented. No 1.0 change. |
| `jackson-databind 2.11.1` | `GHSA-rmj7-2vxq-3g9f` / `CVE-2026-54513` | High | BasicPolymorphicTypeValidator array bypass | Not reachable. Polymorphic typing is not enabled. R8 removed the validator. | P2. Documented. No 1.0 change. |
| `commons-io 2.5` | `GHSA-78wr-2p64-hpwj` / `CVE-2024-47554` | High | XmlStreamReader denial of service | Not reachable. XmlStreamReader is not used and is not in the R8 output. | P2. Documented. No 1.0 change. |
| `commons-io 2.5` | `GHSA-gwrp-pvrq-jmwv` / `CVE-2021-29425` | Moderate | FileNameUtils path normalization | Not reachable. VRKA does not use FileNameUtils. R8 removed it. | P2. Documented. No 1.0 change. |
| `commons-compress 1.12` | `GHSA-4g9r-vxhx-9pgx` / `CVE-2024-25710` | Moderate | Corrupt DUMP archive loop | Not reachable. The DUMP reader is not in the R8 output. | P2. Documented. No 1.0 change. |
| `commons-compress 1.12` | `GHSA-7hfm-57qf-j43q` / `CVE-2021-35515` | High | Crafted 7Z archive loop | Not reachable. The 7Z reader is not in the R8 output. | P2. Documented. No 1.0 change. |
| `commons-compress 1.12` | `GHSA-crv7-7245-f45f` / `CVE-2021-35516` | High | Crafted 7Z archive allocation | Not reachable. The 7Z reader is not in the R8 output. | P2. Documented. No 1.0 change. |
| `commons-compress 1.12` | `GHSA-h436-432x-8fvx` / `CVE-2018-1324` | Moderate | Crafted ZIP extra-field loop | The ZIP classes are present. They read only runtime archives bundled inside the signed APK. They do not read a user or network archive. | P2. Documented. Upgrade with a compatible stack. |
| `commons-compress 1.12` | `GHSA-hrmr-f5m6-m9pq` / `CVE-2018-11771` | Moderate | Crafted ZIP end-of-file loop | The ZIP classes are present. They read only runtime archives bundled inside the signed APK. | P2. Documented. Upgrade with a compatible stack. |
| `commons-compress 1.12` | `GHSA-mc84-pj99-q6hh` / `CVE-2021-36090` | High | Crafted ZIP memory allocation | The ZIP classes are present. They read only runtime archives bundled inside the signed APK. | P2. Documented. Upgrade with a compatible stack. |
| `commons-compress 1.12` | `GHSA-xqfj-vm6h-2x34` / `CVE-2021-35517` | High | Crafted TAR memory allocation | Not reachable. The TAR reader is not in the R8 output. | P2. Documented. No 1.0 change. |

## yt-dlp updater integrity

- PASS: Stable updates use `https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest`.
- PASS: Nightly updates use `https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest`.
- PASS: The updater selects the exact release asset name `yt-dlp`.
- PASS: Android validates TLS certificates. The library does not install a custom trust manager.
- PASS: Android follows at most five redirects. Android does not follow an HTTPS-to-HTTP redirect.
- REVIEW P2: Android can follow an HTTPS redirect to another origin. The library does not apply a host allowlist. The URL comes from the authenticated GitHub API response. It is not user-controlled.
- PASS: The download uses a controlled temporary file in the application cache directory.
- PASS: The remote filename does not become a local path. The installed name is always `yt-dlp` below the library directory in `noBackupFilesDir`.
- PASS: A download error occurs before replacement. The known-good runtime remains in place.
- PASS: A caught copy failure deletes the incomplete target and restores the bundled runtime.
- PASS: The updater deletes the temporary file after a completed download and install attempt.
- REVIEW P2: A network failure inside the download helper can leave an orphan temporary file in the app cache until cache eviction.
- REVIEW P2: A complete malformed update can replace the runtime because the library performs no checksum or executable validation. The failure appears when VRKA later runs the file.
- REVIEW P2: The library does not verify `SHA2-256SUMS` or `SHA2-256SUMS.sig`. Both stable and nightly upstream releases publish these files. Integrity therefore depends on GitHub TLS and repository control.
- REVIEW P2: Replacement is recoverable after a caught error. It is not atomic. Process termination during the delete-and-copy window can leave an incomplete runtime file.
- ACTION P2: A later compatible updater should validate the upstream SHA-256 file, restrict redirect hosts, clean stale temporary files, and replace the runtime atomically. Do not add a custom signature scheme.
- PASS: Normal updater errors pass through the bounded URL and token redaction used by the user interface.

The 1.0.0 updater does not accept a URL from the user.
The observed gaps are supply-chain and availability hardening items.
They do not provide a known path for an untrusted site to replace the runtime.

## Final secret scan

- PASS: A high-confidence pattern scan covered application source, resources, Gradle files, scripts, and release Markdown files.
- PASS: The same pattern scan covered the extracted final source ZIP.
- PASS: No API key, password, authorization value, private key, private certificate, cookie, session token, or service credential matched.
- PASS: No `.jks`, `.keystore`, `.p12`, `.pfx`, `.pem`, private-key file, `.env`, credential file, or signing properties file is in the source tree or source ZIP.
- PASS: The signing properties file remains outside the repository.
- PASS: The source ZIP excludes `local.properties`, build output, Gradle caches, and the release directory.
- REVIEW: `README.md` contains `replace-me` signing examples. These strings are placeholders, not credentials.
- REVIEW: The checkpoint contains local Windows recovery paths. The paths contain no key or password value.

## Final Android release surface

- PASS: `apkanalyzer` reports `debuggable=false` for the signed APK.
- PASS: The source manifest intentionally exports MainActivity only.
- PASS: DownloadService is not exported.
- PASS: The merged manifest keeps AndroidX InitializationProvider non-exported.
- REVIEW: The merged manifest exports AndroidX ProfileInstallReceiver. Android protects it with `android.permission.DUMP`. An ordinary third-party application cannot use this receiver.
- PASS: Backup is disabled.
- PASS: Cleartext traffic is disabled.
- PASS: The requested application permissions are INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, and WAKE_LOCK.
- PASS: AndroidX adds one application-specific signature permission for non-exported dynamic receivers.
- PASS: The release contains no debug Activity, debug Service, test component, synthetic QA URL, or synthetic QA record.
- PASS: The final APK contains ARM64 native code only.
- PASS: MainActivity remains the only unprotected exported application entry point.

## Final WebView and session review

- PASS: No `addJavascriptInterface` bridge exists.
- PASS: The WebMessage listener accepts media URL text only. It exposes no native privileged method.
- PASS: File access and content access remain disabled.
- PASS: Mixed HTTP content remains disabled.
- PASS: JobStore does not serialize `resolvedMediaUrl` or `resolvedHeaders`.
- PASS: Retry removes resolved media URLs and headers before it creates a new job.
- PASS: `safeError` removes HTTP and HTTPS URLs and redacts cookie, authorization, token, and signature values. It limits text to 320 characters.
- PASS: Browser logs contain a WebView error code and host only. Downloader logs use the redacted error text.
- PASS: Notifications show the media title, media configuration, status, progress, speed, and ETA. They do not show a URL or request header.
- PASS: Browser handoff headers remain in process memory and do not enter History storage.
- REVIEW P2: Android System WebView owns the cookie jar. VRKA does not explicitly clear that jar when a fallback session ends. A cookie can remain in app-private WebView storage. VRKA does not copy it into JobStore, History, notifications, or logs.
- ACTION P2: Define and test an ephemeral cookie policy in a later browser-maintenance release. Do not change the accepted 1.0 fallback without regression testing.

## Final input, path, and argument review

- PASS: The focused `DownloadRequestFactoryTest` suite passed on 2026-07-29.
- PASS: Input URLs must have an HTTP or HTTPS scheme and a host.
- PASS: New job identifiers are UUID values.
- PASS: Cleanup normalizes the staging root and child path. Cleanup refuses the root itself.
- PASS: Single-output staging uses `media.%(ext)s`.
- PASS: Playlist staging limits the title component to 96 bytes through the yt-dlp template.
- PASS: Final filenames remove separators, control characters, traversal markers, and invalid characters.
- PASS: Final filename stems have a 160-byte UTF-8 limit.
- PASS: The 2,000-character signed-CDN regression stays bounded.
- PASS: The application sends arguments as a list to YoutubeDLRequest. It does not construct a shell command string.
- PASS: The release UI does not expose free-text custom arguments.
- PASS: Video mode rejects custom format, extraction, audio format, and recode options that can force audio-only output.

## Final licence review

- PASS: `THIRD_PARTY_NOTICES.md` identifies the significant runtime libraries and licences.
- PASS: The Space Mono SIL Open Font License text is packaged in the APK.
- PASS: The final polish icons, launcher layers, splash resources, and notification artwork were created in this project. They were not copied from an external asset pack.
- PASS: The final polish added no third-party runtime dependency.
- PASS: No notice update is required for the in-project vector resources.

## Final triage

- P0: None.
- P1: None.
- P2: Upgrade the old youtubedl-android transitive stack after compatibility testing.
- P2: Add upstream checksum validation, redirect-host controls, stale-temporary cleanup, and atomic updater replacement in a later updater revision.
- P2: Define an explicit app-private WebView cookie retention policy in a later browser-maintenance release.

No unresolved P0 or P1 finding blocks VRKA Android 1.0.0.
This statement applies only to the checks and evidence in this document.
It is not an absolute security guarantee.

---

## VRKA Android 4.0.2 Security Hardening & Verification — 2026-09-07

This audit covers the security hardening pass implemented in VRKA Android 4.0.2 addressing component updater authenticity, dependency security, browser session clearing, and diagnostic privacy.

### 1. Authenticated Component Updater (yt-dlp)

- **Standard OpenPGP Implementation**: Integrated Bouncy Castle (`bcpg-jdk18on:1.85` and `bcprov-jdk18on:1.85.2`). Signature verification delegates framing, algorithm negotiation, and verification to Bouncy Castle standard OpenPGP APIs (`PGPObjectFactory`, `JcaPGPContentVerifierBuilderProvider`), eliminating custom crypto parsing.
- **Pinned Upstream Trust Anchor**: The official yt-dlp release key (`contact@grub4k.xyz`) is embedded in `app/src/main/res/raw/ytdlp_pubkey.asc`. The updater independently derives the key fingerprint on initialization and asserts matching:
  - Primary Key ID: `0x57CF65933B5A7581`
  - Primary Key Fingerprint: `AC0CBBE6848D6A873464AF4E57CF65933B5A7581`
  - Dynamic key fetching over network is strictly disallowed.
- **Strict HTTPS & Redirect Policy**: All update communication requires HTTPS. Insecure HTTP sources are rejected. Redirects are validated manually (depth limit 5) to recognized GitHub release asset hosts (`objects.githubusercontent.com`, `release-assets.githubusercontent.com`).
- **Cryptographic Verification Chain**:
  1. Fetch `SHA2-256SUMS` and detached signature `SHA2-256SUMS.sig`.
  2. Verify OpenPGP detached signature over manifest using pinned trust anchor.
  3. Extract SHA-256 hash for target asset `yt-dlp` from the authenticated manifest.
  4. Stream-download `yt-dlp` to `.download.tmp` while computing SHA-256 digest.
  5. Reject and delete temp file on hash mismatch.
- **Transactional Replacement & Rollback**:
  1. Stage verified file to `yt-dlp.staged.tmp`.
  2. If active binary exists, create backup `yt-dlp.backup.tmp`.
  3. Replace active binary via atomic move or safe replace.
  4. Perform post-update execution validation (`versionName`).
  5. If execution check fails or returns empty/null, restore `yt-dlp.backup.tmp` over `yt-dlp` and throw error.
  6. Delete backup only after verified execution check.

### 2. Browser Session & Storage Isolation

- **GeckoView 153.0 Storage Clearing**: Integrated `GeckoRuntime.storageController.clearData(flags)` using exact flags:
  - `ClearFlags.COOKIES`
  - `ClearFlags.DOM_STORAGES`
  - `ClearFlags.AUTH_SESSIONS`
  - `ClearFlags.SITE_DATA`
  - `ClearFlags.ALL_CACHES`
- **In-Memory Cache Reset**: In-memory candidate observation cache cleared via `GeckoMediaBridge.clear()`.
- **Data Protection Guarantee**: Verified that browser session clearing strictly touches GeckoView runtime storage and never affects download history (`JobStore`), active queue state, user preferences (`DataStore`), or diagnostic logs (`DiagnosticStore`).
- **UI Confirmation**: Accessible in Settings under "Browser Subsystems" with explicit confirmation dialog informing the user of the clearing scope.

### 3. Bundled Extensions Status

- **Immutable APK Assets**: Verified that `uBlock Origin` (v1.74.0) and `Puemos HLS Detection` (v1.0.0) are built into APK assets (`app/src/main/assets/extensions/`) and loaded via `resource://android/assets/extensions/`.
- **Truthful Status Reporting**: Replaced simulated version bumping with truthful reporting: displayed as `Bundled • App Release`, preventing misleading out-of-band update prompts.

### 4. Empirical Dependency Vulnerability Audit

- **Audit Target**: Complete resolution of `releaseRuntimeClasspath`.
- **Bouncy Castle Alignment**: Resolved `org.bouncycastle:bcprov-jdk18on:1.85.2` (patched against known CVEs) alongside `bcpg-jdk18on:1.85` and `bcutil-jdk18on:1.85`.
- **youtubedl-android Transitive Dependencies**:
  - `commons-io:2.5` & `commons-compress:1.12`: Used strictly for internal application asset extraction (python/ffmpeg bundles) packaged within the APK. Not exposed to untrusted external archive input.
  - `jackson-databind:2.11.1`: Used solely to deserialize structured yt-dlp `--dump-json` output from local subprocesses; polymorphic type handling (`enableDefaultTyping`) is not enabled.
  - R8 full-mode shrinking and ProGuard optimization tree-shake unused transitive classes from the final release APK.

### 5. Deterministic Test Verification

- **Automated Test Coverage**: 145 unit tests pass cleanly offline via `gradlew testDebugUnitTest --offline`.
- **Test Matrix Expansion**:
  - `SecureComponentUpdaterTest`: 26 test cases verifying authentic signatures, tampered manifests, corrupted signatures, unpinned keys, wrong binary hashes, network errors, insecure HTTP, redirect policies, transactional staging, and rollback.
  - `JobStorePersistenceTest`: 6 test cases verifying queue roundtrip (10 jobs), recovery of in-flight jobs as failed with retry prompts, terminal state preservation, corrupted JSON tolerance, and max 250 job capping.
