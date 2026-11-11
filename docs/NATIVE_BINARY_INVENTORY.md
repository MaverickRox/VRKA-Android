# Native Binary Provenance Inventory

The machine-readable inventory is [native-binaries.json](native-binaries.json). SHA-256 values
for Maven artifacts come from `gradle/verification-metadata.xml`; hashes for bundled files are
computed from the checked-in assets.

## Confirmed inventory

| Component | Delivery | Version | Origin | Verification |
|---|---|---|---|---|
| Mozilla GeckoView | prebuilt AAR | 159.0.20261022150525 | Mozilla Maven | SHA-256 in verification metadata |
| youtubedl-android library | prebuilt AAR | 0.18.1 | Maven Central | SHA-256 in verification metadata |
| youtubedl-android FFmpeg | prebuilt AAR | 0.18.1 | Maven Central | SHA-256 in verification metadata |
| uBlock Origin | signed Firefox XPI asset | pinned bundled XPI | upstream uBlock release | SHA-256 of checked-in asset |
| VRKA Media Detector | first-party JS extension | repository source | this repository | source review; asset hashes |
| Release public key | ASC resource | pinned v5.1.3 trust anchor | this repository | SHA-256 and fingerprint checks |

## What is not claimed

- These AARs are downloaded prebuilt artifacts, not built reproducibly in this repository.
- A matching checksum proves artifact integrity relative to the committed metadata; it does not
  prove the upstream project is vulnerability-free.
- The bundled yt-dlp runtime inside the `youtubedl-android` AAR is not independently rebuilt by
  VRKA. The official component updater can replace it with a signed release artifact.
- No full native SBOM or binary-to-source reproducibility attestation is published for v5.1.3.
- CI detects changes to checked-in assets and dependency versions; it does not rebuild or
  byte-compare upstream native binaries.
- uBlock Origin is not covered by Gradle dependency monitoring; its release must be reviewed
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
