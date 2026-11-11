# CI/CD and Supply-Chain Security

## Workflow boundaries

- `.github/workflows/ci.yml` runs read-only validation on pushes and pull requests.
- The top-level permission is `contents: read`; the dependency-review job adds only
  `pull-requests: read`. Workflows do not receive write tokens or signing secrets.
- Production signing is not performed in CI. The `release` task family requires explicit
  signing properties and is never invoked by pull-request workflows.
- Actions are pinned to full commit SHAs (updated manually on `main` when reviewed; comments retain
  the human-readable major version). Automatic Dependabot PR creation is disabled to preserve a
  single permanent `main` development branch, while Dependabot vulnerability alerts remain enabled.
- Concurrency cancellation prevents duplicate runs on the same ref.
- Test, lint, and provider reports are uploaded on failure, with a seven-day retention period.
- `devRelease` R8 mapping output (`mapping.txt` and `mapping.sha256`) is verified non-empty and
  retained as a workflow artifact for seven days; `devRelease` uses the debug keystore and
  contains no production signing credentials.

## Validation gates

The validation job runs, in order:

1. `testDebugUnitTest`
2. `lintDebug`
3. `lintRelease` (release lint must fail the job on errors)
4. `compileDebugAndroidTestKotlin`
5. `assembleDebug`, `assembleDebugAndroidTest`, `assembleDevRelease`
6. Verification that `app/build/outputs/mapping/devRelease/mapping.txt` is non-empty and hashed
7. A check that dependency lockfiles were not rewritten

Gradle uses the committed lockfile and `gradle/verification-metadata.xml`; missing or mismatched
artifacts fail dependency resolution. Provider instrumentation runs in a separate API 36 emulator
job.

## Mapping files and retained build data

CI validates R8 shrinking and mapping generation continuously on `devRelease` (`isMinifyEnabled =
true`, `isShrinkResources = true` inherited from `release`) and retains `mapping.txt` +
`mapping.sha256` for 7 days.

Production `release` builds are performed outside normal CI with explicit signing credentials and
are never triggered by pull requests. For each production release build, archive the generated
`app/build/outputs/mapping/release/mapping.txt` alongside `mapping.sha256` in access-controlled
private release storage indexed by `{versionName, versionCode, sourceCommit, apkSha256}`:

```powershell
Get-FileHash .\app\build\outputs\mapping\release\mapping.txt -Algorithm SHA256
# Archive mapping.txt + mapping.sha256 + build metadata JSON in private custody alongside release notes;
# never attach mapping.txt to public GitHub release assets or release tags.
```

Mapping files must never contain signing credentials (verified against `devRelease` outputs) and are
retained solely for private crash-stack deobfuscation. v5.1.3 has no published mapping file.

## Improvement boundaries

- CI does not rebuild the published APK and does not verify a reproducible native build.
- No GitHub artifact attestation exists for v5.1.3; future releases should generate one as
  described in [RELEASE_PROVENANCE.md](RELEASE_PROVENANCE.md).
- Dependency review and Dependabot vulnerability alerts reduce supply-chain risk but do not replace
  review of repository ownership, native artifact provenance, or signing-key custody.
