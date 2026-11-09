# VRKA Phase 0 Legacy Fixture Corpus

Generated outside the production repository. Do not ship this directory.

## Source profiles

| Profile | Commit | Format reality | Cases | Files | Missing-file cases |
|---|---|---|---|---|---|
| v5.1.0 | `2e5b8228b581af606a7a6c40758c2aaaef0914c9` | JobStore only; native bare JSON array; no journal; no cleanup store | 10 | 9 | 1 |
| v5.1.1 | `ef656e5c4e3a145847faa52186b6aa21625447a4` | JobStore schema 2 object; no journal; no cleanup store; `publishStarted` exists | 10 | 9 | 1 |
| v5.1.2 | `af5c1b90d0ad9e18582481460a008aa3daf2c354` | JobStore schema 2; journal schema 1 (lenient; no attempt/batch/cleanup status); no cleanup store | 45 | 43 | 2 |
| baseline-8b8591d | `8b8591d7d8bb6cd25a63397f0998785fc78c67b1` | Unreleased baseline: strict journal with `CLEANUP_PENDING`, transaction-scoped reconciliation, StagingCleanupStore schema 1 | 15 | 14 | 1 |

**Totals:** 80 cases, 75 fixture files, 5 missing-file cases. SHA-256 of every file is recorded and validated in `CORPUS_MANIFEST.json`.

## Generation method

- JobStore fixtures were produced by invoking the actual tagged/current `JobStore.save()` encoder; derived formats are explicitly labeled (schemaVersion change, array extraction, truncation, duplication, unsupported version, empty).
- v5.1.2 journal fixtures were produced by invoking the actual tagged `PublicationJournal` API (`recordIntent`, `recordBatchIntents`, `updateProviderCreated`, `updateDataCopied`, `updateValidated`, `updateFinalized`, `updateRecorded`, `markFailed`, `markInterrupted`, `markReconciled`). Derived/corrupt records are labeled.
- Baseline journal/cleanup fixtures were produced by invoking the current baseline `PublicationJournal` and `StagingCleanupStore` APIs.
- Every manifest entry records source tag, source commit, encoder, description, derivation, bytes, and SHA-256.
- Source snapshots used for generation are in `../src-v5.1.0`, `../src-v5.1.1`, `../src-v5.1.2`, and `../src-baseline`; these are isolated `git archive` extracts, not modified production trees.
- Generator tests are retained in `generators/` for reproducibility. They are test-only and were run in the isolated snapshots with environment variables `VRKA_PHASE0_FIXTURES`, `VRKA_PHASE0_TAG`, and `VRKA_PHASE0_COMMIT`.

## Important limits

- The corpus is a set of alternative migration scenarios, not one device's data. Importing every file into one prototype DB intentionally creates repeated job identities across scenarios.
- v5.1.2 normally deleted staging files in `publishAll()`. The `journal-missing-staging-finalized` fixture represents that normal absence; no staged hash exists for legacy records.
- v5.1.2 did not have `StagingCleanupStore`; the baseline cleanup fixture is for the unreleased tree only.
- v5.1.0/v5.1.1 had no publication journal. A `publishStarted` in-flight job from those versions cannot be transactionally recovered from a journal.
- A legacy reconciled entry is a historical decision, not proof that a provider object exists. The fixture corpus includes `RECONCILED + INTENT_RECORDED` and reconciled earlier-phase records.
- `journal-synthesized-defaults` demonstrates why the v5.1.2 lenient decoder must not be used as the migration contract: it invents job/phase/status/timestamp values for a corrupted record.
