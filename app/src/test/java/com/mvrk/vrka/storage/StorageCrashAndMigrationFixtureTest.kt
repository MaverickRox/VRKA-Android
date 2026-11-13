package com.mvrk.vrka.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.AppSettings
import com.mvrk.vrka.DownloadJob
import com.mvrk.vrka.DownloadRequest
import com.mvrk.vrka.JobState
import com.mvrk.vrka.OutputPublisher
import com.mvrk.vrka.PublicationEntry
import com.mvrk.vrka.PublicationJournalRecoveryTest
import com.mvrk.vrka.PublicationPhase
import com.mvrk.vrka.PublicationProviderType
import com.mvrk.vrka.PublicationTransactionStatus
import com.mvrk.vrka.PublicationUriRules
import com.mvrk.vrka.SaveLocationMode
import com.mvrk.vrka.SettingsRepository
import com.mvrk.vrka.VrkaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Phase 1 baseline schema & crash-recovery fixture suite:
 * - Builds an authentic v5.1.4 SQLite database matching schemas/VrkaDatabase/1.json (user_version=1)
 * - Enumerates all 23 existing PublicationPhase enum values for Phase 3 mapping parity
 * - Reproduces SAF W4 renameDocument crash where an identical duplicate user file exists in the tree
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageCrashAndMigrationFixtureTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var dbName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "v1-fixture-${System.nanoTime()}.db"
        testDir = File(context.cacheDir, "crash-fix-${System.nanoTime()}").apply { mkdirs() }
        deleteDb()
    }

    @After
    fun tearDown() {
        deleteDb()
        testDir.deleteRecursively()
    }

    private fun deleteDb(name: String = dbName) {
        val base = context.getDatabasePath(name)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun createV514Version1Database(name: String = dbName) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `jobs` (`id` TEXT NOT NULL, `request_json` TEXT NOT NULL, `state` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT NOT NULL, `progress` REAL NOT NULL, `speed` TEXT NOT NULL, `eta_seconds` INTEGER, `downloaded_bytes` INTEGER, `estimated_total_bytes` INTEGER, `estimated_size_accuracy` TEXT NOT NULL, `reported_total_bytes` INTEGER, `reported_total_is_exact` INTEGER NOT NULL, `active_stream` TEXT, `playlist_item_index` INTEGER, `playlist_item_count` INTEGER, `actual_final_file_bytes` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `output_uris_json` TEXT NOT NULL, `error` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `actual_resolution` TEXT, `runtime_name` TEXT NOT NULL, `runtime_version` TEXT NOT NULL, `subtitle_status` TEXT NOT NULL, `publish_started` INTEGER NOT NULL, `current_attempt_id` TEXT NOT NULL, `legacy_verification` TEXT NOT NULL, `recovery_required` INTEGER NOT NULL, `terminal_at` INTEGER, `commit_sequence` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_state` ON `jobs` (`state`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_updated_at` ON `jobs` (`updated_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_recovery_required` ON `jobs` (`recovery_required`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `attempts` (`attempt_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_number` INTEGER NOT NULL, `request_json` TEXT NOT NULL, `destination_tree_uri` TEXT, `status` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `legacy_trust` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`attempt_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_attempts_job_id` ON `attempts` (`job_id`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attempts_job_id_attempt_number` ON `attempts` (`job_id`, `attempt_number`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `publication_batches` (`batch_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_id` TEXT NOT NULL, `total_outputs` INTEGER NOT NULL, `provider_type` TEXT NOT NULL, `destination_identity` TEXT NOT NULL, `status` TEXT NOT NULL, `legacy_ambiguous` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`batch_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`attempt_id`) REFERENCES `attempts`(`attempt_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_batches_job_id` ON `publication_batches` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_batches_attempt_id` ON `publication_batches` (`attempt_id`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `publication_outputs` (`transaction_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_id` TEXT NOT NULL, `attempt_number` INTEGER NOT NULL, `batch_id` TEXT NOT NULL, `output_index` INTEGER NOT NULL, `total_outputs` INTEGER NOT NULL, `provider_type` TEXT NOT NULL, `destination_identity` TEXT NOT NULL, `pending_filename` TEXT NOT NULL, `final_filename` TEXT NOT NULL, `provider_uri` TEXT, `provider_document_id` TEXT, `staging_key` TEXT, `expected_size_bytes` INTEGER NOT NULL, `expected_sha256` TEXT, `observed_sha256` TEXT, `copied_bytes` INTEGER NOT NULL, `verification_level` TEXT NOT NULL, `provider_capabilities_json` TEXT, `phase` TEXT NOT NULL, `status` TEXT NOT NULL, `cleanup_state` TEXT NOT NULL, `error_message` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`transaction_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`attempt_id`) REFERENCES `attempts`(`attempt_id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`batch_id`) REFERENCES `publication_batches`(`batch_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_job_id` ON `publication_outputs` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_attempt_id` ON `publication_outputs` (`attempt_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_batch_id` ON `publication_outputs` (`batch_id`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_publication_outputs_batch_id_output_index` ON `publication_outputs` (`batch_id`, `output_index`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_provider_uri` ON `publication_outputs` (`provider_uri`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_status` ON `publication_outputs` (`status`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `cleanup_tasks` (`id` TEXT NOT NULL, `job_id` TEXT, `attempt_id` TEXT, `transaction_id` TEXT, `resource_type` TEXT NOT NULL, `resource_identity` TEXT NOT NULL, `stage` TEXT NOT NULL, `retry_count` INTEGER NOT NULL, `next_retry_at` INTEGER NOT NULL, `last_error_class` TEXT, `last_error` TEXT, `dead_letter` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_job_id` ON `cleanup_tasks` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_next_retry_at` ON `cleanup_tasks` (`next_retry_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_dead_letter` ON `cleanup_tasks` (`dead_letter`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cleanup_tasks_resource_type_resource_identity` ON `cleanup_tasks` (`resource_type`, `resource_identity`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `migration_ledger` (`source_key` TEXT NOT NULL, `source_tag` TEXT NOT NULL, `sha256` TEXT NOT NULL, `status` TEXT NOT NULL, `total_records` INTEGER NOT NULL, `imported_records` INTEGER NOT NULL, `detail` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`source_key`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `migration_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_key` TEXT NOT NULL, `source_row` INTEGER NOT NULL, `reason` TEXT NOT NULL, `raw_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_migration_records_source_key` ON `migration_records` (`source_key`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_migration_records_source_key_source_row_reason` ON `migration_records` (`source_key`, `source_row`, `reason`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            val db = helper.writableDatabase
            val reqJson = JobJsonCodec.encodeRequest(DownloadRequest(url = "https://example.com/watch?v=v514-seed"))
            db.execSQL(
                """
                INSERT INTO jobs VALUES (
                    'v514-job-1', ?, 'DONE', 'Legacy v5.1.4 Clip', 'Saved to Downloads/VRKA',
                    100.0, '', NULL, 2048, 2048, 'EXACT', 2048, 1, NULL, NULL, NULL, 2048,
                    1000, 2000, '["content://media/external/downloads/55"]', '', 1, '1080p',
                    'yt-dlp', '2026.08.19', 'NONE', 1, 'v514-job-1:attempt:1', '', 0, 2000, 1, 1
                )
                """.trimIndent(),
                arrayOf(reqJson),
            )
            db.execSQL(
                """
                INSERT INTO attempts VALUES (
                    'v514-job-1:attempt:1', 'v514-job-1', 1, ?, NULL, 'DONE', 1000, 2000, '', 1
                )
                """.trimIndent(),
                arrayOf(reqJson),
            )
            db.execSQL(
                "INSERT INTO publication_batches VALUES ('batch-1', 'v514-job-1', 'v514-job-1:attempt:1', 1, 'MEDIA_STORE', 'Downloads/VRKA', 'COMMITTED', 0, 1000, 2000)",
            )
            db.execSQL(
                """
                INSERT INTO publication_outputs VALUES (
                    'tx-1', 'v514-job-1', 'v514-job-1:attempt:1', 1, 'batch-1', 0, 1, 'MEDIA_STORE',
                    'Downloads/VRKA', '.vrka_pending_tx1_Legacy.mp4', 'Legacy.mp4',
                    'content://media/external/downloads/55', '55', '/tmp/staged.mp4', 2048,
                    'abc', 'abc', 2048, 'HASH_VERIFIED', NULL, 'COMMITTED_RECONCILED', 'RECONCILED',
                    'DONE', NULL, 1000, 2000, 1
                )
                """.trimIndent(),
            )
        }
    }

    @Test
    fun v514DatabaseFixture_opensInRoomAndPreservesAllTables() = runBlocking {
        createV514Version1Database()
        val database = Room.databaseBuilder(context, VrkaDatabase::class.java, dbName)
            .addMigrations(VrkaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = RoomStorageRepository(database)
            val report = repo.loadCache()
            assertEquals(1, report.decodedJobs)
            assertEquals(0, report.quarantinedJobs)
            val loaded = repo.jobById("v514-job-1")
            assertNotNull(loaded)
            assertEquals(JobState.DONE, loaded!!.state)
            assertEquals("Legacy v5.1.4 Clip", loaded.title)
            assertNull(loaded.playlistGroupId)
            val pubEntries = repo.publicationStore.entriesForJob("v514-job-1")
            assertEquals(1, pubEntries.size)
            assertEquals(PublicationPhase.FINALIZED, pubEntries.single().publicationPhase)

            assertEquals(emptyList<JobEntity>(), database.jobDao().forPlaylistGroup("grp-1"))
            val sqliteDb = database.openHelper.readableDatabase
            assertEquals(2, sqliteDb.version)
            val colCursor = sqliteDb.query("PRAGMA table_info('jobs')")
            var hasPlaylistGroupColumn = false
            while (colCursor.moveToNext()) {
                if (colCursor.getString(colCursor.getColumnIndexOrThrow("name")) == "playlist_group_id") {
                    hasPlaylistGroupColumn = true
                }
            }
            colCursor.close()
            assertTrue("MIGRATION_1_2 must add playlist_group_id to jobs", hasPlaylistGroupColumn)

            val idxCursor = sqliteDb.query("PRAGMA index_list('jobs')")
            var hasPlaylistGroupIndex = false
            while (idxCursor.moveToNext()) {
                if (idxCursor.getString(idxCursor.getColumnIndexOrThrow("name")) == "index_jobs_playlist_group_id") {
                    hasPlaylistGroupIndex = true
                }
            }
            idxCursor.close()
            assertTrue("MIGRATION_1_2 must create index_jobs_playlist_group_id", hasPlaylistGroupIndex)
        } finally {
            database.close()
        }
    }

    @Test
    fun all23LegacyPublicationPhases_areEnumeratedAndPersistRoundTrip() = runBlocking {
        assertEquals(6, PublicationPhase.entries.size)
        val expectedLegacyMappings = listOf(
            "STAGING" to PublicationPhase.INTENT,
            "READY" to PublicationPhase.INTENT,
            "PREPARED" to PublicationPhase.INTENT,
            "INTENT_RECORDED" to PublicationPhase.INTENT,
            "CREATE_REQUESTED" to PublicationPhase.INTENT,
            "PROVIDER_OBJECT_CREATED" to PublicationPhase.CREATED,
            "COPYING" to PublicationPhase.CREATED,
            "DATA_COPIED" to PublicationPhase.COPIED,
            "OUTPUT_VALIDATED" to PublicationPhase.COPIED,
            "VERIFIED" to PublicationPhase.COPIED,
            "FINALIZE_REQUESTED" to PublicationPhase.COPIED,
            "FINALIZED" to PublicationPhase.FINALIZED,
            "PUBLISHED" to PublicationPhase.FINALIZED,
            "RECORDED" to PublicationPhase.FINALIZED,
            "COMMITTED_RECONCILED" to PublicationPhase.FINALIZED,
            "CLOSED" to PublicationPhase.FINALIZED,
            "STAGING_CLEANUP_PENDING" to PublicationPhase.FINALIZED,
            "CLEANUP_PENDING" to PublicationPhase.CLEANUP_PENDING,
            "RECOVERY_REQUIRED" to PublicationPhase.COPIED,
            "PERMISSION_REQUIRED" to PublicationPhase.CREATED,
            "ABORTED" to PublicationPhase.ABORTED,
            "INTENT" to PublicationPhase.INTENT,
            "CREATED" to PublicationPhase.CREATED,
            "COPIED" to PublicationPhase.COPIED,
        )
        // Verify exhaustive mapping for all 23 historical phase strings + canonical names.
        val database = Room.databaseBuilder(context, VrkaDatabase::class.java, dbName)
            .addMigrations(VrkaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = RoomStorageRepository(database)
            val sqlite = database.openHelper.writableDatabase
            val now = 10_000L
            expectedLegacyMappings.forEachIndexed { idx, (rawPhase, expectedPhase) ->
                val jobId = "phase-job-$idx"
                val attemptId = "$jobId:attempt:1"
                val batchId = "phase-batch-$idx"
                val txId = "tx-$idx-$rawPhase"
                val job = DownloadJob(
                    id = jobId,
                    request = DownloadRequest(url = "https://example.com/watch?v=$idx"),
                    state = JobState.POSTPROCESSING,
                )
                assertTrue(repo.saveJobs(listOf(job), setOf(job.id)).success)
                val reqJson = JobJsonCodec.encodeRequest(job.request)
                sqlite.execSQL(
                    "INSERT OR REPLACE INTO attempts VALUES (?, ?, 1, ?, NULL, 'POSTPROCESSING', ?, NULL, '', 1)",
                    arrayOf<Any?>(attemptId, jobId, reqJson, now),
                )
                sqlite.execSQL(
                    "INSERT OR REPLACE INTO publication_batches VALUES (?, ?, ?, 1, 'MEDIA_STORE', 'Downloads/VRKA', 'OPEN', 0, ?, ?)",
                    arrayOf<Any?>(batchId, jobId, attemptId, now, now),
                )
                val status = if (rawPhase == "CLEANUP_PENDING") "CLEANUP_PENDING" else "IN_PROGRESS"
                val providerUri = if (rawPhase in setOf("STAGING", "READY", "PREPARED", "INTENT_RECORDED", "CREATE_REQUESTED", "INTENT")) {
                    null
                } else {
                    "content://media/external/downloads/${100 + idx}"
                }
                sqlite.execSQL(
                    """
                    INSERT INTO publication_outputs VALUES (
                        ?, ?, ?, 1, ?, 0, 1, 'MEDIA_STORE',
                        'Downloads/VRKA', ?, ?, ?, NULL, ?, 64,
                        NULL, NULL, 64, 'SIZE_VERIFIED', NULL, ?, ?,
                        'NONE', NULL, ?, ?, 1
                    )
                    """.trimIndent(),
                    arrayOf<Any?>(
                        txId, jobId, attemptId, batchId,
                        ".vrka_pending_${txId}_f$idx.mp4", "f$idx.mp4",
                        providerUri, File(testDir, "f$idx.mp4").absolutePath,
                        rawPhase, status, now, now,
                    ),
                )
                val decoded = repo.publicationStore.entriesForJob(jobId).single()
                assertEquals("Legacy phase $rawPhase must normalize cleanly", expectedPhase, decoded.publicationPhase)
                if (rawPhase == "RECOVERY_REQUIRED" || rawPhase == "PERMISSION_REQUIRED") {
                    assertTrue("$rawPhase must carry recoveryRequired=true", decoded.recoveryRequired)
                } else {
                    assertFalse("$rawPhase must not falsely set recoveryRequired", decoded.recoveryRequired)
                }
                if (rawPhase == "STAGING_CLEANUP_PENDING") {
                    assertTrue("STAGING_CLEANUP_PENDING must carry stagingCleanupPending=true", decoded.stagingCleanupPending)
                }
            }

            // Startup reconciliation propagates legacy RECOVERY_REQUIRED / PERMISSION_REQUIRED onto JobEntity.
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val settings = SettingsRepository(context, MutableStateFlow(AppSettings()))
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repo.publicationStore,
                ops = ops,
                sdkInt = 34,
                mediaStoreCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION),
            )
            val manager = VrkaDownloadManager(
                context = context,
                settingsRepository = settings,
                scope = scope,
                customRepository = repo,
                customPublisher = publisher,
                customStagingRoot = File(testDir, "staging").apply { mkdirs() },
                customStagingCleanupStore = repo.cleanupStore,
                autoRecoverOnInit = false,
                runtimeInitOverride = {},
            )
            manager.reconcilePublicationJournalOnStartup()
            val recoveryIdx = expectedLegacyMappings.indexOfFirst { it.first == "RECOVERY_REQUIRED" }
            val permissionIdx = expectedLegacyMappings.indexOfFirst { it.first == "PERMISSION_REQUIRED" }
            assertTrue(repo.jobById("phase-job-$recoveryIdx")!!.recoveryRequired)
            assertTrue(repo.jobById("phase-job-$permissionIdx")!!.recoveryRequired)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun safW4Crash_withTwoIdenticalByteFilesInTree_failsSafeWithoutDeletingOrAdoptingAmbiguousFile() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val settings = SettingsRepository(
                context,
                MutableStateFlow(
                    AppSettings(
                        outputTreeUri = PublicationJournalRecoveryTest.TREE,
                        saveLocationMode = SaveLocationMode.REMEMBER_LOCATION,
                    ),
                ),
            )
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
            )

            val payload = ByteArray(1024) { (it % 251).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w4-job/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }

            val oldUri = Uri.parse("content://saf/tree/root/document/old-pre-rename-1")
            // Two identical-content files under the target tree after renameDocument swap + crash:
            val existingUserCopyUri = Uri.parse("content://saf/tree/root/document/user-copy-10")
            val renamedOutputUri = Uri.parse("content://saf/tree/root/document/renamed-11")
            val f1 = File(testDir, "saf_user_10.mp4").apply { writeBytes(payload) }
            val f2 = File(testDir, "saf_renamed_11.mp4").apply { writeBytes(payload) }
            val tree = Uri.parse(PublicationJournalRecoveryTest.TREE)
            val targetName = "Duplicate [vrka-txw4ambig].mp4"
            ops.safItems[existingUserCopyUri.toString()] =
                PublicationJournalRecoveryTest.SafItem(existingUserCopyUri, tree, targetName, f1)
            ops.safItems[renamedOutputUri.toString()] =
                PublicationJournalRecoveryTest.SafItem(renamedOutputUri, tree, targetName, f2)

            val job = DownloadJob(
                id = "w4-job",
                request = DownloadRequest(
                    url = "https://example.com/watch?v=w4",
                    destinationTreeUri = PublicationJournalRecoveryTest.TREE,
                ),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            assertTrue(repository.saveJobs(listOf(job), setOf(job.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = "tx-w4-ambig",
                    jobId = job.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = targetName,
                    pendingFilename = ".vrka_pending_tx-w4-ambig_$targetName",
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = PublicationJournalRecoveryTest.TREE,
                    publicationPhase = PublicationPhase.COPIED,
                    providerUri = oldUri.toString(),
                    copiedBytes = payload.size.toLong(),
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${job.id}:attempt:1",
                    batchId = "batch-w4",
                    expectedSha256 = sha,
                    verificationLevel = "HASH_VERIFIED",
                ),
            )

            val manager = VrkaDownloadManager(
                context = context,
                settingsRepository = settings,
                scope = scope,
                customRepository = repository,
                customPublisher = publisher,
                customStagingRoot = stagingRoot,
                customStagingCleanupStore = repository.cleanupStore,
                autoRecoverOnInit = false,
                runtimeInitOverride = {},
            )
            manager.reconcilePublicationJournalOnStartup()

            val recovered = repository.jobById(job.id)
            assertNotNull(recovered)
            assertNotEquals(
                "Ambiguous W4 SAF duplicate match must never auto-commit DONE",
                JobState.DONE,
                recovered!!.state,
            )
            assertFalse("Unverified user file must never be deleted on W4 ambiguity", ops.deletedUris.contains(existingUserCopyUri))
            assertFalse("Renamed candidate must never be deleted on W4 ambiguity", ops.deletedUris.contains(renamedOutputUri))
            assertTrue("Ambiguous W4 recovery must flag recoveryRequired = true", recovered.recoveryRequired)
            val retainedEntry = repository.publicationStore.entriesForJob(job.id).single()
            assertEquals(PublicationPhase.COPIED, retainedEntry.publicationPhase)
            assertEquals(oldUri.toString(), retainedEntry.providerUri)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun w1Crash_mediaStoreIntentWithoutUri_adoptsPendingRowAndFinalizesDone() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val settings = SettingsRepository(context, MutableStateFlow(AppSettings()))
            val mediaCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION)
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
                mediaStoreCollection = mediaCollection,
            )

            val payload = ByteArray(1024) { (it % 199).toByte() }
            val sha = sha256Hex(payload)
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w1-job/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }
            val txId = "tx-w1-pending-1"
            val finalName = "Clip [vrka-txw1pendin].mp4"
            val pendingName = ".vrka_pending_${txId}_$finalName"
            val pendingUri = ops.insertMediaStore(
                mediaCollection,
                android.content.ContentValues(),
                displayName = pendingName,
                isPending = true,
            )!!
            ops.openOutputStream(pendingUri, "w")!!.use { it.write(payload) }

            val job = DownloadJob(
                id = "w1-job",
                request = DownloadRequest(url = "https://example.com/watch?v=w1"),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            assertTrue(repository.saveJobs(listOf(job), setOf(job.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = txId,
                    jobId = job.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = finalName,
                    pendingFilename = pendingName,
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = PublicationUriRules.MEDIA_STORE_DESTINATION,
                    publicationPhase = PublicationPhase.INTENT,
                    providerUri = null,
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${job.id}:attempt:1",
                    batchId = "batch-w1",
                    expectedSha256 = sha,
                ),
            )

            val manager = createManager(repository, publisher, settings, scope, stagingRoot)
            manager.reconcilePublicationJournalOnStartup()

            val recovered = repository.jobById(job.id)!!
            assertEquals(JobState.DONE, recovered.state)
            assertFalse(recovered.recoveryRequired)
            assertEquals(listOf(pendingUri.toString()), recovered.outputUris)
            assertTrue(publisher.verifyMediaStoreFinalized(pendingUri, finalName))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun w2Crash_mediaStoreCopiedAlreadyFinalized_promotesToDoneIdempotently() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val settings = SettingsRepository(context, MutableStateFlow(AppSettings()))
            val mediaCollection = Uri.parse(PublicationJournalRecoveryTest.MEDIA_COLLECTION)
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
                mediaStoreCollection = mediaCollection,
            )

            val payload = ByteArray(1024) { (it % 173).toByte() }
            val sha = sha256Hex(payload)
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w2-job/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }
            val finalName = "Already Final [vrka-txw2copied].mp4"
            val finalUri = ops.insertMediaStore(
                mediaCollection,
                android.content.ContentValues(),
                displayName = finalName,
                isPending = false,
            )!!
            ops.openOutputStream(finalUri, "w")!!.use { it.write(payload) }

            val job = DownloadJob(
                id = "w2-job",
                request = DownloadRequest(url = "https://example.com/watch?v=w2"),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            assertTrue(repository.saveJobs(listOf(job), setOf(job.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = "tx-w2-copied-1",
                    jobId = job.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = finalName,
                    pendingFilename = ".vrka_pending_tx-w2-copied-1_$finalName",
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = PublicationUriRules.MEDIA_STORE_DESTINATION,
                    publicationPhase = PublicationPhase.COPIED,
                    providerUri = finalUri.toString(),
                    copiedBytes = payload.size.toLong(),
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${job.id}:attempt:1",
                    batchId = "batch-w2",
                    expectedSha256 = sha,
                ),
            )

            val manager = createManager(repository, publisher, settings, scope, stagingRoot)
            manager.reconcilePublicationJournalOnStartup()

            val recovered = repository.jobById(job.id)!!
            assertEquals(JobState.DONE, recovered.state)
            assertFalse(recovered.recoveryRequired)
            assertEquals(listOf(finalUri.toString()), recovered.outputUris)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun w3Crash_safIntentWithoutUri_adoptsPendingDocumentAndFinalizesDone() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val tree = Uri.parse(PublicationJournalRecoveryTest.TREE)
            val settings = SettingsRepository(
                context,
                MutableStateFlow(AppSettings(outputTreeUri = tree.toString(), saveLocationMode = SaveLocationMode.REMEMBER_LOCATION)),
            )
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
            )

            val payload = ByteArray(1024) { (it % 151).toByte() }
            val sha = sha256Hex(payload)
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w3-job/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }
            val txId = "tx-w3-saf-1"
            val finalName = "SAF Clip [vrka-txw3saf100].mp4"
            val pendingName = ".vrka_pending_${txId}_$finalName"
            val pendingUri = ops.createSafDocument(tree, "video/mp4", pendingName)!!
            ops.openOutputStream(pendingUri, "w")!!.use { it.write(payload) }

            val job = DownloadJob(
                id = "w3-job",
                request = DownloadRequest(url = "https://example.com/watch?v=w3", destinationTreeUri = tree.toString()),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            assertTrue(repository.saveJobs(listOf(job), setOf(job.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = txId,
                    jobId = job.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = finalName,
                    pendingFilename = pendingName,
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = tree.toString(),
                    publicationPhase = PublicationPhase.INTENT,
                    providerUri = null,
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${job.id}:attempt:1",
                    batchId = "batch-w3",
                    expectedSha256 = sha,
                ),
            )

            val manager = createManager(repository, publisher, settings, scope, stagingRoot)
            manager.reconcilePublicationJournalOnStartup()

            val recovered = repository.jobById(job.id)!!
            assertEquals(JobState.DONE, recovered.state)
            assertFalse(recovered.recoveryRequired)
            assertEquals(1, recovered.outputUris.size)
            assertEquals(finalName, ops.queryDisplayName(Uri.parse(recovered.outputUris.single())))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun w4Crash_safCopiedUniqueTaggedMatch_adoptsAndCompletesDone() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val tree = Uri.parse(PublicationJournalRecoveryTest.TREE)
            val settings = SettingsRepository(
                context,
                MutableStateFlow(AppSettings(outputTreeUri = tree.toString(), saveLocationMode = SaveLocationMode.REMEMBER_LOCATION)),
            )
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
            )

            val payload = ByteArray(1024) { (it % 139).toByte() }
            val sha = sha256Hex(payload)
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w4-clean/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }
            val oldUri = Uri.parse("content://saf/tree/root/document/old-pre-rename-w4")
            val renamedUri = Uri.parse("content://saf/tree/root/document/renamed-w4-clean")
            val txId = "tx-w4-clean1"
            val targetName = "Clean [vrka-txw4clean1].mp4"
            val backedFile = File(testDir, "saf_w4_clean.mp4").apply { writeBytes(payload) }
            ops.safItems[renamedUri.toString()] =
                PublicationJournalRecoveryTest.SafItem(renamedUri, tree, targetName, backedFile)

            val job = DownloadJob(
                id = "w4-clean",
                request = DownloadRequest(url = "https://example.com/watch?v=w4clean", destinationTreeUri = tree.toString()),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            assertTrue(repository.saveJobs(listOf(job), setOf(job.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = txId,
                    jobId = job.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = targetName,
                    pendingFilename = ".vrka_pending_${txId}_$targetName",
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = tree.toString(),
                    publicationPhase = PublicationPhase.COPIED,
                    providerUri = oldUri.toString(),
                    copiedBytes = payload.size.toLong(),
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${job.id}:attempt:1",
                    batchId = "batch-w4clean",
                    expectedSha256 = sha,
                ),
            )

            val manager = createManager(repository, publisher, settings, scope, stagingRoot)
            manager.reconcilePublicationJournalOnStartup()

            val recovered = repository.jobById(job.id)!!
            assertEquals(JobState.DONE, recovered.state)
            assertFalse(recovered.recoveryRequired)
            assertEquals(listOf(renamedUri.toString()), recovered.outputUris)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    @Test
    fun w4Crash_safCopiedZeroMatchesOrUntagged_preservesCopiedAndSetsRecoveryRequired() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = RoomStorageRepository(database)
            val ops = PublicationJournalRecoveryTest.TestPublicationStorageOps(testDir, sdkInt = 34)
            val tree = Uri.parse(PublicationJournalRecoveryTest.TREE)
            val settings = SettingsRepository(
                context,
                MutableStateFlow(AppSettings(outputTreeUri = tree.toString(), saveLocationMode = SaveLocationMode.REMEMBER_LOCATION)),
            )
            val publisher = OutputPublisher(
                context = context,
                settingsRepository = settings,
                journal = repository.publicationStore,
                ops = ops,
                sdkInt = 34,
            )

            val payload = ByteArray(1024) { (it % 127).toByte() }
            val sha = sha256Hex(payload)
            val stagingRoot = File(testDir, "staging").apply { mkdirs() }
            val staged = File(stagingRoot, "w4-fail/clip.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(payload)
            }

            // Case 1: Tagged intendedFilename, 0 matching docs in tree.
            val oldZeroUri = Uri.parse("content://saf/tree/root/document/old-zero")
            val zeroJob = DownloadJob(
                id = "w4-zero",
                request = DownloadRequest(url = "https://example.com/watch?v=w4zero", destinationTreeUri = tree.toString()),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )
            // Case 2: Untagged filename even if a doc with matching name and bytes exists in tree.
            val oldUntaggedUri = Uri.parse("content://saf/tree/root/document/old-untagged")
            val untaggedDocUri = Uri.parse("content://saf/tree/root/document/existing-untagged")
            val untaggedFile = File(testDir, "saf_untagged.mp4").apply { writeBytes(payload) }
            ops.safItems[untaggedDocUri.toString()] =
                PublicationJournalRecoveryTest.SafItem(untaggedDocUri, tree, "PlainName.mp4", untaggedFile)
            val untaggedJob = DownloadJob(
                id = "w4-untagged",
                request = DownloadRequest(url = "https://example.com/watch?v=w4untagged", destinationTreeUri = tree.toString()),
                state = JobState.POSTPROCESSING,
                publishStarted = true,
            )

            assertTrue(repository.saveJobs(listOf(zeroJob, untaggedJob), setOf(zeroJob.id, untaggedJob.id)).success)
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = "tx-w4-zero00",
                    jobId = zeroJob.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "Missing [vrka-txw4zero00].mp4",
                    pendingFilename = ".vrka_pending_tx-w4-zero00_Missing.mp4",
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = tree.toString(),
                    publicationPhase = PublicationPhase.COPIED,
                    providerUri = oldZeroUri.toString(),
                    copiedBytes = payload.size.toLong(),
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${zeroJob.id}:attempt:1",
                    batchId = "batch-w4zero",
                    expectedSha256 = sha,
                ),
            )
            repository.publicationStore.recordIntent(
                PublicationEntry(
                    transactionId = "tx-w4-plain0",
                    jobId = untaggedJob.id,
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "PlainName.mp4",
                    pendingFilename = ".vrka_pending_tx-w4-plain0_PlainName.mp4",
                    stagingPath = staged.absolutePath,
                    expectedSizeBytes = payload.size.toLong(),
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = tree.toString(),
                    publicationPhase = PublicationPhase.COPIED,
                    providerUri = oldUntaggedUri.toString(),
                    copiedBytes = payload.size.toLong(),
                    status = PublicationTransactionStatus.IN_PROGRESS,
                    attemptNumber = 1,
                    attemptId = "${untaggedJob.id}:attempt:1",
                    batchId = "batch-w4untagged",
                    expectedSha256 = sha,
                ),
            )

            val manager = createManager(repository, publisher, settings, scope, stagingRoot)
            manager.reconcilePublicationJournalOnStartup()

            val recZero = repository.jobById(zeroJob.id)!!
            assertTrue("0-match W4 must flag recoveryRequired", recZero.recoveryRequired)
            assertNotEquals(JobState.DONE, recZero.state)
            val zeroEntry = repository.publicationStore.entriesForJob(zeroJob.id).single()
            assertEquals(PublicationPhase.COPIED, zeroEntry.publicationPhase)
            assertEquals(oldZeroUri.toString(), zeroEntry.providerUri)

            val recUntagged = repository.jobById(untaggedJob.id)!!
            assertTrue("Untagged W4 must never adopt and must flag recoveryRequired", recUntagged.recoveryRequired)
            assertNotEquals(JobState.DONE, recUntagged.state)
            val untaggedEntry = repository.publicationStore.entriesForJob(untaggedJob.id).single()
            assertEquals(PublicationPhase.COPIED, untaggedEntry.publicationPhase)
            assertEquals(oldUntaggedUri.toString(), untaggedEntry.providerUri)

            // Retry must reset recoveryRequired = false on attempt + 1.
            val retryAttemptId = manager.retry(recZero.id)
            assertNotNull("retry() must accept recoveryRequired FAILED job", retryAttemptId)
            val retried = repository.jobById(recZero.id)!!
            assertFalse("retry() must reset recoveryRequired to false", retried.recoveryRequired)
            assertEquals(2, retried.attempt)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            database.close()
        }
    }

    private fun createManager(
        repository: RoomStorageRepository,
        publisher: OutputPublisher,
        settings: SettingsRepository,
        scope: CoroutineScope,
        stagingRoot: File,
    ): VrkaDownloadManager = VrkaDownloadManager(
        context = context,
        settingsRepository = settings,
        scope = scope,
        customRepository = repository,
        customPublisher = publisher,
        customStagingRoot = stagingRoot,
        customStagingCleanupStore = repository.cleanupStore,
        autoRecoverOnInit = false,
        runtimeInitOverride = {},
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
