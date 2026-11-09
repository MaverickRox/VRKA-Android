# VRKA Android 5.1.2 — Release Notes

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 STILL REQUIRE A ONE-TIME MANUAL UPGRADE.** The v5.0.2 client cannot repair its own updater due to the legacy filename validation defect. Download and install `VRKA-Android-v5.1.2.apk` from the official GitHub release page (https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2) over v5.0.2 without uninstalling. Application data, queue, history, and settings are preserved.

VRKA Android 5.1.2 is a critical publication durability and crash-recovery release. It eliminates the publication crash window through a durable journal, ensures atomic playlist batching, protects against incomplete storage operations, and enforces strict six-step reconciliation persistence ordering.

## Highlights

1. **Durable Publication Journal** — In-flight storage publication transactions are durably persisted to `PublicationJournal` before files are created in Android storage providers (`MediaStore` or `SAF`), ensuring complete crash recoverability.
2. **Discoverable Pending Output Identities** — MediaStore and SAF objects use deterministic pending filenames (`.vrka_pending_${transactionId}_${filename}`). On process restart, orphaned objects created before URI persistence are discovered, verified, and either adopted or cleaned up without risk to unrelated files.
3. **Atomic Playlist Batch Intent Recording** — All publication intents across multi-file playlists are prepared and persisted atomically upfront before provider creation begins. Interrupted playlist transfers fail closed, preserving completed outputs in History while preventing false completion records.
4. **Strict Six-Step Persistence Ordering** — Reconciliation strictly verifies output file existence on disk, updates in-memory state, and confirms `JobStore` persistence *before* marking journal transactions reconciled and deleting staging data.
5. **Corrupted Journal Safeguards** — Corrupted journals are preserved in bounded recovery snapshots (`publication_journal.corrupt-$stamp.json`, max 3) and emit diagnostic alerts, never silently treating corrupt state as empty.
6. **SAF Rename Fallback Guarantees** — Document providers lacking `DocumentsContract.renameDocument` support are gracefully handled without data loss.
7. **Legacy In-Flight Fail-Closed Fallback** — In-flight jobs started under older releases without journal entries fail closed safely as `FAILED` (`"Interrupted (legacy transfer without publication journal)"`) when output URIs are empty.
8. **Preserved UI Contributions** — Comet progress glow (PR #10) and Liquid sliding navbar (PR #11) remain fully active and unchanged.

## Scope boundaries

- No new product features or unrelated UI redesigns.
- yt-dlp remains the extraction engine; FFmpeg remains the processing engine.
- Package ID (`com.mvrk.vrka`) and production signing identity are unchanged.
- While passive stream discovery and uBlock filtering in GeckoView were verified, full end-to-end media transfer on anti-bot sites remains an unverified platform boundary.

## Release Verification

- **Production APK:** `VRKA-Android-v5.1.2.apk`
- **Package ID:** `com.mvrk.vrka`
- **Version Code:** `50102`
- **Version Name:** `5.1.2`
- **File Size:** `157,243,017` bytes (149.96 MB)
- **SHA-256:** `3a28262db691858757afd9edbded1bedfcbbcef5d1eeeb56743a16d08eeafe9a`
- **SHA-1:** `81ff63b8b0fd6ff7125d0e1c7b5b689d35a0e87c`
- **Signing Scheme:** APK Signature Scheme v2 (RSA 4096-bit)
- **Signing Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Signing Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **OpenPGP Trust Anchor:** Key ID `0xD519380BEBA1B9F7`, Fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **Automated Tests:** 747 JVM unit tests across 73 suites (0 failures, 0 errors, 0 skipped), lint passed (0 errors/fatal).

---

# Historical: VRKA Android 5.1.1


> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 STILL REQUIRE A ONE-TIME MANUAL UPGRADE.** The v5.0.2 client cannot repair its own updater due to the legacy filename validation defect. Download and install `VRKA-Android-v5.1.1.apk` from the official GitHub release page (https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.1) over v5.0.2 without uninstalling. Application data, queue, history, and settings are preserved.

VRKA Android 5.1.1 is a focused reliability release. It keeps the two merged UI contributions (Comet progress glow and Liquid sliding navbar) and corrects the download-size estimation architecture across format preflight, resolution selection, active transfer, and final media accounting.

## Highlights

1. **Comet-style progress glow** — preserved from PR #10, with the same visual geometry and reduced-motion behavior.
2. **Liquid sliding navbar** — preserved from PR #11.
3. **Corrected resolution-specific size estimation** — the yt-dlp preflight and the transfer now use the same format selector; resolution chips use the same revealed codec preference and ranking; changing resolution changes the displayed size.
4. **Improved Browser Fallback estimates** — the stale page-level estimate is cleared and the selected candidate is estimated independently with the same URL, headers, referer and format selector as the transfer.
5. **Queue and History persistence protection** — malformed files are preserved, valid records are salvaged, schema versions are explicit, and failed writes are reported.
6. **Android 8 and Android 9 destination handling** — a valid user-selected folder is required before downloading; API 29+ keeps Downloads/VRKA.
7. **Diagnostic URL privacy and failure context** — signed/credential-like query values and path segments are redacted before persistence and export. Failed downloads retain the original source URL locally; successful downloads and successful retries do not. Export shows a warning and requires opt-in for the full original source URL.
8. **Strict updater timestamps** — impossible dates, incomplete values, and trailing text are rejected while skew, expiry, signature, and anti-rollback policy remain unchanged.
9. **Hermetic updater tests** — temporary test keys replace all production-key discovery, and the cryptographic tests run on every normal test invocation.
10. **Progress accessibility** — determinate range, current value, and a localized label are exposed; duplicate visible percentage semantics are removed; the visual glow is unchanged.
11. **Post-processing honesty** — merge, trim, H.264 conversion, metadata, embedded subtitles, and sponsor cuts make a size approximate rather than "exact".
12. **Actual resolution, stream stage and playlist accounting** — a `before_dl` marker records per-item heights and stream metadata, mixed playlists are summarized honestly, video/muxed streams are not mislabeled as audio when a denominator changes, active progress identifies the current stream/item, and History reports aggregate published media size.

## Scope boundaries

- No general-purpose browser toolbar, arbitrary browser navigation, batch sharing, native HLS/DASH transfer engine, scheduling system, database migration, or architecture rewrite.
- yt-dlp remains the extraction/transfer engine; FFmpeg remains the local processing engine.
- Package ID and production signing identity are unchanged.

## Release Verification

- **Production APK:** `VRKA-Android-v5.1.1.apk`
- **Package ID:** `com.mvrk.vrka`
- **Version Code:** `50101`
- **Version Name:** `5.1.1`
- **File Size:** `157,231,575` bytes
- **SHA-256:** `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`
- **SHA-1:** `acc9b903669158f399a1c93607309cb9d5649fb0`
- **Signing Scheme:** APK Signature Scheme v2 (RSA 4096-bit)
- **Signing Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **OpenPGP Trust Anchor:** Key ID `0xD519380BEBA1B9F7`, Fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **Automated Tests:** 732 JVM unit tests across 72 suites (0 failures, 0 errors, 0 skipped), lint passed (0 errors/fatal).
- **Physical Device Validation:** Verified on OnePlus 11 (Android 16, SDK 36, arm64-v8a) via ADB: 4K/2K/Best estimate synchronization confirmed (~54.75 MB), neutral "Downloading media" state observed, and final 52.2 MiB output validated in Downloads/VRKA. Comprehensive subsystem verification classifications and platform boundaries are authoritatively documented in [DEVICE_VALIDATION.md](DEVICE_VALIDATION.md).

---

# Historical: VRKA Android 5.1.0

> [!IMPORTANT]
> **USERS UPGRADING FROM VRKA v5.0.2 MUST DOWNLOAD AND INSTALL v5.1.0 DIRECTLY FROM THE OFFICIAL GITHUB RELEASE PAGE. THE v5.0.2 IN-APP UPDATER CANNOT COMPLETE THIS UPDATE. DO NOT USE THE RETRY BUTTON.**
> 
> **OFFICIAL DOWNLOAD PAGE:**
> https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.0
> 
> **INSTALLATION:** Download `VRKA-Android-v5.1.0.apk` from the Assets section and install it over your existing VRKA installation. DO NOT UNINSTALL v5.0.2 FIRST.

VRKA Android 5.1.0 introduces comprehensive interface localization across 14 languages, preflight download size estimation, an AMOLED-optimized luminous wake progress indicator, custom yt-dlp runtime validation, an updated GeckoView 159 engine, and a cryptographically authenticated in-app updater architecture.

## Highlights

- **14 Languages & System Default:** Complete interface localization supporting Arabic (with full RTL support), Bengali, Simplified Chinese, English, French, German, Gujarati, Hindi, Kannada, Portuguese (Brazil), Portuguese (Portugal), Spanish, Tamil, and Telugu, with resource-key parity across all languages.
- **Preflight Download Size Estimation:** Accurate approximate size estimates displayed on the download card and Android Share Sheet prior to queueing or downloading.
- **Directional Luminous Wake Progress Bar:** Redesigned download progress renderer featuring an 18-track streak and cached analytic teardrop glow optimized for AMOLED displays.
- **Custom yt-dlp Runtime Management:** Settings now supports configuring and validating custom yt-dlp forks while keeping upstream OpenPGP signature verification for official releases.
- **GeckoView 159 Engine Update:** Bundled Mozilla GeckoView runtime updated to 159.0.20261022150525 with uBlock Origin 1.74.0 and hardened private-session cleanup.
- **Notification & Share Sheet Refinements:** Streamlined notifications without duplicate large icons, and unified format selection on the compact floating share card.
- **Secure In-App Updater Redesign:** Cryptographically verified release manifest architecture (`VRKA-Android-v5.1.0.manifest.json`) authenticated by a detached OpenPGP signature (`VRKA-Android-v5.1.0.manifest.json.asc`). Pinned 4096-bit RSA OpenPGP release trust anchor (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`) with anti-freeze and anti-rollback protections. Staging verification defect resolved by separating temporary archive integrity checks from post-rename staged APK checks.

## Release Verification

- **Production APK:** `VRKA-Android-v5.1.0.apk`
- **Package ID:** `com.mvrk.vrka`
- **Version Code:** `50100`
- **Version Name:** `5.1.0`
- **File Size:** 157,202,797 bytes
- **SHA-256:** `5051ECFD6319CA2CAFED4287904006FBC54B06FAAC5444D3A3C73DF5CDC848AA`
- **Signing Scheme:** APK Signature Scheme v2 (RSA 4096-bit)
- **Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`
- **Certificate SHA-1:** `7758980F74A503684BF1A187994E447823D19D7C`
- **Release Manifest:** `VRKA-Android-v5.1.0.manifest.json` (metadata revision 3)
  - SHA-256: `F1A47B342061F5149B293E1361695F12B69E5FA3D5D912AC85A520A428CAFA8E`
- **Detached OpenPGP Signature:** `VRKA-Android-v5.1.0.manifest.json.asc`
  - SHA-256: `17B3C504EB4352FCB34815975E973AB8AA6AAEBC6FD1852E2719646858B4FB35`
- **Exported Public Key:** `VRKA-Release-Public-Key.asc` (pinned fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`)

---

# Historical: VRKA Android 5.0.2

## 5.0.2

- Fixed in-app application update detection and APK selection.
- Improved background application updates with persistent WorkManager downloads.
- Added retry handling for temporary network failures and connection interruptions.
- Improved APK staging, verification, and update-ready notification handling.
- Improved update installation and recovery behavior.

## 5.0

- Redesigned the interface with the Liquid Glass navigation system and horizontal swipe navigation.
- Added configurable concurrent downloads with bounded scheduling and independent cancellation/failure handling.
- Added Android Share Sheet support with robust shared-URL extraction and Download URL pre-filling.
- Added actual downloaded-resolution tracking and improved Queue/History media metadata.
- Added safer History management with record-only deletion, file deletion, multi-select, batch actions, and destructive-action safeguards.
- Improved download state persistence across process restarts and hardened terminal job-state handling.
- Restored and hardened Browser Fallback discovery and transfer handoff through GeckoView and yt-dlp.
- Improved Browser Fallback reliability by separating browser UI/session lifecycle from active transfers and removing blocking worker operations.
- Added validated custom yt-dlp argument templates with shell-free execution, safe staging/output handling, and fallback isolation.
- Improved Android yt-dlp compatibility, including handling of unsupported impersonation targets.
- Added optional, default-off H.264 Compatibility Mode using local FFmpeg conversion and post-conversion validation.
- Reworked subtitle handling with targeted language selection, rate-limit/failure isolation, container-aware embedding, and final subtitle-track verification.
- Improved YouTube subtitle handling for creator/manual captions and automatic-caption fallback.
- Upgraded GeckoView to 156.0.20260921121718 and improved browser-component/runtime reporting.
- Improved component updater reliability, installed-version detection, state transitions, cancellation, and channel switching.
- Hardened request/header handling, private browser-candidate state, diagnostics, output validation, and staged-file cleanup.
- Improved Settings descriptions and component/status presentation to reflect the final production architecture.

## Known Limitations

- Browser Fallback downloads do not currently fetch subtitles.
- H.264 Compatibility Mode is optional and disabled by default.
- Browser Fallback remains dependent on site discovery and yt-dlp/site compatibility.
- Upgrading from v5.0.2 requires a one-time manual download and installation of the v5.1.0 APK from GitHub due to a client-side verification check on temporary download files in the v5.0.2 binary.
