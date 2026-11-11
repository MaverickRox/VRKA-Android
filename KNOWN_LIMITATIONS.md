# Known Limitations — VRKA Android 5.1.3

> [!NOTE]
> **Historical Baseline & Scope**: This document tracks known limitations across VRKA Android releases and defines the current operational boundaries for official production release **v5.1.3**. For current production architecture and security policies, see [CURRENT_PRODUCTION_ARCHITECTURE.md](CURRENT_PRODUCTION_ARCHITECTURE.md), [README.md](README.md), and [SECURITY.md](SECURITY.md).

> [!NOTE]
> **v5.1.3 storage limitations.** The Room/SQLite authority, startup gate, and atomic publication
> state machine described in [docs/STORAGE_ARCHITECTURE.md](docs/STORAGE_ARCHITECTURE.md) ship in
> v5.1.3. Migration has been verified against an 80-case corpus generated from tagged encoders,
> not every real-world legacy installation. The SAF protocol was exercised on an API 36 emulator
> against a controlled DocumentsContract provider; third-party OEM providers may have
> different rename, enumeration, readback, or permission behavior. When a provider cannot
> enumerate children or return reliable document identities, VRKA rejects the destination before
> creation; when content cannot be read back or a marker is rewritten/duplicated, the transaction
> stays unresolved and is not reported as published. Room schema upgrades for future versions
> must remain transactional and preserve legacy migration records.
>
> Corrupt Room rows are quarantined and shown as damaged History entries; storage reports
> `READY` with `partialReadiness` rather than silently dropping them. Cleanup tasks that exhaust
> their retry budget become dead letters and require an explicit Diagnostics retry. Provider
> cleanup keeps the History record and publication row in place until ownership is resolved.
>
> **Supply-chain and release limitations.** Dependency checksums verify the committed metadata,
> not the intent of every publisher. Native dependencies are prebuilt AARs and are not reproducibly
> built in this repository. uBlock Origin is a bundled upstream asset not covered by Dependabot alerts
> (automated Dependabot PR generation is disabled under the single-branch policy while vulnerability alerts remain enabled).
> v5.1.3 has no CI-generated build attestation; the published artifact and tag are unchanged by
> this maintenance work. Private signing-key custody, offline status, backup, and hardware
> protection remain unverified until the maintainer records evidence. A future OpenPGP or APK
> signing-key rotation requires a transition release or signing lineage; see
> [docs/KEY_MANAGEMENT.md](docs/KEY_MANAGEMENT.md).

> [!IMPORTANT]
> **v5.0.2 USERS STILL NEED THE ONE-TIME MANUAL UPGRADE.** The v5.0.2 client has an `APK verification failed: APK filename does not match the expected release` check that cannot update itself. Download and install `VRKA-Android-v5.1.2.apk` from the official GitHub release page (https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) over v5.0.2 without uninstalling. Then update to v5.1.3. Application data and settings are preserved.

- The app and packaged native media libraries target `arm64-v8a`; other ABIs are not shipped.
- VRKA does not bypass DRM, login, payment, CAPTCHA, consent, or other access controls. Site changes may require a yt-dlp or app update.
- **Resolution-Chip Estimates Are Approximate**: Resolution chips display locally calculated approximate estimates (indicated by the `~` prefix) derived from the cached metadata format table. This avoids triggering 5–7 parallel, high-latency yt-dlp network preflights during initial URL load.
- **Authoritative Selected-Quality Confirmation**: The confirmation modal estimate for the user's selected resolution/quality is authoritative: it uses the exact effective format selector (`-f`) and audio sorting rules that the actual download transfer executes.
- **Browser Fallback Live-Site Validation Boundary**: While passive stream discovery, uBlock Origin content filtering, and media candidate interception (11 HLS streams) were verified on hardware using the Shaka Player demo, full end-to-end media transfer and final file size accuracy on protected live anti-bot sites have not been comprehensively validated on physical devices.
- **Android Storage-Provider Platform Limitations & Crash Recovery**: API 29+ exports directly to `MediaStore.Downloads` (`Downloads/VRKA`). API 26–28 requires an explicit user-granted SAF folder tree; if the permission is revoked, downloads pause and prompt for a destination. v5.1.3 uses durable Room publication and cleanup records. OEM providers may differ in rename, enumeration, readback, and permission behavior:
  - **Provider Object Creation Before URI Persistence**: MediaStore and SAF objects use a deterministic recoverable pending filename (`.vrka_pending_${transactionId}_${filename}`) established before creation. If a crash occurs before the resulting URI can be recorded in the journal, startup reconciliation discovers the orphaned object by its transaction identity, adopting and finalizing it if the copy was completed, or safely deleting it if incomplete, without affecting unrelated user files.
  - **Atomic Playlist Batch Intent Recording**: All output intents in a multi-file playlist batch are recorded atomically upfront with individual transaction IDs, output indices, and total batch count before provider creation begins. If a playlist publication is interrupted (e.g. 1 of 3 files completed), partial provider files are removed, completed file URIs are retained in History, and the job fails closed (`FAILED`, `INTERRUPTED_DOWNLOAD_ERROR`) rather than incorrectly marking the entire playlist complete.
  - **Durable job state and ordering**: Room does not evict nonterminal jobs or jobs pinned by unresolved publication transactions. It commits terminal completion and exact ordered outputs atomically. If ownership is unclear, recovery preserves the transaction and does not mark the job DONE.
  - **Provider Verification and Recovery**: API 29+ finalization requires the provider row, expected display name, and `IS_PENDING = 0`; a missing pending column is unverifiable, never treated as finalized. Startup recovery re-inspects finalized and recorded outputs before DONE. API 26–28 uses name-only legacy semantics without unsupported pending operations.
  - **Cleanup and URI Discovery**: Failed deletions remain durable cleanup tasks. Provider query failures and revoked SAF permissions never authorize deletion. Destructive cleanup requires verified ownership of the matching provider object.
  - **Strict legacy Journal Validation**: The importer validates known journal fields, transaction IDs, indexes, batch totals, pending-name relationships, provider URI structure, and phase/status combinations. Ambiguous or invalid records remain preserved as evidence and fail closed.
  - **Staging Cleanup Durability**: Failed staging deletion becomes a durable Room retry obligation and does not run while an unresolved transaction or live job depends on the directory.
  - **SAF Rename Support**: Not all SAF DocumentProviders support `DocumentsContract.renameDocument`. Where unsupported, VRKA retains the created document at its pending URI, verifies the provider row, and fails closed rather than marking an unverified publication complete.
  - **Legacy In-Flight Job Boundary**: Persisted jobs started under legacy versions with `publishStarted = true` but lacking publication journal records fail closed safely as `FAILED` (`"Interrupted (legacy transfer without publication journal)"`) when output URIs are empty, preventing output hallucination or unauthorized file deletions.
  - **Verification Boundary**: JVM recovery tests use controlled provider and filesystem dependencies. API 36 instrumentation covers MediaStore and a controlled SAF test provider. Third-party OEM provider behavior, broad physical-device coverage, and physical-device crash recovery remain unverified.
