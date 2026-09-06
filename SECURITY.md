# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
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
apksigner verify --verbose --print-certs VRKA-Android-v4.0.1.apk
```

The signing certificate must match:
- **SHA-256 Fingerprint**: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`

### Checksum Verification

Every release includes an authentic `SHA256SUMS` manifest. Verify your download:

```bash
sha256sum -c SHA256SUMS
```

Or in PowerShell:

```powershell
(Get-FileHash .\VRKA-Android-v4.0.1.apk -Algorithm SHA256).Hash
```

---

## Security Architecture Highlights

- **External Key Isolation**: Production keystores and credentials are never stored in source control. Builds fail fast if release credentials are not provided via environment or local properties.
- **Content Filtering**: Embedded browser fallback sessions load with integrated uBlock Origin filtering to suppress unwanted advertising and tracking scripts.
- **Isolated Storage**: Downloads are confined to user-designated public media storage or app-scoped sandboxes using Android Storage Access Framework (SAF).
- **Local Stream Discovery**: Browser fallback sessions operate with isolated local cookies and hand off captured stream URLs directly to the local downloader without connecting to any external analytics service.
