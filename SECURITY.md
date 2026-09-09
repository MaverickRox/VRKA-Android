# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| 4.5.1 | :white_check_mark: |
| 4.5 | :white_check_mark: |
| 4.0.3 | :white_check_mark: |
| 4.0.2 | :white_check_mark: |
| 4.0.1 | :white_check_mark: |
| 4.0.0 | :white_check_mark: |
| < 4.0.0 | :x: |

---

## Reporting a Vulnerability

We take the security and privacy of VRKA Android seriously. If you discover a potential security issue or vulnerability, please report it responsibly:

1. Navigate to the repository security page: [https://github.com/MaverickRox/VRKA-Android/security](https://github.com/MaverickRox/VRKA-Android/security)
2. Submit a report or contact the repository maintainer with details to reproduce the issue.
3. Provide a clear explanation, steps to reproduce, and any relevant logs (with sensitive data redacted).

Please allow up to 48 hours for initial triage before public disclosure.

---

## Release Integrity & Cryptographic Signing Lineage

All official release binaries of VRKA Android are signed using the canonical v1.0 signing certificate. The build system strictly requires external signing credentials for release artifacts and prevents silent fallbacks to debug keys.

### Certificate Fingerprint

Verify the signing certificate of downloaded release APKs using `apksigner`:

```bash
apksigner verify --verbose --print-certs VRKA-Android-v4.5.1.apk
```

The signing certificate must match:
- **SHA-256 Fingerprint**: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`
- **SHA-1 Fingerprint**: `7758980f74a503684bf1a187994e447823d19d7c`

### Checksum Verification

Every release includes an authentic `SHA256SUMS` manifest. Verify your download:

```bash
sha256sum -c SHA256SUMS
```

Or in PowerShell:

```powershell
(Get-FileHash .\VRKA-Android-v4.5.1.apk -Algorithm SHA256).Hash
```

---

## Threat Model

### Protected Scope

- **Cryptographically Authenticated Updates**: External component updates (`yt-dlp`) are fetched strictly over HTTPS from allowlisted GitHub release endpoints. Release checksum manifests (`SHA2-256SUMS`) must be authenticated via detached OpenPGP signatures (`SHA2-256SUMS.sig`) using standard Bouncy Castle APIs against the pinned upstream trust anchor (`AC0CBBE6848D6A873464AF4E57CF65933B5A7581`). Binaries are verified against the authenticated manifest, staged transactionally, validated for executable integrity (`versionName`), and automatically rolled back if validation fails.
- **HTTP Header Validation & Injection Prevention**: Custom HTTP headers are validated strictly against RFC 7230 / RFC 9110 token specifications. Header names containing non-token characters or CRLF sequences (`\r`, `\n`) are rejected at input time. Case-insensitive precedence ensures deduplication, and dedicated `Referer` and `Origin` parameters maintain unambiguous routing precedence.
- **Sensitive Header Redaction & Diagnostics Sanitization**: Sensitive header keys (including `Authorization`, `Cookie`, `X-Api-Key`, and secret tokens) are systematically masked (`[REDACTED]`) across logs, crash diagnostics, on-device stores, and UI cards. Diagnostic traces automatically sanitize URL query tokens, signatures, and credentials before rendering or clipboard export.
- **Bundled Extension Scope**: Content filtering (`uBlock Origin`) and stream discovery (`Puemos`) are immutable assets packaged into the application APK and updated exclusively through signed application releases.
- **Browser Session Isolation & Clearing**: Embedded GeckoView fallback sessions operate in an isolated sandbox with strict tracking protection. Users can explicitly purge all cookies, active auth sessions, DOM storages, and caches via Settings without affecting download history or configuration.
- **Application Self-Update Security**: The in-app application updater verifies releases exclusively via HTTPS over approved GitHub endpoints (`api.github.com`, `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com`, `raw.githubusercontent.com`, and `*.githubusercontent.com`). Every redirect hop is independently validated against protocol downgrade and arbitrary hosts. Released APKs must strictly match the canonical `VRKA-Android-vX.Y.Z.apk` naming convention corresponding to the release version. Package installation is handed off via Android `FileProvider` with scoped content URI permissions, requiring explicit user confirmation.
- **Release Continuity**: The build pipeline enforces canonical signing certificate verification to prevent APK takeover and update incompatibilities.

### Out of Scope / Not Guaranteed

- **Compromised Host OS**: Devices with compromised root access, modified Android frameworks, or active spyware cannot guarantee application memory isolation or secure storage guarantees.
- **Physical Access**: Unencrypted physical access to an unlocked device bypasses Android's local application sandboxing.
- **Malicious Third-Party Content**: While GeckoView and uBlock Origin mitigate common script execution threats, untrusted websites visited during fallback navigation remain subject to browser-level sandboxing limits.
