package com.mvrk.vrka.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LegacyMigrationFixtureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resource(path: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("phase0-fixtures/$path")
            ?: error("Missing fixture resource: $path")
        return stream.use { it.readBytes() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifest(): JSONArray {
        val text = String(resource("CORPUS_MANIFEST.json"), Charsets.UTF_8)
        return JSONObject(text).getJSONArray("cases")
    }

    @Test
    fun corpusManifestMatchesEveryFixtureHash() {
        val cases = manifest()
        assertTrue("Expected a substantial fixture corpus", cases.length() >= 80)
        var files = 0
        for (index in 0 until cases.length()) {
            val entry = cases.getJSONObject(index)
            if (entry.isNull("file")) continue
            val path = entry.getString("file")
            val bytes = resource(path)
            assertEquals(path, entry.getString("sha256"), sha256(bytes))
            files++
        }
        assertEquals(75, files)
    }

    @Test
    fun everyFixtureCaseMigratesWithoutLosingOrModifyingOriginals() = runBlocking(Dispatchers.IO) {
        val cases = manifest()
        for (index in 0 until cases.length()) {
            val entry = cases.getJSONObject(index)
            val caseName = entry.getString("case")
            val p = entry.optString("file").takeIf { !entry.isNull("file") && it.isNotBlank() }
            val dir = java.nio.file.Files.createTempDirectory("vrka-migration-$index").toFile()
            try {
                val sourceBytes = mutableMapOf<File, ByteArray>()
                fun install(resourcePath: String, targetName: String) {
                    val bytes = resource(resourcePath)
                    File(dir, targetName).writeBytes(bytes)
                    sourceBytes[File(dir, targetName)] = bytes
                }
                if (p != null) {
                    when {
                        p.contains("staging-cleanup") -> {
                            install("baseline-8b8591d/baseline-jobstore-schema2.json", "download_jobs.json")
                            install(p, "staging_cleanup.json")
                        }
                        p.contains("journal") -> {
                            val base = if (p.startsWith("baseline-")) {
                                "baseline-8b8591d/baseline-jobstore-schema2.json"
                            } else {
                                "v5.1.2/terminal-history-no-hashes.json"
                            }
                            install(base, "download_jobs.json")
                            install(p, "publication_journal.json")
                        }
                        else -> install(p, "download_jobs.json")
                    }
                }

                val db = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java).allowMainThreadQueries().build()
                try {
                    val repository = RoomStorageRepository(db)
                    val report = LegacyStorageMigrator(dir, db, repository).migrateIfNeeded()
                    val originalsUnchanged = sourceBytes.all { (file, bytes) ->
                        file.isFile && sha256(file.readBytes()) == sha256(bytes)
                    }
                    assertTrue("$caseName changed a legacy source file", originalsUnchanged)
                    val integrity = db.openHelper.writableDatabase.query("PRAGMA integrity_check").use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    assertEquals("$caseName integrity", "ok", integrity)
                    val fkViolations = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { it.count }
                    assertEquals("$caseName foreign keys", 0, fkViolations)
                    if (p != null) {
                        assertTrue("$caseName did not record a ledger", db.migrationDao().allLedger().isNotEmpty())
                        assertTrue("$caseName did not record a source profile", report.profile.isNotBlank())
                    }
                    val mustPreserve = caseName.contains("truncated") ||
                        caseName.contains("unsupported") ||
                        caseName.contains("synthesized") ||
                        caseName.contains("duplicate-transaction")
                    if (mustPreserve) {
                        assertTrue(
                            "$caseName should preserve an unimportable/ambiguous record",
                            db.migrationDao().records().isNotEmpty(),
                        )
                    }
                } finally {
                    db.close()
                }
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun repeatedMigrationIsIdempotent() = runBlocking(Dispatchers.IO) {
        val dir = java.nio.file.Files.createTempDirectory("vrka-migration-repeat").toFile()
        try {
            File(dir, "download_jobs.json").writeText(
                """
                {"schemaVersion":2,"jobs":[{"id":"job-repeat","state":"DONE","title":"Repeat",
                  "request":{"url":"https://example.com/repeat"},"outputs":["content://media/external/downloads/9"]}]}
                """.trimIndent(),
            )
            File(dir, "publication_journal.json").writeText(
                """
                {"schemaVersion":1,"entries":[{"transactionId":"tx-repeat","jobId":"job-repeat",
                  "outputIndex":0,"totalOutputs":1,"intendedFilename":"repeat.mp4",
                  "pendingFilename":".vrka_pending_tx-repeat_repeat.mp4",
                  "stagingPath":"/staging/job-repeat/repeat.mp4","expectedSizeBytes":10,
                  "providerType":"MEDIA_STORE","destinationIdentity":"Downloads/VRKA",
                  "publicationPhase":"FINALIZED","providerUri":"content://media/external/downloads/9",
                  "copiedBytes":10,"status":"RECONCILED","createdAt":1,"updatedAt":1}]}
                """.trimIndent(),
            )
            val db = Room.inMemoryDatabaseBuilder(context, VrkaDatabase::class.java).allowMainThreadQueries().build()
            try {
                val repository = RoomStorageRepository(db)
                val migrator = LegacyStorageMigrator(dir, db, repository)
                migrator.migrateIfNeeded()
                val firstJobs = db.jobDao().all().size
                val firstTx = db.publicationOutputDao().all().size
                migrator.migrateIfNeeded()
                assertEquals(firstJobs, db.jobDao().all().size)
                assertEquals(firstTx, db.publicationOutputDao().all().size)
            } finally {
                db.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