- **Hardware Performance & Finalization Timings**: Finalization requires local container merge/transcode, stream integrity validation, full media copy to storage, and atomic JobStore persistence. Stage timing telemetry is logged (`Finalize job=… stage=…`), but formal micro-benchmark timings across slow flash media and USB-OTG storage remain pending.
- The actual-resolution marker relies on yt-dlp exposing `height`. When it reports `NA`, validation falls back to the downloaded file's metadata. Playlists with mixed heights show a `min–max` range rather than one item's height.
- Playlist progress is deliberately scoped to the current item and current video/audio stream; the app does not invent an aggregate percentage when yt-dlp only reports per-item progress. The completed History size is the sum of published media outputs and excludes subtitle sidecars.
- Browser Fallback candidate estimation is best-effort. If the candidate's metadata is expired, inaccessible, or has no trustworthy size, the card shows unavailable rather than the page estimate.
- Diagnostic text sanitizes embedded URLs as well as dedicated URL fields; path/route context is retained while query values and fragments are redacted.
- Failed-download diagnostics may retain a sanitized original source URL for debugging; successful downloads do not persist source URLs, and a successful retry clears URL context from earlier failures. Export displays a warning and requires an explicit opt-in to include the full original source URL. Credentials and signed CDN access values remain redacted even when opted in.
- Selecting a resolution triggers a metadata extraction for that selector. The first estimate for a new selector can take up to the preflight timeout (25 seconds); a warm cache or an in-flight request is reused. Estimation failure never blocks a valid download.
- Browser Fallback discovers media in GeckoView and transfers the selected candidate with yt-dlp. Browser Fallback currently does not request or publish subtitles; subtitle options apply to the normal yt-dlp acquisition path.
- H.264 Compatibility Mode is optional and off by default. It needs a downloaded local video, an available FFmpeg `libx264` encoder, temporary storage and extra processing time/battery. Conversion fails safely if the codec cannot be probed or the output does not validate. Audio and subtitle streams are copied where the input/container permits.
- Custom yt-dlp templates apply to the explicitly selected normal attempt. They do not apply to Browser Fallback. VRKA blocks shell execution and path/output overrides, but a permitted yt-dlp option can still alter extraction behavior or fail on a particular site.
- The Browser Session clear action clears GeckoView runtime cookies, DOM storage, auth sessions, site data, caches and in-memory discovered candidates. It does not cancel an active private fallback session or clear app settings, history, queue, or diagnostics.
- Upgrading from previous releases may carry legacy extension or update state; migration logic cleans obsolete state upon first run.
- Component version is shown as unknown when the app cannot read a runtime, packaged-file or verified-update version. “Up to date” appears only after a check or verified component update.
- Android may stop the application process under memory pressure; interrupted background work can be resumed or retried.
- The new accessibility and destination strings have English/base translations in every locale and translated values for the 14 supported languages, but the new translations have not been reviewed by native speakers. Text expansion and RTL layouts for the new strings were not visually checked on a device.
- Subsystem testing on physical hardware was performed on a OnePlus 11 (Android 16, SDK 36, ARM64) for v5.1.1, and an in-place upgrade from v5.1.1 to v5.1.2 was confirmed on physical hardware by the maintainer. For complete categorization of verified subsystems, maintainer-reported upgrade verification, and unverified platform boundaries, refer to the authoritative record in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

## Validation Scope

Automated test and build verification results are recorded in [RELEASE_EVIDENCE.md](RELEASE_EVIDENCE.md) and [TEST_RESULTS.md](TEST_RESULTS.md). Authoritative device validation records are maintained in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).
