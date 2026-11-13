# VRKA Android 5.1.5

## Highlights

- Playlist downloads: Playlists are now expanded into individual download jobs, allowing multiple videos to download concurrently with separate progress and History entries.
- Playlist selection: When a YouTube link contains both a video and playlist, choose whether to download the entire playlist or just that video.
- More reliable file saving: Improved MediaStore publishing and recovery to prevent completed downloads from being lost when a storage operation fails.
- Updater improvements: Fixed update checks when the installed version is already current or newer, while retaining downgrade protection.
- Android 16 download notifications: Improved background download handling and notification separation for concurrent downloads.
- Updated Download branding: Replaced the Download page's text logo with the new transparent purple VRKA wordmark, compatible with light and dark themes.

## Reliability and security

- Fixed queue completion and cleanup issues affecting subsequent downloads and History deletion.
- Added cryptographic verification of uBlock Origin extension packages before installation.
- Improved media detector credential handling and redirect restrictions.
- Refined R8/ProGuard rules while retaining required serialization, database and background-work functionality.

## Compatibility

- Android 8.0 or later.
- ARM64 (`arm64-v8a`).
- Target SDK 36.

## Updating

- From v5.1.4, v5.1.3 or v5.1.2: Update through **Settings -> Check for Updates**, or install the APK over the existing installation.
- Do not uninstall the previous version. Existing Queue, History, Settings and custom templates are preserved.
- For changelogs of older releases, see [CHANGELOG.md](CHANGELOG.md) or [GitHub Releases](https://github.com/MaverickRox/VRKA-Android/releases).
