# v5.1.4 Release Provenance

## Canonical v5.1.4 objects

| Object | Value |
|---|---|
| Release tag | `v5.1.4` |
| Annotated tag target | `6717da543df98fca33cdce78f8bc098edf6cef60` (direct `tag == source_commit`) |
| APK build source commit (`C1`) | `6717da543df98fca33cdce78f8bc098edf6cef60` |
| Release evidence commit (`C2`) | Parent is `6717da543df98fca33cdce78f8bc098edf6cef60`; records signed manifest, checksums, and evidence after `C1` CI pass and APK build |
| Source commit CI validation run | https://github.com/MaverickRox/VRKA-Android/actions/runs/42988201135 (`Unit tests, lint and development build` + `Real MediaStore and SAF provider tests (API 36)`) |
| Tag message | `VRKA Android v5.1.4 production release` |
| Version | `5.1.4` / `50104` |
| APK | `VRKA-Android-v5.1.4.apk` |
| APK size | `157431395` bytes |
| APK SHA-256 | `5535291ff60c89852f1b2b960379a3b2637576e833ab3951759522110bba5d97` |
| Manifest | `VRKA-Android-v5.1.4.manifest.json` (`metadata_revision` `7`) |
| Manifest SHA-256 | `3b8cbf09cd3e836fe655714ebde7486d727f4988cb98734ce045a7d8ceb5d18e` |
| Signature | `VRKA-Android-v5.1.4.manifest.json.asc` |
| Signature SHA-256 | `088c131eb2412736d4dabd6fab0a44bb3812a088a1b783c678d51bbdb6ea7a25` |
| Release public key asset | `VRKA-Release-Public-Key.asc`; SHA-256 `7f2a6ef42a4f66289e31c4fefff44b7ba294f9d0c696ace1f20602401641f38a` |
| Checksums | `SHA256SUMS`; SHA-256 `ce78a365f64c7b51c43e67c113666b0dc59f8a13d7b5de5b55594b30fa89089a` |
| Release R8 mapping backup (private) | `81,677,479` bytes; SHA-256 `1a745660c1bd6af6e99344ca53c5f5bbfd47080418c560fff48e29a411f7c38a` (archived off-repo, never uploaded) |

For `v5.1.4`, CI (`42988201135`) ran and passed on the exact source commit `6717da543df98fca33cdce78f8bc098edf6cef60` before the production APK was built cleanly from `6717da543df98fca33cdce78f8bc098edf6cef60`, and the annotated `v5.1.4` tag points directly at `6717da543df98fca33cdce78f8bc098edf6cef60`. No GitHub-hosted artifact provenance attestation exists for the locally signed production APK (`F:\vrka-release.p12` remains offline on the maintainer workstation).

---

# Historical: v5.1.3 Release Provenance

## Canonical objects

| Object | Value |
|---|---|
| Release tag | `v5.1.3` |
| Tag object type | annotated tag `7527e800e59bf6774b33011f3a23ca2049a41df1` |
| Peeled tag target | `daac0b5b7f14dc5ce6b2af0be8fcaf5119ebd2b8` |
| APK build source commit | `90de6b8e69b2b801eed4c838fe510fe91a29a80e` |
| Source commit parent | `2670ec83b7b43761afaf00b14b022e019d4d6eba` |
| Release metadata commit | `daac0b5b7f14dc5ce6b2af0be8fcaf5119ebd2b8` (direct child of the source commit) |
| Tag message | `VRKA Android v5.1.3 production release` |
| Version | `5.1.3` / `50103` |
| APK | `VRKA-Android-v5.1.3.apk` |
| APK size | `157428789` bytes |
| APK SHA-256 | `c9f58d3a97d728315b51fc92f43b5f54b88ae4ad9c25b3b22ea0cd8338549ddf` |
| Manifest | `VRKA-Android-v5.1.3.manifest.json` |
| Manifest SHA-256 | `3e5f8593731e401c236b74c1ce727e831c80c71d05c486ab92a04fb5597e528c` |
| Signature | `VRKA-Android-v5.1.3.manifest.json.asc` |
| Signature SHA-256 | `ee142c5970d827cfdb882e83b7878ae512799667ef087e5f4ee0ef7d984757a8` |
| Checksums | `SHA256SUMS`; SHA-256 `b8ae49f9b79261b447f8b9518ffede90a46e8a62c4b9294ce1573bbd916a9f7c` |

The GitHub release assets report the same APK, manifest, signature, and checksum hashes; the
release is not a draft or prerelease.

## Relationship between source and tag

Commit `90de6b8` contains the release preparation and version metadata. The APK was built from
that source revision, as recorded in the release evidence and signed manifest. Commit `daac0b5`
is a direct child that records release evidence, checksums, the manifest, the detached signature,
and release-facing documentation. The annotated tag points at `daac0b5`, not at the build source
commit. This is a metadata-after-build relationship, not evidence that the tag commit itself was
the APK source.

## Build/CI evidence

The repository's GitHub Actions history contains no workflow run whose `head_sha` is `90de6b8`.
The release evidence records a local production build with explicit signing properties; the
normal CI run associated with `daac0b5` is validation, not the APK build. Therefore:

- The exact source revision and artifact hashes are established from release records and the
  signed manifest.
- A CI-generated build provenance attestation for v5.1.3 **does not exist**. No such claim is made.
- The build was performed with production signing credentials outside this maintenance task; no
  production signing material was accessed here.

## Future release requirements

For future releases, generate a GitHub artifact attestation (or an equivalent signed provenance
record) covering at least:

- source repository and full source commit;
- workflow identity and workflow run URL;
- build environment (runner image, JDK, Android SDK/build tools);
- artifact filename and SHA-256;
- build timestamp;
- signing-certificate fingerprint and manifest revision/hash.

Attestation publication must not require exposing signing secrets to pull-request builds.
Production `app/build/outputs/mapping/release/mapping.txt` and its SHA-256 digest must be archived
in private maintainer storage indexed by `{versionName, versionCode, sourceCommit, apkSha256}`
for crash deobfuscation; mapping files must not be attached to a public release by default. CI
continuously checks and retains non-secret `devRelease` `mapping.txt` + `mapping.sha256` artifacts
for 7 days.

## Verification commands

```powershell
Get-FileHash .\VRKA-Android-v5.1.3.apk -Algorithm SHA256
Get-FileHash .\VRKA-Android-v5.1.3.manifest.json -Algorithm SHA256
Get-FileHash .\VRKA-Android-v5.1.3.manifest.json.asc -Algorithm SHA256
```

Do not modify or replace the published v5.1.3 assets, signatures, checksums, or tag to add
provenance retroactively.
