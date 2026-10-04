# Security Policy

## Supported Versions

| Version | Supported |
| :--- | :--- |
| 5.0 | :white_check_mark: |
| 4.5.3 and older | :x: |

## Reporting a Vulnerability

Report vulnerabilities through the [GitHub security page](https://github.com/MaverickRox/VRKA-Android/security). Include reproduction steps and sanitized logs; do not attach cookies, authorization values, signed media URLs, or personal device identifiers.

## Release Integrity

The official v5.0 release APK is production-signed. Verify its signature and SHA-256 against `RELEASE_EVIDENCE.md` or official GitHub release checksums before installation.

The `release` build type requires explicit signing properties; it has no debug-key fallback. Never publish signing properties, keystores, passwords, or private signing keys in this repository or artifact package.

## Security Boundaries

- Custom yt-dlp templates are argument arrays passed to yt-dlp, not shell commands. Validation blocks shell hooks, external downloaders, config files, cookie files, and output-path overrides. VRKA owns staging and publication paths.
- Browser Fallback always hands the selected candidate to the standard yt-dlp request path. It keeps the original public page URL as the job source and clears custom template arguments and user-defined headers before transfer construction. Captured browser headers are validated and origin-bound by `HeaderValidation`.
- User URLs are validated HTTP(S) inputs. They remain individual argv values; no shell is used.
- Diagnostics and persisted job data sanitize URLs and sensitive header values. Avoid exporting diagnostic files containing site or account identifiers.
- GeckoView navigates in a task-scoped private session. The Settings clear action clears GeckoView cookies, DOM storage, auth sessions, site data, caches and the in-memory candidate bridge. It does not cancel an active task.
- yt-dlp is the normal and Browser Fallback transfer engine. FFmpeg is used for local processing, probing and validation. The active browser extensions are the VRKA media detector and uBlock Origin. Dormant Media3 and gated N_m3u8DL-RE code are not advertised or routed as active download capabilities.

## Out of Scope

VRKA does not bypass DRM, authentication, payment, CAPTCHA, consent, or other site access controls. A compromised operating system or device can defeat application sandbox protections. A site's behavior and third-party component availability can change after release.
