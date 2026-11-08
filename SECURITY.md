# Security Policy

## Supported Versions

| Version | Supported | Notes |
| :--- | :--- | :--- |
| 5.1.1 | :white_check_mark: | Current official stable release |
| 5.1.0 | :white_check_mark: | Prior stable release |
| 5.0.2 and older | :x: | Unsupported; upgrade to v5.1.1 directly |

## Reporting a Vulnerability

Report vulnerabilities through the [GitHub security page](https://github.com/MaverickRox/VRKA-Android/security). Include reproduction steps and sanitized logs; do not attach cookies, authorization values, signed media URLs, or personal device identifiers.

## Release Integrity

The official v5.1.1 and v5.1.0 release APKs are production-signed with APK Signature Scheme v2 (RSA 4096-bit). Verify signatures and SHA-256 against `RELEASE_EVIDENCE.md` or published `SHA256SUMS` before installation.

- **v5.1.1 Release APK:** `VRKA-Android-v5.1.1.apk`
- **v5.1.1 SHA-256:** `781d7910d9b5855d4060c247c09ac3f89156ebfd202c8af5385eea816380f4f8`
- **Android Signing Certificate SHA-256:** `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`

The `release` build type requires explicit signing properties; it has no debug-key fallback. Never publish signing properties, keystores, passwords, or private signing keys in this repository or artifact package.

## OpenPGP Release Signing and Trust Anchor

All official application updates are cryptographically authenticated using detached OpenPGP signatures over versioned release manifests (`VRKA-Android-vX.Y.Z.manifest.json`).

The application binary embeds and pins the official VRKA OpenPGP release signing public key:
- **User ID:** `VRKA Official Release Signer (VRKA Persistent Release Signing Key) <security@vrka.org>`
- **Key ID:** `0xD519380BEBA1B9F7`
- **Key Fingerprint:** `71165A658B3A8612AC2568C6D519380BEBA1B9F7`
- **Key Algorithm:** RSA 4096-bit
- **Resource Location:** `app/src/main/res/raw/vrka_release_pubkey.asc`

### Cryptographic Policy and Downgrade Defense
- **Algorithm Enforcement:** The updater strictly rejects weak or broken cryptographic algorithms (MD5, SHA-1, RIPEMD-160, DSA). Release signatures must use SHA-256 or stronger.
- **Issuer Fingerprint Verification:** Detached signatures must contain an issuer fingerprint subpacket matching the pinned trust anchor.
- **Anti-Rollback Protection:** Monotonically increasing `metadata_revision` numbers prevent replay attacks and downgrade of release metadata.
- **Anti-Downgrade Protection:** Updates with `versionCode` less than the currently installed version are rejected before download.
- **Anti-Freeze Protection:** Release manifests enforce an expiration timestamp (`expires_at`), preventing freeze attacks where an attacker serves stale but validly signed metadata.
- **Certificate Pinning:** The manifest binds the target APK to the production signing certificate SHA-256 (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).

## Security Boundaries

- Custom yt-dlp templates are argument arrays passed to yt-dlp, not shell commands. Validation blocks shell hooks, external downloaders, config files, cookie files, and output-path overrides. VRKA owns staging and publication paths.
- Browser Fallback always hands the selected candidate to the standard yt-dlp request path. It keeps the original public page URL as the job source and clears custom template arguments and user-defined headers before transfer construction. Captured browser headers are validated and origin-bound by `HeaderValidation`.
- User URLs are validated HTTP(S) inputs. They remain individual argv values; no shell is used.
- Failed-download diagnostics may retain the original submitted source URL for reproducibility, but credential-like query values, embedded signed CDN URLs, and source-userinfo are redacted. Successful downloads do not add source URLs. Diagnostic export shows a warning and requires an explicit opt-in to include the full original source URL. Avoid sharing diagnostic exports containing site or account identifiers.
- GeckoView navigates in a task-scoped private session. The Settings clear action clears GeckoView cookies, DOM storage, auth sessions, site data, caches and the in-memory candidate bridge. It does not cancel an active task.
- yt-dlp is the normal and Browser Fallback transfer engine. FFmpeg is used for local processing, probing and validation. The active browser extensions are the VRKA media detector and uBlock Origin. Dormant Media3 and gated N_m3u8DL-RE code are not advertised or routed as active download capabilities.

## Out of Scope

VRKA does not bypass DRM, authentication, payment, CAPTCHA, consent, or other site access controls. A compromised operating system or device can defeat application sandbox protections. A site's behavior and third-party component availability can change after release.
