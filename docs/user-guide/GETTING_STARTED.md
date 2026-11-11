# Getting Started with VRKA Android

## Requirements

- **Android version:** Android 8.0 (API 26) or newer (target SDK 36 / Android 16)
- **CPU architecture:** `arm64-v8a` (64-bit ARM devices; other ABIs are not shipped)
- **Storage space:** Sufficient free device storage for both temporary processing in app staging and the final published media file

## 1. Download & Verify the Official APK

Official releases are published only at:
[https://github.com/MaverickRox/VRKA-Android/releases](https://github.com/MaverickRox/VRKA-Android/releases)

Before installing a manually downloaded APK, compare its SHA-256 digest against the release `SHA256SUMS` file or [RELEASE_EVIDENCE.md](../../RELEASE_EVIDENCE.md):

**Windows (PowerShell):**
```powershell
(Get-FileHash .\VRKA-Android-v5.1.4.apk -Algorithm SHA256).Hash
```

**macOS / Linux:**
```bash
sha256sum -c SHA256SUMS --ignore-missing
```

All official production releases share package ID `com.mvrk.vrka` and the same RSA-4096 signing certificate (`9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`).

## 2. Fresh Install or Upgrade

- **Fresh installation:** Open `VRKA-Android-v5.1.4.apk` on your device and allow installation from your file manager or browser when prompted, or run `adb install VRKA-Android-v5.1.4.apk`.
- **Upgrading from v5.1.3 or v5.1.2 (in-app or manual):**
  Update directly through **Settings -> Check for Updates** or install `VRKA-Android-v5.1.4.apk` directly over the existing installation without uninstalling. Your Queue, History, Settings, and custom templates are preserved.
  *(If upgrading from v5.1.2 to v5.1.3/v5.1.4, VRKA automatically migrates legacy JSON storage into Room on first launch.)*
- **Upgrading from v5.0.2 or older (one-time bridge required):**
  Due to a historical filename validation bug inside the old `v5.0.2` client, its in-app updater cannot complete a direct self-update.
  1. Download **`VRKA-Android-v5.1.2.apk`** from the [v5.1.2 release page](https://github.com/MaverickRox/VRKA-Android/releases/tag/v5.1.2).
  2. Install it over `v5.0.2` **without uninstalling**.
  3. Open VRKA once so it finishes migrating, then update normally to `v5.1.4` via the in-app updater or the latest release APK.

## 3. Configure Your Download Folder

- **Android 10+ (API 29+):** VRKA exports completed media into `Downloads/VRKA` via `MediaStore.Downloads` by default. You can also choose an alternative Storage Access Framework (SAF) folder in **Settings -> Destination** or switch between **Use selected** and **Ask every time**.
- **Android 8.0 & 8.1 / Android 9 (API 26–28):** Android's scoped `MediaStore.Downloads` collection is not available on these versions. Select a valid folder via **Settings -> Destination** before starting your first download.

## 4. First Download

1. Copy a supported media URL, paste it into the **Download** screen (or share a link directly to VRKA from another app).
2. Choose **Video** (select a resolution chip or **Best available**, optional **60 FPS** preference) or **Audio** (**MP3** 128–320 kbps, **Opus**, or **WAV**).
3. Tap **Add to Queue**. When **Size Confirmation** is enabled in Settings, VRKA estimates the exact selected quality before adding the task to the queue.
4. Track progress in **Queue** and open or share completed files from **History**.
