# VRKA Storage Architecture (v5.1.3)

**Status:** shipped in the official v5.1.3 release (`90de6b8e69b2b801eed4c838fe510fe91a29a80e`).
The v5.1.2 APK and its JSON storage architecture remain historical release evidence and are not
modified by this document.

## Authority and cache

`VrkaDatabase` (Room 2.8.5 / SQLite) is the sole durable authority for jobs, attempts,
publication batches and outputs, cleanup obligations, and migration records. Media bytes remain
in per-attempt staging files and Android storage providers; no media blob is stored in SQLite.

`RoomStorageRepository` owns database writes and publishes committed rows into a `StateFlow`
cache for the existing manager, UI, service, and notification consumers. Database mutations
commit first; the cache is refreshed only after a successful commit. The cache is never
authoritative and an uninitialized cache never proves that no jobs exist.

## Tables and identities

| Table | Purpose |
|---|---|
| `jobs` | Every persisted `DownloadJob` field, request JSON, state, progress/byte accounting, ordered output references, attempt, terminal metadata, monotonic version. |
| `attempts` | One row per actual retry, keyed by a globally unique `attempt_id`, retaining the logical `job_id`. |
| `publication_batches` | Immutable batch identity, owning job/attempt, expected output count, provider/destination, batch status. |
| `publication_outputs` | One row per output transaction: transaction ID, attempt/batch/index identity, pending/final names, provider URI/document ID, staging key, expected size and SHA-256, observed SHA-256, verification level, phase, cleanup state, timestamps, version. |
| `cleanup_tasks` | Durable staging and provider-object cleanup obligations with owning IDs, retry count, next retry, dead-letter state. |
| `migration_ledger` / `migration_records` | Source hashes, detected profile, import status, and raw preserved legacy records. |

IDs are generated before provider side effects. Wall-clock timestamps are diagnostic only; they
are never used for ownership. A retry never shares an attempt ID with an earlier execution, and
a playlist retry with a different output count creates a new batch.

## Startup gate

`StorageRuntime` exposes `INITIALIZING → MIGRATING → READY`, or `MIGRATION_FAILED` /
`STORAGE_FAILED`. Initialization runs on IO. It performs the legacy import, reloads the cache,
reopens the database with a fresh Room instance, and runs integrity checks before publishing
`READY`. `VrkaApplication`, `VrkaDownloadManager`, `DownloadService`,
`DownloadTransferJobService`, the Compose root, and enqueue admission all consult this gate.
Enqueue is rejected while storage is not ready or when the durable write fails.

## Publication state machine

Each output transaction moves monotonically through:

```
STAGING → READY → PREPARED → INTENT_RECORDED → CREATE_REQUESTED → PROVIDER_OBJECT_CREATED
→ COPYING → DATA_COPIED → OUTPUT_VALIDATED → FINALIZE_REQUESTED → FINALIZED → RECORDED
```

Failure and recovery states are `CLEANUP_PENDING`, `RECOVERY_REQUIRED`, `PERMISSION_REQUIRED`,
and `ABORTED`. Provider intent is persisted before the corresponding provider call. External
provider work never runs inside a database transaction.

The terminal commit is one Room transaction. It verifies the job's current attempt, the batch
identity, exact ordered output indices and count, provider URIs, and an acceptable verification
level (`HASH_VERIFIED` or `SIZE_VERIFIED`) for every output. It writes the DONE job, marks the
exact transactions `RECONCILED` / `COMMITTED_RECONCILED`, marks the batch committed, and inserts
the staging cleanup task. A fresh DAO read verifies the terminal projection afterward. Stale
callbacks fail the attempt/version comparison and never mark a different retry DONE.

## Recovery ownership

Startup recovery never selects transactions by "newest output index" alone. It selects exactly
one non-ambiguous batch whose entries all share the logical job, current attempt number and
attempt ID, batch ID, output count, provider type, and destination. The selected batch must own
a unique index set within the declared range; a complete job also requires the exact ordered
`0 until totalOutputs` set. A job with entries from different attempts or different batches is
never completed, and its provider objects are preserved.

