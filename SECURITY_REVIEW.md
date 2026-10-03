# VRKA Android 5.0 — Security Review

Date: 2026-10-03
Scope: VRKA Android app source, tests, build inputs, and release artifact. F-Droid and other external distribution artifacts were excluded.

## Custom yt-dlp templates

- Templates are parsed and passed as argv elements; VRKA does not execute a shell.
- The public HTTP(S) URL remains a separate validated argv element.
- Dangerous output-path, shell-hook, and credential-bearing header options are rejected.
- VRKA controls the staging/output path.
- Browser Fallback rebuilds a sanitized request without the selected template, custom arguments, or typed custom headers. A Gecko-derived private candidate is not promoted to the user's public URL.
- Cancellation uses the normal job lifecycle and terminates the associated yt-dlp work.

## URL, headers, and persisted state

- External job URLs are restricted to HTTP(S).
- Header validation rejects malformed/CRLF values and constrains sensitive browser-session headers to approved use.
- Browser candidate URLs and browser headers remain in memory and are excluded from persisted job data.
- Sensitive headers are filtered from persistence.
- Diagnostics sanitize URLs, headers, and free text before storage or display.
- Browser Fallback failures are presented generically instead of exposing temporary private candidate URLs.

## Output and component boundaries

Staging/publication remain controlled by VRKA, with output validation before publication and staged-file cleanup on failure/cancellation. yt-dlp is the normal and fallback transfer engine; FFmpeg is for local processing. Puemos is bundled/update-managed, not a transfer engine. Media3/remote-FFmpeg code is dormant and N_m3u8DL-RE is gated off.

A targeted scan found no embedded production signing material, tokens, or credentials. Matches were test fixtures and runtime property-name lookups. No direct app/build reference to an F-Droid patch or metadata file was found.

This was a source/build review, not a dynamic penetration test. No emulator, ADB, physical device, or device automation was used. Browser Fallback still depends on yt-dlp and site behavior. The development APK uses a debug signing key.
