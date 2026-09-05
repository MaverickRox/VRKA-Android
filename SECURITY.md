# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| 4.0.0 | :white_check_mark: |
| < 4.0.0 | :x: |

---

## Reporting a Vulnerability

We take the security and privacy of VRKA Android seriously. If you discover a potential vulnerability, please report it responsibly using GitHub Private Vulnerability Reporting:

1. Navigate to the **Security** tab of this repository: [https://github.com/MaverickRox/VRKA-Android/security/advisories/new](https://github.com/MaverickRox/VRKA-Android/security/advisories/new)
2. Click **Report a vulnerability**.
3. Provide a clear explanation of the issue, steps to reproduce, and any relevant logs (with personal data redacted).

Please allow up to 48 hours for initial triage before public disclosure.

---

## Release Integrity & Cryptographic Signing Lineage

All official release binaries of VRKA Android are signed using the canonical v1.0 certificate authority. The build system strictly requires external signing credentials for release artifacts and prevents silent fallbacks to debug keys.

### Certificate Fingerprints

Verify the signing certificate of downloaded release APKs using `apksigner`:

```bash
apksigner verify --verbose --print-certs VRKA-Android-v4.0.0.apk
```

The signing certificate must match:
- **SHA-256 Fingerprint**: `9befdbf4fb00acedb72f866ce4016944c95ea99448e205768383b310ca11e1fa`
- **SHA-1 Fingerprint**: `bb4c93ebc5e2d1eb7d00f8bfad1357da8e5ec9c1`
- **Subject / Issuer**: `EMAILADDRESS=maverickrox@example.com, CN=MaverickRox, OU=Development, O=VRKA, L=City, ST=State, C=US`

### Checksum Verification

Every release includes an authentic `SHA256SUMS` manifest. Verify your download:

```bash
sha256sum -c SHA256SUMS
```

Or in PowerShell:

```powershell
(Get-FileHash .\VRKA-Android-v4.0.0.apk -Algorithm SHA256).Hash
```

---

## Security Architecture Highlights

- **External Key Isolation**: Production keystores and credentials are never stored in source control. Builds fail fast if release credentials are not provided via environment or local properties.
- **Content Filtering**: Embedded browser fallback sessions load with integrated uBlock Origin protection to block malicious ads, trackers, and unwanted redirect scripts.
- **Isolated Storage**: Downloads are confined to user-designated public media storage or app-scoped sandboxes using Android Storage Access Framework (SAF).
- **Session Privacy**: Browser fallback sessions run with isolated cookie stores and pass captured stream URLs directly to the local downloader without external telemetry.
