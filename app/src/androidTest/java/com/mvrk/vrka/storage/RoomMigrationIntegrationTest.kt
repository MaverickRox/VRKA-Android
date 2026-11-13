package com.mvrk.vrka.storage

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RoomMigrationIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testDbName = "migration-test.db"

    @Before
    @After
    fun cleanup() {
        val dbFile = context.getDatabasePath(testDbName)
        listOf("", "-wal", "-shm", "-journal").forEach { ext ->
            File(dbFile.absolutePath + ext).delete()
        }
    }

    @Test
    fun testMigration1To2OnDevice() {
        val dbFile = context.getDatabasePath(testDbName)
        dbFile.parentFile?.mkdirs()

        // 1. Create a raw SQLite database at version 1 with the exact v1 schema from 1.json
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `jobs` (`id` TEXT NOT NULL, `request_json` TEXT NOT NULL, `state` TEXT NOT NULL, `title` TEXT NOT NULL, `detail` TEXT NOT NULL, `progress` REAL NOT NULL, `speed` TEXT NOT NULL, `eta_seconds` INTEGER, `downloaded_bytes` INTEGER, `estimated_total_bytes` INTEGER, `estimated_size_accuracy` TEXT NOT NULL, `reported_total_bytes` INTEGER, `reported_total_is_exact` INTEGER NOT NULL, `active_stream` TEXT, `playlist_item_index` INTEGER, `playlist_item_count` INTEGER, `actual_final_file_bytes` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `output_uris_json` TEXT NOT NULL, `error` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `actual_resolution` TEXT, `runtime_name` TEXT NOT NULL, `runtime_version` TEXT NOT NULL, `subtitle_status` TEXT NOT NULL, `publish_started` INTEGER NOT NULL, `current_attempt_id` TEXT NOT NULL, `legacy_verification` TEXT NOT NULL, `recovery_required` INTEGER NOT NULL, `terminal_at` INTEGER, `commit_sequence` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_state` ON `jobs` (`state`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_updated_at` ON `jobs` (`updated_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_jobs_recovery_required` ON `jobs` (`recovery_required`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `attempts` (`attempt_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_number` INTEGER NOT NULL, `request_json` TEXT NOT NULL, `destination_tree_uri` TEXT, `status` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, `legacy_trust` TEXT NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`attempt_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_attempts_job_id` ON `attempts` (`job_id`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attempts_job_id_attempt_number` ON `attempts` (`job_id`, `attempt_number`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `publication_batches` (`batch_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_id` TEXT NOT NULL, `total_outputs` INTEGER NOT NULL, `provider_type` TEXT NOT NULL, `destination_identity` TEXT NOT NULL, `status` TEXT NOT NULL, `legacy_ambiguous` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`batch_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`attempt_id`) REFERENCES `attempts`(`attempt_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_batches_job_id` ON `publication_batches` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_batches_attempt_id` ON `publication_batches` (`attempt_id`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `publication_outputs` (`transaction_id` TEXT NOT NULL, `job_id` TEXT NOT NULL, `attempt_id` TEXT NOT NULL, `attempt_number` INTEGER NOT NULL, `batch_id` TEXT NOT NULL, `output_index` INTEGER NOT NULL, `total_outputs` INTEGER NOT NULL, `provider_type` TEXT NOT NULL, `destination_identity` TEXT NOT NULL, `pending_filename` TEXT NOT NULL, `final_filename` TEXT NOT NULL, `provider_uri` TEXT, `provider_document_id` TEXT, `staging_key` TEXT, `expected_size_bytes` INTEGER NOT NULL, `expected_sha256` TEXT, `observed_sha256` TEXT, `copied_bytes` INTEGER NOT NULL, `verification_level` TEXT NOT NULL, `provider_capabilities_json` TEXT, `phase` TEXT NOT NULL, `status` TEXT NOT NULL, `cleanup_state` TEXT NOT NULL, `error_message` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`transaction_id`), FOREIGN KEY(`job_id`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`attempt_id`) REFERENCES `attempts`(`attempt_id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`batch_id`) REFERENCES `publication_batches`(`batch_id`) ON UPDATE NO ACTION ON DELETE RESTRICT )"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_job_id` ON `publication_outputs` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_attempt_id` ON `publication_outputs` (`attempt_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_batch_id` ON `publication_outputs` (`batch_id`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_publication_outputs_batch_id_output_index` ON `publication_outputs` (`batch_id`, `output_index`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_provider_uri` ON `publication_outputs` (`provider_uri`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_publication_outputs_status` ON `publication_outputs` (`status`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `cleanup_tasks` (`id` TEXT NOT NULL, `job_id` TEXT, `attempt_id` TEXT, `transaction_id` TEXT, `resource_type` TEXT NOT NULL, `resource_identity` TEXT NOT NULL, `stage` TEXT NOT NULL, `retry_count` INTEGER NOT NULL, `next_retry_at` INTEGER NOT NULL, `last_error_class` TEXT, `last_error` TEXT, `dead_letter` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_job_id` ON `cleanup_tasks` (`job_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_next_retry_at` ON `cleanup_tasks` (`next_retry_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cleanup_tasks_dead_letter` ON `cleanup_tasks` (`dead_letter`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cleanup_tasks_resource_type_resource_identity` ON `cleanup_tasks` (`resource_type`, `resource_identity`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `migration_ledger` (`source_key` TEXT NOT NULL, `source_tag` TEXT NOT NULL, `sha256` TEXT NOT NULL, `status` TEXT NOT NULL, `total_records` INTEGER NOT NULL, `imported_records` INTEGER NOT NULL, `detail` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`source_key`))"
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `migration_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_key` TEXT NOT NULL, `source_row` INTEGER NOT NULL, `reason` TEXT NOT NULL, `raw_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_migration_records_source_key` ON `migration_records` (`source_key`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_migration_records_source_key_source_row_reason` ON `migration_records` (`source_key`, `source_row`, `reason`)")

                    // Room v1 master table
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '2b73ced53dfa514c30b22aeda88c5aaf')")

                    // Seed a v1 job
                    db.execSQL(
                        """
                        INSERT INTO jobs (
                            id, request_json, state, title, detail, progress, speed,
                            eta_seconds, downloaded_bytes, estimated_total_bytes,
                            estimated_size_accuracy, reported_total_bytes, reported_total_is_exact,
                            active_stream, playlist_item_index, playlist_item_count,
                            actual_final_file_bytes, created_at, updated_at, output_uris_json,
                            error, attempt, actual_resolution, runtime_name, runtime_version,
                            subtitle_status, publish_started, current_attempt_id,
                            legacy_verification, recovery_required, terminal_at,
                            commit_sequence, version
                        ) VALUES (
                            'v1-job-1', '{}', 'COMPLETED', 'Pre-Migration Video', 'Saved', 1.0, '',
                            NULL, 1024, 1024, 'EXACT', 1024, 1,
                            NULL, 1, 1, 1024, 1000, 2000, '[]',
                            '', 1, '1080p', 'GeckoView', '159',
                            'NONE', 1, 'att-1', 'VERIFIED', 0, 2000, 1, 1
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val v1Db = helper.writableDatabase
        assertEquals(1, v1Db.version)
        v1Db.close()
        helper.close()

        // 2. Open via Room with MIGRATION_1_2
        val roomDb = Room.databaseBuilder(context, VrkaDatabase::class.java, testDbName)
            .addMigrations(VrkaDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        // 3. Verify migration succeeded and data is intact
        val job = roomDb.jobDao().byId("v1-job-1")
        assertNotNull("Pre-migration job must be preserved", job)
        assertEquals("v1-job-1", job?.id)
        assertEquals("Pre-Migration Video", job?.title)
        assertNull("Pre-migration job has null playlistGroupId", job?.playlistGroupId)

        // 4. Verify index_jobs_playlist_group_id was created
        val cursor = roomDb.openHelper.readableDatabase.query("PRAGMA index_list('jobs')")
        var foundIndex = false
        while (cursor.moveToNext()) {
            val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            if (name == "index_jobs_playlist_group_id") {
                foundIndex = true
            }
        }
        cursor.close()
        assertTrue("MIGRATION_1_2 must create index_jobs_playlist_group_id", foundIndex)

        // 5. Verify inserting and querying with playlistGroupId works seamlessly
        val newJob = job!!.copy(
            id = "v2-job-2",
            title = "Playlist Video",
            playlistGroupId = "pl-group-999",
            playlistItemIndex = 1,
            playlistItemCount = 2,
        )
        roomDb.jobDao().upsert(newJob)

        val groupJobs = roomDb.jobDao().forPlaylistGroup("pl-group-999")
        assertEquals(1, groupJobs.size)
        assertEquals("v2-job-2", groupJobs[0].id)
        assertEquals("Playlist Video", groupJobs[0].title)

        roomDb.close()
    }
}
