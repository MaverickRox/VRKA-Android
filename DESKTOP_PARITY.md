# Desktop parity

This audit compares VRKA Android 1.0.0 with Windows VRKA build 007.
The Windows source is read-only.

| Desktop feature | Android state | Action |
| --- | --- | --- |
| URL analysis and normal yt-dlp extraction | Match | Keep the central extractor path. Use Browser fallback only after a bounded failure. |
| Video and Best available | Match | The physical YouTube test passed with video and audio. |
| Resolution controls | Match | Each value is a maximum cap. The 2160p-on-1080p physical test passed. |
| Prefer 60 FPS | Match | Add an optional resolution-first `fps` sort. The default selector does not change. |
| Audio mode | Match | Keep audio selection separate from video selection. |
| MP3 320/256/192/128 | Match | Keep the four structured bitrate choices. |
| WAV and FLAC | Match | Keep the current FFmpeg post-process path. |
| Trim | Match | Keep start/end controls. Reject invalid ranges before enqueue. |
| Playlist and inclusive range | Match | Keep structured start/end values and bounded playlist names. |
| Subtitles | Match | Add separate subtitle download, automatic-caption, language, and video-embed controls. |
| SponsorBlock | Match | Keep the opt-in control. Expose the structured category list. |
| Metadata and audio thumbnail | Match | Expose the existing request options. |
| Cookies and browser session | Platform equivalent | Browser fallback captures approved session headers and cookies for the same job. Desktop browser-cookie import is not exposed on Android. |
| Proxy, rate limit, and force IPv4 | Deferred | The core downloader can support these arguments. The release UI does not expose untested controls. |
| Custom yt-dlp arguments | Internal only | Do not expose a free-text command surface in the 1.0 release. The request builder removes video-to-audio overrides. |
| Archive and output template | Platform equivalent | Android uses persistent History and MediaStore/SAF publication. It does not expose raw path templates. |
| Queue, progress, speed, ETA | Match | The physical device tests passed. |
| Cancel and retry | Match | The physical device tests passed. |
| History, Open, Share, Delete | Match | The physical device tests passed. |
| yt-dlp updater | Match | Keep Stable and Nightly channels. A failed update keeps the known-good runtime. |
| Storage selection | Platform equivalent | Use Downloads/VRKA or an Android document-tree location. |
| Light and Dark appearance | Match | Use one Light/Dark switch. |
| AMOLED appearance | Android extension | Keep a separate remembered AMOLED switch. Apply it only in Dark mode. |
| Product font | Match | Use the Desktop Space Mono Regular and Bold assets for all Compose typography. |
| Icon language | Partial | Keep the compact current navigation glyphs. Do not add a new icon dependency before release. |
| Browser fallback | Platform feature | Keep the same-job handoff, conservative containment, lifecycle reconstruction, and bounded error state. |

## Deferred controls

The Android release does not expose desktop browser-cookie import.
The Android release does not expose proxy, rate-limit, force-IPv4, or free-text yt-dlp controls.
These controls need separate physical tests and input-policy work.
Their omission does not change the accepted core download paths.

## Source changes

The parity pass adds Space Mono typography.
The parity pass adds the Desktop-style Light/Dark selection and separate AMOLED preference.
The parity pass adds the bounded 60 FPS, subtitle, metadata, thumbnail, and SponsorBlock controls.
The parity pass does not change the accepted default YouTube selector.
