# Legacy Storage Migration (Unreleased `main`)

## Scope

Existing installations of v5.1.0, v5.1.1, v5.1.2, and the unreleased JSON baseline are imported
into the Room database on first launch after upgrading. This applies only to unreleased `main`;
the published v5.1.2 APK is unchanged.

The importer is in `LegacyStorageMigrator`. Original JSON files and bounded corrupt recovery
copies are never deleted or rewritten. After import, the JSON decoders remain migration-only
code; they are not a competing durable authority.

## Detected profiles

| Profile | JobStore | Publication journal | Cleanup store | Evidence characteristics |
|---|---|---|---|---|
| v5.1.0 | Native bare JSON array, older field set, FLAC→OPUS loader mapping | Absent | Absent | No attempt/batch IDs, no hashes. |
| v5.1.1 | JobStore schema 2, `publishStarted` and newer fields | Absent | Absent | In-flight publishing cannot be reconstructed transactionally. |
| v5.1.2 | JobStore schema 2 | Schema 1 | Absent | `RECONCILED` is a job-scoped tombstone, not proof of publication; `FINALIZED` is not independently verified; no attempt/batch ID or SHA-256; staging often absent. |
| unreleased baseline | JobStore schema 2 | Strict journal | Schema 1 | `CLEANUP_PENDING` and newer states may exist. |

Detection uses the actual files and structure, not the application version. A journal without a
cleanup store selects the v5.1.2 profile; a cleanup store selects the unreleased baseline; a bare
array selects v5.1.0; a JSON object without a journal selects v5.1.1.

## Import sequence

1. Detect the source files and profile.
2. Verify free space and preserve bounded corrupt recovery copies.
3. Snapshot each source and compute its SHA-256.
4. Write the migration ledger entry with source hash and `IMPORTING` status.
5. Import jobs in bounded chunks, using deterministic IDs and transactional progress.
6. Import journal transactions with deterministic transaction/attempt/batch identities.
7. Import cleanup obligations and preserve ambiguous or malformed records as raw
   `migration_records`.
8. Run `PRAGMA integrity_check` and `PRAGMA foreign_key_check`.
9. Reopen the database with a fresh Room instance and verify it is readable.
10. Publish `READY`.

Repeated imports are idempotent: the ledger detects completed sources by hash, and deterministic
IDs prevent duplicate jobs, attempts, batches, outputs, or cleanup tasks. An interruption at any
checkpoint resumes from the ledger and current rows.

## Evidence levels and ambiguous batches

| Level | Meaning |
|---|---|
| `LEGACY_DONE_NO_HASH` | A legacy History record marked DONE. It is not demoted merely because an old provider URI is inaccessible. |
| `LEGACY_TERMINAL` | A terminal legacy record other than DONE. |
| `LEGACY_RECONCILED_TOMBSTONE` | A v5.1.2 `RECONCILED` journal entry. It does not prove that an output exists. |
| `LEGACY_UNVERIFIED` | A legacy journal record without independent content evidence. |
| `LEGACY_ACTIVE` | A legacy non-publishing active job. |
| `RECOVERY_REQUIRED` | A legacy in-flight publishing job with no journal (v5.1.0/v5.1.1) that cannot be transactionally reconstructed. |
| `LEGACY_AMBIGUOUS` | Preserved records whose identity or batch membership cannot be established safely. |

The v5.1.2 journal contains no attempt or batch identity. Every imported row is retained as raw
migration evidence and its synthetic storage batch is marked `legacy_ambiguous`. A synthetic
attempt/batch is never treated as proof of retry ownership: recovery cannot complete a job, adopt
or finalize a provider object, reconcile a row, or delete an object from an ambiguous batch.
Unknown phase, status, or provider values are preserved as raw records instead of being mapped to
`INTENT_RECORDED`, `PENDING`, or `MEDIA_STORE`.

If a source carries attempt/batch fields, they remain in the preserved raw row but are not used
as storage authority; storage uses deterministic synthetic keys and marks the batch ambiguous.
No legacy journal source can authorize completion, adoption, finalization, reconciliation, or
deletion.

No hashes are invented. A missing v5.1.2 staging file is expected in many legitimate cases and
is not treated as corruption. Plain-name SAF documents whose old URI is invalid are never
adopted or deleted automatically.

## Fixture corpus

`app/src/test/resources/phase0-fixtures` contains the Phase 0 corpus generated from the actual
tagged encoders: 80 cases and 75 files across v5.1.0 (10 cases), v5.1.1 (10), v5.1.2 (45), and
the unreleased baseline (15). `CORPUS_MANIFEST.json` records each case's source commit, encoder,
byte length, and SHA-256. The corpus and generator sources are committed so CI does not depend on
the external Phase 0 scratch directory.

`LegacyMigrationFixtureTest` verifies every manifest hash, migrates every fixture, checks that
original files are unchanged, runs integrity/foreign-key checks, and repeats the import to prove
idempotency. Independent verification against the Phase 0 scratch corpus confirmed all 75 file
hashes matched both the manifest and the scratch copies.

## Startup failure behavior

If migration fails, `StorageRuntime` reports `MIGRATION_FAILED`; enqueue and publication entry
points reject new work with a recoverable error. The application never silently starts with an
empty database when legacy data exists and never falls back to modifying legacy JSON. A fresh
install with genuinely absent legacy files initializes an empty database normally.
