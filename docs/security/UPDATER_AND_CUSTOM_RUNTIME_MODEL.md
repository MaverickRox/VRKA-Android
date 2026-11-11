# Updater, Command Policy, and Custom Runtime Security Model

This document summarizes the three application-level security boundaries enforced by VRKA Android (`v5.1.4`):

## 1. Cryptographically Authenticated App Self-Updater

1. **Strict HTTPS & Approved Hosts (`AppUpdateManager`):**
   - Queries `https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest` (24-hour startup gate; manual checks bypass the gate).
   - Accepts only HTTPS on default/443 with no userinfo or URI fragments.
   - Restricts hosts to `api.github.com`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, and `raw.githubusercontent.com`.
   - Enforces at most `MAX_REDIRECTS = 5` and validates every redirect target against protocol downgrades and unapproved hosts.
2. **Canonical Release & Asset Matching:**
   - Rejects draft and prerelease entries.
   - Matches canonical three-part release tags `vX.Y.Z`, canonical APK names `VRKA-Android-vX.Y.Z.apk`, manifest `VRKA-Android-vX.Y.Z.manifest.json`, and detached signature `VRKA-Android-vX.Y.Z.manifest.json.asc`. Rejects releases lacking signed manifests.
3. **OpenPGP Manifest Verification (`ReleaseManifestVerifier`):**
   - Pinned RSA-4096 trust anchor in `app/src/main/res/raw/vrka_release_pubkey.asc` (`0xD519380BEBA1B9F7`, fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`).
   - Enforces SHA-2 family digests only (SHA-256 / SHA-384 / SHA-512), RSA keys only, and an authenticated **hashed** Issuer Fingerprint subpacket matching the pinned fingerprint.
   - Rejects invalid ISO-8601 dates, future `issued_at` skew > 1 hour, expired `expires_at` (anti-freeze), metadata revision rollback (`metadata_revision < currentStoredRevision`), version-code downgrades, non-matching package ID, non-`arm64-v8a` ABI, or mismatched signing certificate SHA-256 (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).
4. **Two-Stage Staging & PackageInstaller Handoff (`AppUpdateDownloadWorker` + `AppUpdateManager`):**
   - Streams bytes to app-private `filesDir/updates/*.download.tmp`, enforcing exact byte length bounds and computing SHA-256 incrementally.
   - Validates package archive metadata (`packageName`, exact semantic `versionName`, `targetVersionCode` when present, signer certificate set matching both installed signer and manifest cert SHA-256, and non-`FLAG_DEBUGGABLE`).
   - Atomically moves into `filesDir/updates/VRKA-Android-vX.Y.Z.apk`, re-verifies staged file confinement, target version code, size, digest, and certificate, and launches Android `PackageInstaller` via `FileProvider`.

## 2. Custom Command Template Hardening (`CustomCommandPolicy` + `DownloadRequestFactory`)

- **No shell execution:** Argument strings are tokenized directly into `argv` via `parseArgumentLine` without invoking `sh`.
- **Save-time & execution-time enforcement:** Both `CustomCommandPolicy.validateTemplate()` (UI) and `DownloadRequestFactory.safeCustomArguments()` (request construction) share the same helpers:
  - Case-insensitive rejection of process execution, external downloader, config/plugin/batch, path/output (`-o`, `-P`), proxy, `--netrc`, `--no-check-certificates`, `--prefer-insecure`, `--impersonate`, and `--enable-file-urls` options.
  - Rejection of positional URLs/extra positional args and control characters (`\u0000`, `\r`, `\n`).
  - Template `--add-header` / `--add-headers` / `-H` entries accept only safe metadata headers (`Accept`, `Accept-Language`, `Origin`, `Referer`, `User-Agent`, `X-Requested-With`); credential-bearing headers (`Cookie`, `Authorization`, tokens) and attached `-H...` short options are rejected so persistent templates never retain or leak session secrets across redirects.
  - URL validation (`isHttpUrl`) treats submitted media URLs as single HTTP(S) URI tokens cleanly even if query parameters contain text resembling flags.

## 3. Custom `yt-dlp` Runtime Verification (`YtDlpRuntimeManager`)

- **Stock vs Custom boundary:** Bundled/component-updated stock `yt-dlp` is verified against the upstream `ytdlp_pubkey.asc` trust anchor (`SecureComponentUpdater`). Custom forks from GitHub (`owner/repo`) do not have upstream OpenPGP release guarantees for VRKA, so they bypass stock provenance and run with application UID privileges.
- **Explicit user risk acknowledgment & SHA-256 confirmation:** Installing or activating a custom runtime requires confirming the **"Untrusted custom runtime"** modal displaying repository, version, and full SHA-256 digest (`SettingsScreen`).
- **Pre-install staging checks:** Enforces GitHub `owner/repo` syntax, rejects upstream stock repos, caps asset size (`<= 64 MiB`), validates exact release asset byte length, checks that same-tag upstream binaries have not mutated silently (`existing.sha256 == downloadedSha256`), executes `--version` via bundled `libpython.so` in staging before promoting, and atomically replaces the binary.
- **Fail-closed continuous SHA-256 verification:** Every `activeBinary()`, `applyActiveRuntime()`, and `activateCustom()` call re-computes the SHA-256 digest of the on-disk custom binary against the persisted verified record (`verifiedAt > 0L`). Any missing or modified file reverts cleanly to **stock `yt-dlp`**, while corrupt local copies can be repaired by reinstalling the verified upstream tag when its hash matches.