Transactions from a previous attempt or a different batch are retained as unresolved evidence.
Recovery does not adopt, finalize, reconcile, or delete them. A completed job is committed through
the same atomic Room terminal transaction used by the normal publisher, so a recovery path cannot
bypass the terminal ownership checks.

Legacy journal rows are imported with a synthetic storage key but marked `legacy_ambiguous` when
the source has no valid attempt/batch identity. Ambiguous batches are excluded from recovery
completion, provider adoption/finalization, reconciliation, and deletion.

## Corrupt persisted rows and partial readiness

Undecodable `jobs` rows are never dropped from the cache or Paging. The repository writes a
durable quarantine record containing the raw persisted fields, then surfaces a safe damaged
History placeholder with `recoveryRequired = true`. The placeholder cannot be retried, never
contains source URLs or credentials, and cannot overwrite the raw row through a cache save.
`StorageRuntime` reports `READY` with `quarantinedJobs > 0` and `partialReadiness = true`; the UI
shows a storage-recovery notice. Unknown enum values in jobs or publication rows fail decoding
and are quarantined rather than mapped to active defaults.

## Cleanup obligations, deletion and dead letters

Staging and provider cleanup tasks are independent durable rows. Deleting a History record removes
its job and settled publication rows but never deletes a staging cleanup task; the task is retried
after restart. A provider cleanup obligation blocks History deletion (and `clearJob`) until the
exact provider object is confirmed removed or definitely absent; the block is reported in
Diagnostics. `clearJob` and `clearAll` cannot bypass unresolved outputs or cleanup tasks.

Tasks that exhaust their retry budget become dead letters. Dead letters remain queryable, are
reported in Diagnostics, and are skipped by automatic retry. The Diagnostics screen lists them and
provides a manual, task-scoped retry that preserves the retry count and last error. Successful
cleanup clears the obligation only after provider deletion or definite absence is confirmed.

## MediaStore protocol

On API 29+ the publisher inserts a unique pending row, persists its URI before copying bytes,
reads the stored bytes back, compares size and SHA-256 with staging, persists finalization
intent, clears `IS_PENDING`, and re-queries the exact row. `IS_PENDING = 0`, the final display
name, row identity, and matching content are required before `PUBLISHED`. A missing pending
column on a supported API is `UNKNOWN`, never success. API 26–28 use the same unique names with
legacy semantics and no pending-column assumption.

## SAF protocol

SAF outputs use a transaction-marked final name (`<title> [vrka-<token>].<ext>`). Before creation
the publisher records the parent tree, pending name, final marked name, expected size/hash, and
the detected capability snapshot. The provider must support child enumeration and reliable
document identity before any side effect; otherwise the destination is rejected with an
explanation. After copy and readback, the document is renamed and the returned URI is persisted
immediately.

If the process dies after `renameDocument()` and before the new URI is persisted, startup
recovery searches the exact destination tree for exactly one document carrying the transaction
marker. It verifies parent, name, and content, adopts the replacement URI, and continues. Query
errors, revoked permission, duplicate candidates, and content mismatch leave the transaction
unresolved without deleting a candidate. The marker is intentionally not removed from the
actual SAF filename because it is the recovery identity; the user-facing title is stored
separately in the database.

## Retention, paging, admission

Terminal History is retained until explicit user deletion and loaded through Paging 3 pages
(30 rows per page) rather than held entirely in UI memory. There is no automatic 250-record
history eviction. Deletion checks unresolved publication transactions before removing a job.
Enqueue rejects invalid URLs, a full active queue (1,000), insufficient free space, storage that
is not ready, or a durable write that cannot be confirmed; a rejected job never remains
displayed as accepted.

## Verification

The JVM suite covers full-field round trips, atomic terminal commits, stale-attempt rejection,
unverified-output rejection, unresolved-transaction deletion protection, paging, admission,
migration idempotency, and the real publication orchestration path. The API 36 instrumentation
suite covers real MediaStore finalization/recovery and a real `ContentResolver` SAF provider
with in-place rename, replacement-ID rename, null/unsupported/throwing rename, modified marker,
duplicate candidates, child-query failure, readback failure, delete retry, permission
revocation, and interruption recovery. See `TEST_RESULTS.md` and `DEVICE_VALIDATION.md`.
