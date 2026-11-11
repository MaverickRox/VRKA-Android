# CI/CD and Supply-Chain Security

## Workflow boundaries

- `.github/workflows/ci.yml` runs read-only validation on pushes and pull requests.
- The top-level permission is `contents: read`; the dependency-review job adds only
  `pull-requests: read`. Workflows do not receive write tokens or signing secrets.
- Production signing is not performed in CI. The `release` task family requires explicit
  signing properties and is never invoked by pull-request workflows.
- Actions are pinned to full commit SHAs. Dependabot updates the pins through reviewable pull
  requests; comments retain the human-readable major version.
- Concurrency cancellation prevents duplicate runs on the same ref.
- Test and provider reports are uploaded only on failure, with a seven-day retention period.

## Validation gates

The validation job runs, in order:

1. `testDebugUnitTest`
2. `lintDebug`
3. `lintRelease` (release lint must fail the job on errors)
4. `compileDebugAndroidTestKotlin`
5. `assembleDebug`, `assembleDebugAndroidTest`, `assembleDevRelease`
6. A check that dependency lockfiles were not rewritten

Gradle uses the committed lockfile and `gradle/verification-metadata.xml`; missing or mismatched
artifacts fail dependency resolution. Provider instrumentation runs in a separate API 36 emulator
job.

## Mapping files and retained build data

Production release builds are performed outside normal CI with signing credentials. If R8 mapping
files are retained, keep them in private storage associated with the exact source commit and
version; do not upload them as public release assets. They are not required to build the app and
must never contain signing secrets. v5.1.3 has no published mapping file.

## Improvement boundaries

- CI does not rebuild the published APK and does not verify a reproducible native build.
- No GitHub artifact attestation exists for v5.1.3; future releases should generate one as
  described in [RELEASE_PROVENANCE.md](RELEASE_PROVENANCE.md).
- Dependency review and Dependabot reduce supply-chain risk but do not replace review of
  repository ownership, native artifact provenance, or signing-key custody.
