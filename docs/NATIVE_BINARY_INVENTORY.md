# Native Binary Provenance Inventory

The machine-readable inventory is [native-binaries.json](native-binaries.json), and full package-level licensing notices (including Maven transitive dependencies and vendored UI components) are recorded in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). SHA-256 values
for Maven artifacts come from `gradle/verification-metadata.xml`; hashes for bundled files are
computed from the checked-in assets.

## Confirmed inventory

| Component | Delivery | Version | Origin | Bundled native/resource payload | Verification |
|---|---|---|---|---|---|
| Mozilla GeckoView | prebuilt AAR (direct, `MPL-2.0`) | 159.0.20261022150525 | Mozilla Maven | `libclearkey.so`, `libcrashhelper.so`, `libcrashtools.so`, `libfreebl3.so`, `libgkcodecs.so`, `liblgpllibs.so`, `libmozavcodec.so`, `libmozavutil.so`, `libmozglue.so`, `libnss3.so`, `libplugin-container.so`, `libsoftokn3.so`, `libxul.so`, `assets/omni.ja` | SHA-256 in verification metadata |
| youtubedl-android library | prebuilt AAR (direct, `GPL-3.0`) | 0.18.1 | Maven Central | `libpython.so` (`PSF-2.0`), `libpython.zip.so`, `libqjs.so` (`MIT`) | SHA-256 in verification metadata |
| youtubedl-android FFmpeg | prebuilt AAR (direct, `GPL-3.0-or-later`) | 0.18.1 (FFmpeg 7.1.1) | Maven Central / termux-packages | `libffmpeg.so` (317,120 B), `libffmpeg.zip.so` (35,624,931 B), `libffprobe.so` (233,664 B) (ARM64; configured `--enable-gpl --enable-version3`, NDK r28c) | SHA-256 in verification metadata & native-binaries.json |
| AndroidX Graphics Path | prebuilt AAR (transitive, `Apache-2.0`) | 1.0.1 | Google Maven | `libandroidx.graphics.path.so` | SHA-256 in verification metadata & lockfile |
| AndroidX DataStore Core | prebuilt AAR (transitive, `Apache-2.0`) | 1.2.1 | Google Maven | `libdatastore_shared_counter.so` | SHA-256 in verification metadata & lockfile |
| Stock yt-dlp runtime | bundled raw resource (`app/src/main/res/raw/ytdlp`, `Unlicense`) | `2026.08.19` | upstream yt-dlp zipapp | overrides AAR `res/raw/ytdlp` | SHA-256 of checked-in asset; OpenPGP for updates |
| yt-dlp OpenPGP key | bundled raw resource (`app/src/main/res/raw/ytdlp_pubkey.asc`) | pinned upstream key | upstream yt-dlp release signing key | trust anchor for `SecureComponentUpdater` | SHA-256 of checked-in asset |
| uBlock Origin | signed Firefox XPI asset (`GPL-3.0`) | pinned bundled XPI (`1.74.0`) | upstream uBlock release (AMO signed) | `app/src/main/assets/extensions/ublock.xpi` | Pinned version allowlist + XPI PKCS#7/CMS + AMO CA SPKI + SHA-256 |
| VRKA Media Detector | first-party JS extension (`GPL-3.0`) | repository source | this repository | `manifest.json`, `background.js`, `content.js` | source review; LF asset hashes |
| Release public key | ASC resource | pinned v5.1.4/v5.1.5 trust anchor | this repository | `app/src/main/res/raw/vrka_release_pubkey.asc` | SHA-256 and fingerprint checks |

## What is not claimed

- These AARs and `yt-dlp` / `ublock.xpi` assets are downloaded prebuilt artifacts, not built reproducibly in this repository.
- A matching checksum proves artifact integrity relative to the committed metadata; it does not
  prove the upstream project is vulnerability-free.
- The bundled `res/raw/ytdlp` runtime and AAR native libraries (`libpython.so`, `libqjs.so`, `libffmpeg.so`, GeckoView `.so`s) are not independently rebuilt by
  VRKA. The official component updater can replace `yt-dlp` with a signed release artifact.
- Custom runtimes added by the user via GitHub `owner/repo` bypass stock trust-anchor verification and execute with application privileges after SHA-256 pinning and explicit confirmation; users must return to stock to restore stock provenance.
- No full native SBOM or binary-to-source reproducibility attestation is published for v5.1.3 or v5.1.4.
- Custom `yt-dlp` runtimes selected in Settings undergo SHA-256 pin verification, local corruption repair on re-install/update when matching the recorded digest, and explicit experimental-runtime confirmation (`YtDlpRuntimeManager`).
- CI detects changes to checked-in assets, lockfile pins, verification metadata, and dependency versions; it does not rebuild or
  byte-compare upstream native binaries.
- `ublock.xpi` and the checked-in `res/raw/ytdlp` zipapp are not covered by Gradle dependency monitoring; their upstream releases must be reviewed
  manually when updated.

## Update and monitoring procedure

1. Change the AAR version only through a reviewed dependency update and regenerate the lockfile
   and verification metadata.
2. Re-hash bundled assets and update `native-binaries.json`.
3. Review upstream release notes and security advisories for GeckoView, youtubedl-android,
   FFmpeg, and uBlock Origin.
4. Run the full JVM suite, lint, release compilation, and the API 36 provider tests before
   shipping a native dependency change.
5. Do not replace a functioning native binary solely to satisfy an inventory check.
