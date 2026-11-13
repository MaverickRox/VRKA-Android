# Signing Key Custody and Rotation Policy

## Verified architecture

| Item | Current v5.1.5 state |
|---|---|
| Android APK signing | Production `release` build requires explicit signing properties; RSA 4096-bit certificate SHA-256 `9BEFDBF4FB00ACEDB72F866CE4016944C95EA99448E205768383B310CA11E1FA`; APK Signature Scheme v2 |
| Updater trust anchor | Pinned OpenPGP public key in `app/src/main/res/raw/vrka_release_pubkey.asc`; fingerprint `71165A658B3A8612AC2568C6D519380BEBA1B9F7`; RSA 4096-bit (self-asserted User ID `<security@vrka.org>`; domain/mailbox ownership requires external maintainer verification) |
| Manifest authentication | Detached OpenPGP signature over a versioned release manifest; SHA-2 digest allowlist, RSA-only key, hashed issuer fingerprint matching the pinned key |
| Update authorization | Signed manifest binds version, version code, release tag, APK size, APK SHA-256, signing-certificate SHA-256, expiration, and metadata revision; anti-rollback and anti-freeze checks run before download |
| APK verification | Size, SHA-256, package/version, certificate, and signing scheme are checked before installation |

The private APK keystore and private OpenPGP key are **not present in this repository** and were
not accessed during this maintenance task. Their exact storage location, offline status,
encryption, backup, and hardware protection are **unresolved** because no verifiable evidence was
available. Do not state that a key is offline, encrypted, backed up, or hardware-protected until
the maintainer records the evidence and the access procedure.

## Custody policy

1. Keep the APK keystore and OpenPGP private key outside the repository and outside CI.
2. Require two-person approval for a production build or manifest signature.
3. Store only public verification material and hashes in the repository.
4. Keep encrypted backups in separate controlled locations; record recovery drills without
   storing key material in the repository.
5. Do not expose signing properties, keystore files, passwords, or private keys to pull-request
   workflows. CI validation must not load production credentials.
6. Record every production build's source commit, version, certificate, APK hash, manifest hash,
   workflow run (if any), and signer fingerprint. A local build without a CI run must be labeled
   as such; see [RELEASE_PROVENANCE.md](RELEASE_PROVENANCE.md).

## OpenPGP trust-anchor rotation protocol

A pinned updater key cannot be changed by simply replacing the resource in a new APK: already
installed clients reject the new key. The safe transition is:

1. Generate a replacement OpenPGP key **K2** and publish its public key and fingerprint through a
   signed transition statement from the current key **K1**.
2. Prepare an application update that is still signed by K1 and ships both trust anchors. The
   update's manifest remains signed by K1 and includes the K2 fingerprint in a signed transition
   field.
3. Observe a defined overlap period in which K1 remains accepted for the current metadata
   revision, while K2 signatures are accepted only for the next revision.
4. Publish the first post-transition manifest signed by K2 (and optionally dual-signed by K1).
   Clients that have the transition release verify the K2 fingerprint against the signed
   transition, not against an unsolicited network key.
5. Revoke K1 through a signed revocation statement. Preserve the transition statement and old
   release evidence for audit.

No rotation is performed in this task. Existing v5.1.3–v5.1.5 clients have no dual-anchor support, so a
future rotation requires a **transition application release** signed by the old APK key. Users who
skip the transition release must install a full transition APK manually; the app cannot upgrade
across a changed APK signing identity without the Android signing lineage or a fresh install.

## Android APK signing-key rotation

- Prefer Android APK Signature Scheme v3 proof-of-rotation / signing-certificate lineage for
  compatible devices. It allows an existing installation to update to a new APK signing key.
- The lineage must be generated and retained offline with the old and new private keys.
- A new APK signing key without a valid lineage behaves as a different application to Android;
  affected users must uninstall/reinstall or use a separately distributed migration package,
  which may lose app data.
- Test rotations on an emulator with an existing installation before publishing. v5.1.3–v5.1.5 have not
  undergone a key rotation.

## Compromise and emergency response

1. Stop publishing new manifests and builds under the compromised key.
2. Publish a signed revocation/transition statement from a trusted surviving key where possible.
3. Issue an emergency release with the highest available version/metadata revision and a fresh
   security review.
4. If the APK signing key is compromised, use the signing lineage or a documented reinstall path;
   never silently publish an APK signed by a different key.
5. If the OpenPGP key is compromised, do not rely on the compromised key for the replacement
   anchor. Use a pre-established offline transition key or a manually distributed recovery
   release, and document the user recovery procedure.

## Verification and limitations

Existing verifier tests reject signatures from a different key, reject weak digest algorithms,
require the pinned issuer fingerprint, enforce expiration and rollback rules, and exercise
a hermetic `K1 -> K2` key-rotation transition fixture in `ReleaseManifestVerifierTest`.
Those checks verify the verifier state machine without production key material; they do not imply
that a production rotation occurred. No production key was rotated, no trust anchor was changed,
and no key material was accessed in this task. Historical acceptance documents may describe a
specific offline private-key path or permissions; those statements were not independently reverified
during this maintenance task and must not be treated as current custody evidence.
