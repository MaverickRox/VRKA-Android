package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.mvrk.vrka.storage.JobJsonCodec
import com.mvrk.vrka.storage.CleanupStore
import com.mvrk.vrka.storage.PublicationStore
import com.mvrk.vrka.storage.RoomStorageRepository
import com.mvrk.vrka.storage.VrkaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Publication recovery coverage. Test classification:
 *
 * - Full production-orchestration tests execute `OutputPublisher.publishAll()` followed by the
 *   manager terminal commit, journal reconciliation, and staging cleanup, then reconstruct a
 *   fresh manager and Room repository from the database on disk.
 * - Production-method tests exercise real manager and publisher methods with injected storage,
 *   journal, and filesystem dependencies.
 * - Isolated state tests validate legacy JobStore/PublicationJournal serialization only.
 *
 * Android instrumentation (real MediaStore and SAF providers) is not executed in this JVM
 * suite. The provider here is a controlled fake, not platform integration evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PublicationJournalRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val openDatabases = mutableListOf<Pair<String, VrkaDatabase>>()

    @org.junit.After
    fun closeRoomDatabases() {
        openDatabases.forEach { (name, database) ->
            runCatching { database.close() }
            deleteRoomDatabase(name)
        }
        openDatabases.clear()
    }

    // ---------------------------------------------------------------------------------------
    // Controlled provider and filesystem doubles
    // ---------------------------------------------------------------------------------------

    internal class MediaItem(
        val uri: Uri,
        val collection: Uri,
        var displayName: String,
        var isPending: Boolean,
        val file: File,
    )

    internal class SafItem(
        val uri: Uri,
        val tree: Uri,
        var displayName: String,
        val file: File,
    )

    internal class TestPublicationStorageOps(
        private val root: File,
        var sdkInt: Int = 34,
    ) : PublicationStorageOps {
        val mediaItems = LinkedHashMap<String, MediaItem>()
        val safItems = LinkedHashMap<String, SafItem>()
        val deletedUris = mutableListOf<Uri>()
        var insertCount = 0
        var providerOperationCount = 0
        var nextId = 1000L

        var failInsert = false
        var failCreateSaf = false
        var failUpdate = false
        var updateAffectedRows: Int? = null
        var omitPendingColumn = false
        var failProviderQuery = false
        var permissionRevoked = false
        var failDelete = false
        var throwOnDelete = false
        var failRename = false
        var byteLimit: Long? = null
        var crashAfterInsertBeforeUri = false
        var crashAfterCreateBeforeUri = false

        override fun insertMediaStore(
            collection: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Uri? {
            providerOperationCount++
            if (failInsert) return null
            val id = nextId++
            val uri = Uri.parse("content://media/external/downloads/$id")
            val item = MediaItem(
                uri = uri,
                collection = collection,
                displayName = displayName ?: "unnamed",
                isPending = isPending ?: true,
                file = File(root, "ms_$id.tmp").apply { createNewFile() },
            )
            mediaItems[uri.toString()] = item
            insertCount++
            if (crashAfterInsertBeforeUri) {
                throw RuntimeException("Crash immediately after provider insert before URI returned")
            }
            return uri
        }

        override fun openOutputStream(uri: Uri, mode: String): OutputStream? {
            providerOperationCount++
            val file = mediaItems[uri.toString()]?.file ?: safItems[uri.toString()]?.file ?: return null
            val base = file.outputStream()
            val limit = byteLimit ?: return base
            return object : OutputStream() {
                private var written = 0L
                override fun write(b: Int) {
                    if (written >= limit) throw IOException("Simulated I/O failure after $written bytes")
                    base.write(b)
                    written++
                }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val remaining = limit - written
                    if (remaining <= 0) throw IOException("Simulated I/O failure after $written bytes")
                    val toWrite = minOf(len.toLong(), remaining).toInt()
                    base.write(b, off, toWrite)
                    written += toWrite
                    if (toWrite < len) throw IOException("Simulated I/O failure during write after $written bytes")
                }
                override fun flush() = base.flush()
                override fun close() = base.close()
            }
        }

        override fun updateMediaStore(
            uri: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Int {
            providerOperationCount++
            if (failUpdate) throw IOException("Simulated provider update failure")
            val item = mediaItems[uri.toString()] ?: return 0
            val affected = updateAffectedRows
            if (affected != null && affected <= 0) return affected
            if (displayName != null) item.displayName = displayName
            if (isPending != null) item.isPending = isPending
            return affected ?: 1
        }

        override fun queryMediaStore(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Cursor? {
            providerOperationCount++
            if (failProviderQuery) throw IOException("Simulated provider query failure")
            val key = uri.toString()
            if (key.startsWith("content://saf/")) {
                if (permissionRevoked) throw SecurityException("SAF permission revoked")
                val item = safItems[key] ?: return cursor(projection, emptyList())
                return cursor(projection, listOf(safRow(item)))
            }
            val item = mediaItems[key] ?: return cursor(projection, emptyList())
            return cursor(projection, listOf(mediaRow(item)))
        }

        override fun queryMediaStoreCollection(
            collection: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Cursor? {
            providerOperationCount++
            if (failProviderQuery) throw IOException("Simulated provider query failure")
            val nameFilter = selectionArgs?.firstOrNull()
            val rows = mediaItems.values
                .filter { nameFilter == null || it.displayName == nameFilter }
                .map(::mediaRow)
            return cursor(projection, rows)
        }

        override fun deleteUri(uri: Uri): Boolean {
            providerOperationCount++
            return deleteObject(uri)
        }

        override fun deleteSafDocument(uri: Uri): Boolean {
            providerOperationCount++
            return deleteObject(uri)
        }

        private fun deleteObject(uri: Uri): Boolean {
            if (throwOnDelete) throw IOException("Simulated provider delete failure")
            if (failDelete) return false
            deletedUris += uri
            val media = mediaItems.remove(uri.toString())
            media?.file?.delete()
            val saf = safItems.remove(uri.toString())
            saf?.file?.delete()
            return media != null || saf != null
        }

        override fun fileExists(uri: Uri): Boolean {
            providerOperationCount++
            return mediaItems.containsKey(uri.toString()) || safItems.containsKey(uri.toString())
        }

        override fun getFileSize(uri: Uri): Long {
            providerOperationCount++
            return mediaItems[uri.toString()]?.file?.length() ?: safItems[uri.toString()]?.file?.length() ?: -1L
        }

        override fun queryDisplayName(uri: Uri): String? {
            providerOperationCount++
            return mediaItems[uri.toString()]?.displayName ?: safItems[uri.toString()]?.displayName
        }

        override fun readBack(uri: Uri): ProviderReadback? {
            providerOperationCount++
            val file = mediaItems[uri.toString()]?.file ?: safItems[uri.toString()]?.file ?: return null
            val bytes = file.readBytes()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            return ProviderReadback(bytes.size.toLong(), digest)
        }

        override fun querySafCapabilities(tree: Uri): SafCapabilities {
            providerOperationCount++
            return SafCapabilities(
                canCreate = true,
                canEnumerate = true,
                hasReliableDocumentIdentity = true,
                canRename = !failRename,
                canReadBack = true,
            )
        }

        override fun findSafDocumentUriByName(tree: Uri, displayName: String): UriLookupResult {
            providerOperationCount++
            if (failProviderQuery) throw IOException("Simulated provider query failure")
            if (permissionRevoked) throw SecurityException("SAF permission revoked")
            val matches = safItems.values.filter { it.tree == tree && it.displayName == displayName }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single().uri)
                else -> UriLookupResult.Unknown("Multiple documents match the final name")
            }
        }

        override fun findPendingMediaStoreUri(pendingFilename: String): UriLookupResult {
            providerOperationCount++
            if (failProviderQuery) throw IOException("Simulated provider query failure")
            val matches = mediaItems.values.filter { it.displayName == pendingFilename }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single().uri)
                else -> UriLookupResult.Unknown("Multiple rows match the pending name")
            }
        }

        override fun existingSafNames(tree: Uri): Set<String> {
            providerOperationCount++
            return safItems.values.filter { it.tree == tree }.mapTo(HashSet()) { it.displayName }
        }

        override fun findPendingSafUri(tree: Uri, pendingFilename: String): UriLookupResult {
            providerOperationCount++
            if (failProviderQuery) throw IOException("Simulated provider query failure")
            if (permissionRevoked) throw SecurityException("SAF permission revoked")
            val matches = safItems.values.filter { it.tree == tree && it.displayName == pendingFilename }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single().uri)
                else -> UriLookupResult.Unknown("Multiple documents match the pending name")
            }
        }

        override fun createSafDocument(tree: Uri, mimeType: String, pendingFilename: String): Uri? {
            providerOperationCount++
            if (failCreateSaf) return null
            val id = nextId++
            val uri = Uri.parse("content://saf/tree/root/document/$id")
            val item = SafItem(
                uri = uri,
                tree = tree,
                displayName = pendingFilename,
                file = File(root, "saf_$id.tmp").apply { createNewFile() },
            )
            safItems[uri.toString()] = item
            if (crashAfterCreateBeforeUri) {
                throw RuntimeException("Crash immediately after SAF create before URI returned")
            }
            return uri
        }

        override fun renameSafDocument(uri: Uri, finalName: String): Uri? {
            providerOperationCount++
            if (failRename) return null
            val item = safItems[uri.toString()] ?: return null
            item.displayName = finalName
            return uri
        }

        private fun mediaRow(item: MediaItem): Map<String, Any?> = mapOf(
            MediaStore.MediaColumns._ID to (item.uri.lastPathSegment?.toLongOrNull() ?: 1L),
            MediaStore.MediaColumns.DISPLAY_NAME to item.displayName,
            MediaStore.MediaColumns.IS_PENDING to if (item.isPending) 1 else 0,
            MediaStore.MediaColumns.RELATIVE_PATH to "Download/VRKA",
            OpenableColumns.SIZE to item.file.length(),
        )

        private fun safRow(item: SafItem): Map<String, Any?> = mapOf(
            OpenableColumns.DISPLAY_NAME to item.displayName,
            OpenableColumns.SIZE to item.file.length(),
        )

        private fun cursor(projection: Array<String>?, rows: List<Map<String, Any?>>): Cursor {
            val requested = projection?.toMutableList() ?: mutableListOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                OpenableColumns.SIZE,
            )
            if (omitPendingColumn) requested.remove(MediaStore.MediaColumns.IS_PENDING)
            return createMockCursor(requested.toTypedArray(), rows)
        }
    }

    internal class TestJobStoreFileOps(
        var failWrite: Boolean = false,
        var failMove: Boolean = false,
    ) : JobStoreFileOps {
        override fun readText(file: File): String = file.readText(Charsets.UTF_8)
        override fun createTempFile(parent: File, prefix: String): File =
            File.createTempFile(prefix, ".tmp", parent)
        override fun writeText(file: File, text: String) {
            if (failWrite) throw IOException("Simulated JobStore write failure")
            file.writeText(text, Charsets.UTF_8)
        }
        override fun atomicMove(source: File, target: File): Boolean {
            if (failMove) return false
            return DefaultJobStoreFileOps.atomicMove(source, target)
        }
        override fun replaceMove(source: File, target: File) {
            if (failMove) throw IOException("Simulated JobStore move failure")
            DefaultJobStoreFileOps.replaceMove(source, target)
        }
    }

    internal class BlockingJobStoreFileOps : JobStoreFileOps by DefaultJobStoreFileOps {
        @Volatile
        var blockNextMove = false
        val moveStarted = CountDownLatch(1)
        val releaseMove = CountDownLatch(1)

        override fun atomicMove(source: File, target: File): Boolean {
            if (blockNextMove) {
                blockNextMove = false
                moveStarted.countDown()
                releaseMove.await(10, TimeUnit.SECONDS)
            }
            return DefaultJobStoreFileOps.atomicMove(source, target)
        }
    }

    companion object {
        const val TREE = "content://saf/tree/root"
        const val MEDIA_COLLECTION = "content://media/external/downloads"

        fun createMockCursor(columns: Array<String>, rows: List<Map<String, Any?>>): Cursor {
            var rowIndex = -1
            return Proxy.newProxyInstance(
                Cursor::class.java.classLoader,
                arrayOf(Cursor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "moveToFirst" -> {
                        rowIndex = if (rows.isNotEmpty()) 0 else -1
                        rowIndex != -1
                    }
                    "moveToNext" -> {
                        rowIndex++
                        rowIndex < rows.size
                    }
                    "close" -> null
                    "getCount" -> rows.size
                    "getColumnIndex" -> (args[0] as String).let { columns.indexOf(it) }
                    "getString" -> rows[rowIndex][columns[args[0] as Int]]?.toString()
                    "getInt" -> (rows[rowIndex][columns[args[0] as Int]] as? Number)?.toInt() ?: 0
                    "getLong" -> (rows[rowIndex][columns[args[0] as Int]] as? Number)?.toLong() ?: 0L
                    "isNull" -> rows[rowIndex][columns[args[0] as Int]] == null
                    else -> null
                }
            } as Cursor
        }

        internal class TestContext(baseFilesDir: File) : android.content.ContextWrapper(null) {
            private val files = File(baseFilesDir, "files").apply { mkdirs() }
            private val cache = File(baseFilesDir, "cache").apply { mkdirs() }
            private val noBackup = File(baseFilesDir, "noBackup").apply { mkdirs() }

            private var editorProxy: android.content.SharedPreferences.Editor? = null
            init {
                editorProxy = Proxy.newProxyInstance(
                    android.content.SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences.Editor::class.java),
                ) { _, method, _ ->
                    when (method.name) {
                        "commit" -> true
                        "apply" -> null
                        else -> editorProxy
                    }
                } as android.content.SharedPreferences.Editor
            }

            private val prefs: android.content.SharedPreferences = Proxy.newProxyInstance(
                android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getString" -> args.getOrNull(1)
                    "getInt" -> args.getOrNull(1) ?: 0
                    "getLong" -> args.getOrNull(1) ?: 0L
                    "getBoolean" -> args.getOrNull(1) ?: false
                    "contains" -> false
                    "all" -> emptyMap<String, Any>()
                    "edit" -> editorProxy
                    else -> null
                }
            } as android.content.SharedPreferences

            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getExternalFilesDir(type: String?): File = files
            override fun getPackageName(): String = "com.mvrk.vrka"
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo =
                android.content.pm.ApplicationInfo().apply {
                    uid = 1000
                    packageName = "com.mvrk.vrka"
                }
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = prefs
        }
    }

    // ---------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------

    internal inner class Harness(
        val directory: File,
        val ops: TestPublicationStorageOps,
        val jobStoreOps: JobStoreFileOps,
        val databaseName: String,
        val database: VrkaDatabase,
        val repository: RoomStorageRepository,
        val journal: PublicationStore,
        val cleanupStore: CleanupStore,
        val publisher: OutputPublisher,
        val manager: VrkaDownloadManager,
        val stagingRoot: File,
        val settingsRepository: SettingsRepository,
    ) {
        internal fun restart(
            ops: TestPublicationStorageOps? = null,
            jobStoreOps: JobStoreFileOps? = null,
            stagingDelete: ((File) -> Boolean)? = null,
        ): Harness {
            val effectiveOps = ops ?: this.ops
            runCatching { database.close() }
            return createHarness(
                directory = directory,
                ops = effectiveOps,
                jobStoreOps = jobStoreOps ?: this.jobStoreOps,
                stagingDelete = stagingDelete ?: { it.deleteRecursively() },
                sdkInt = effectiveOps.sdkInt,
            )
        }
    }

    private fun createHarness(
        directory: File,
        ops: TestPublicationStorageOps,
        initialJobs: List<DownloadJob>? = null,
        jobStoreOps: JobStoreFileOps = DefaultJobStoreFileOps,
        sdkInt: Int = 34,
        stagingDelete: (File) -> Boolean = { it.deleteRecursively() },
    ): Harness {
        val databaseName = roomDatabaseName(directory)
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.databaseBuilder(appContext, VrkaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        openDatabases += databaseName to database
        val repository = RoomStorageRepository(database)
        if (!initialJobs.isNullOrEmpty()) {
            val seeded = runBlocking { repository.saveJobs(initialJobs, initialJobs.mapTo(HashSet()) { it.id }) }
            assertTrue("Harness seed save must succeed: ${seeded.error}", seeded.success)
        }
        runBlocking { repository.loadCache() }
        val jsonJournal = PublicationJournal(File(directory, "publication_journal.json"), jobStoreOps)
        val journal = MirroredPublicationStore(jsonJournal, repository)
        val context = TestContext(directory)
        val settingsRepo = SettingsRepository(
            context,
            MutableStateFlow(AppSettings(outputTreeUri = "")),
        )
        val publisher = OutputPublisher(
            context = context,
            settingsRepository = settingsRepo,
            journal = journal,
            ops = ops,
            sdkInt = sdkInt,
            mediaStoreCollection = Uri.parse(MEDIA_COLLECTION),
        )
        val stagingRoot = File(directory, "files/staging")
        val cleanupStore = repository.cleanupStore
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settingsRepo,
            scope = testScope,
            customPublisher = publisher,
            customRepository = repository,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = cleanupStore,
            stagingDelete = stagingDelete,
            autoRecoverOnInit = false,
        )
        return Harness(
            directory = directory,
            ops = ops,
            jobStoreOps = jobStoreOps,
            databaseName = databaseName,
            database = database,
            repository = repository,
            journal = journal,
            cleanupStore = cleanupStore,
            publisher = publisher,
            manager = manager,
            stagingRoot = stagingRoot,
            settingsRepository = settingsRepo,
        )
    }

    private inner class MirroredPublicationStore(
        private val journal: PublicationJournal,
        private val repository: RoomStorageRepository,
    ) : PublicationStore {
        private val room get() = repository.publicationStore

        override val lastLoadResult: PublicationJournalLoadResult?
            get() = room.lastLoadResult ?: journal.lastLoadResult

        override fun recordIntent(entry: PublicationEntry) = recordBatchIntents(listOf(entry))

        override fun recordBatchIntents(batch: List<PublicationEntry>) {
            val missing = batch.map { it.jobId }.distinct().filter { id ->
                repository.cachedJobs().none { it.id == id }
            }
            if (missing.isNotEmpty()) {
                val jobs = missing.map { id -> sampleJob(id) }
                val result = runBlocking { repository.saveJobs(jobs, missing.toSet()) }
                check(result.success) { "Could not seed Room jobs for publication test: ${result.error}" }
                runBlocking { repository.loadCache() }
            }
            room.recordBatchIntents(batch)
            journal.recordBatchIntents(batch)
        }

        override fun updateProviderCreated(transactionId: String, providerUri: String) {
            room.updateProviderCreated(transactionId, providerUri)
            journal.updateProviderCreated(transactionId, providerUri)
        }

        override fun updateProviderUri(transactionId: String, providerUri: String) {
            room.updateProviderUri(transactionId, providerUri)
            journal.updateProviderUri(transactionId, providerUri)
        }

        override fun updateDataCopied(transactionId: String, copiedBytes: Long) {
            room.updateDataCopied(transactionId, copiedBytes)
            journal.updateDataCopied(transactionId, copiedBytes)
        }

        override fun updateValidated(transactionId: String) {
            room.updateValidated(transactionId)
            journal.updateValidated(transactionId)
        }

        override fun updateVerification(transactionId: String, verificationLevel: String, observedSha256: String?) {
            room.updateVerification(transactionId, verificationLevel, observedSha256)
            journal.updateVerification(transactionId, verificationLevel, observedSha256)
        }

        override fun updateIntendedFilename(transactionId: String, intendedFilename: String) {
            room.updateIntendedFilename(transactionId, intendedFilename)
            journal.updateIntendedFilename(transactionId, intendedFilename)
        }

        override fun updateFinalized(transactionId: String, newUri: String?) {
            room.updateFinalized(transactionId, newUri)
            journal.updateFinalized(transactionId, newUri)
        }

        override fun updateRecorded(transactionId: String) {
            room.updateRecorded(transactionId)
            journal.updateRecorded(transactionId)
        }

        override fun markInterrupted(transactionId: String) {
            room.markInterrupted(transactionId)
            journal.markInterrupted(transactionId)
        }

        override fun markFailed(transactionId: String, error: String) {
            room.markFailed(transactionId, error)
            journal.markFailed(transactionId, error)
        }

        override fun markCleanupPending(transactionId: String, error: String?) {
            room.markCleanupPending(transactionId, error)
            journal.markCleanupPending(transactionId, error)
        }

        override fun updateDeleted(transactionId: String) {
            room.updateDeleted(transactionId)
            journal.updateDeleted(transactionId)
        }

        override fun markReconciled(transactionIds: Collection<String>) {
            room.markReconciled(transactionIds)
            journal.markReconciled(transactionIds)
        }

        override fun clearJob(jobId: String) {
            room.clearJob(jobId)
            journal.clearJob(jobId)
        }

        override fun clearAll() {
            room.clearAll()
            journal.clearAll()
        }

        override fun entriesForJob(jobId: String): List<PublicationEntry> = room.entriesForJob(jobId)
        override fun unreconciledEntries(): List<PublicationEntry> = room.unreconciledEntries()
        override fun allEntries(): List<PublicationEntry> = room.allEntries()

        override fun loadWithStatus(): PublicationJournalLoadResult {
            val jsonStatus = journal.loadWithStatus()
            if (jsonStatus.state in setOf(
                    PublicationJournalLoadState.CORRUPT,
                    PublicationJournalLoadState.UNSUPPORTED_SCHEMA,
                    PublicationJournalLoadState.READ_FAILED,
                )
            ) return jsonStatus
            return room.loadWithStatus()
        }

        override fun updatePhase(transactionId: String, phase: PublicationPhase) {
            room.updatePhase(transactionId, phase)
            journal.updatePhase(transactionId, phase)
        }
    }

    private fun roomDatabaseName(directory: File): String =
        "vrka-recovery-${directory.name.hashCode().toUInt().toString(16)}.db"

    private fun deleteRoomDatabase(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = context.getDatabasePath(name)
        listOf("", "-wal", "-shm").forEach { File(base.absolutePath + it).delete() }
    }

    private fun installRejectJobUpsert(database: VrkaDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_job_insert BEFORE INSERT ON jobs BEGIN SELECT RAISE(ABORT, 'test write failure'); END",
        )
    }

    private fun removeRejectJobUpsert(database: VrkaDatabase) {
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_job_insert")
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun sampleRequest(url: String = "https://example.com/video") = DownloadRequest(
        url = url,
        mode = MediaMode.VIDEO,
        quality = VideoQuality.P1080,
    )

    private fun sampleJob(
        id: String,
        state: JobState = JobState.POSTPROCESSING,
        publishStarted: Boolean = true,
        outputUris: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis(),
    ) = DownloadJob(
        id = id,
        request = sampleRequest(),
        state = state,
        title = "Test Media $id",
        detail = "Publishing to storage",
        publishStarted = publishStarted,
        outputUris = outputUris,
        legacyVerification = if (state == JobState.DONE) "LEGACY_DONE_NO_HASH" else "",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun pendingEntry(
        jobId: String,
        txId: String,
        stagingRoot: File,
        index: Int = 0,
        total: Int = 1,
        finalName: String = "video-$index.mp4",
        size: Long = 128L,
        providerType: PublicationProviderType = PublicationProviderType.MEDIA_STORE,
        destination: String = PublicationUriRules.MEDIA_STORE_DESTINATION,
        createdAt: Long = System.currentTimeMillis(),
    ): PublicationEntry = PublicationEntry(
        transactionId = txId,
        jobId = jobId,
        outputIndex = index,
        totalOutputs = total,
        intendedFilename = finalName,
        pendingFilename = PublicationUriRules.pendingNameFor(txId, finalName),
        stagingPath = File(stagingRoot, "$jobId/$finalName").absolutePath,
        expectedSizeBytes = size,
        providerType = providerType,
        destinationIdentity = destination,
        publicationPhase = PublicationPhase.INTENT,
        attemptNumber = 1,
        attemptId = JobJsonCodec.attemptIdFor(jobId, 1),
        batchId = "batch-$txId",
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private data class SeededTransaction(val entry: PublicationEntry, val uri: Uri?)

    private fun seedTransaction(
        journal: PublicationStore,
        publisher: OutputPublisher,
        ops: TestPublicationStorageOps,
        stagingRoot: File,
        jobId: String,
        txId: String,
        index: Int = 0,
        total: Int = 1,
        finalName: String = "video-$index.mp4",
        size: Long = 128L,
        providerType: PublicationProviderType = PublicationProviderType.MEDIA_STORE,
        phase: PublicationPhase = PublicationPhase.FINALIZED,
        createdAt: Long = System.currentTimeMillis(),
        createProviderObject: Boolean = true,
        finalizeProvider: Boolean = true,
        writtenSize: Long = size,
    ): SeededTransaction {
        val entry = pendingEntry(
            jobId = jobId,
            txId = txId,
            stagingRoot = stagingRoot,
            index = index,
            total = total,
            finalName = finalName,
            size = size,
            providerType = providerType,
            destination = if (providerType == PublicationProviderType.SAF) {
                TREE
            } else {
                PublicationUriRules.MEDIA_STORE_DESTINATION
            },
            createdAt = createdAt,
        )
        journal.recordIntent(entry)
        if (!createProviderObject) return SeededTransaction(entry, null)

        val uri = when (providerType) {
            PublicationProviderType.MEDIA_STORE -> ops.insertMediaStore(
                Uri.parse(MEDIA_COLLECTION),
                ContentValues(),
                displayName = entry.pendingFilename,
                isPending = true,
            )!!
            PublicationProviderType.SAF -> ops.createSafDocument(
                Uri.parse(TREE),
                "video/mp4",
                entry.pendingFilename,
            )!!
        }
        ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(writtenSize.toInt())) }
        journal.updateProviderCreated(txId, uri.toString())
        if (phase == PublicationPhase.CREATED) {
            return SeededTransaction(entry.copy(providerUri = uri.toString(), publicationPhase = phase), uri)
        }
        journal.updateDataCopied(txId, size)
        if (phase == PublicationPhase.COPIED) {
            return SeededTransaction(entry.copy(providerUri = uri.toString(), publicationPhase = phase), uri)
        }
        journal.updateValidated(txId)
        if (!finalizeProvider) {
            when (providerType) {
                PublicationProviderType.MEDIA_STORE -> {
                    val item = ops.mediaItems.getValue(uri.toString())
                    item.displayName = finalName
                    item.isPending = false
                }
                PublicationProviderType.SAF -> {
                    ops.safItems.getValue(uri.toString()).displayName = finalName
                }
            }
            journal.updateFinalized(txId, uri.toString())
            return SeededTransaction(
                entry.copy(
                    providerUri = uri.toString(),
                    publicationPhase = PublicationPhase.FINALIZED,
                    status = PublicationTransactionStatus.COMPLETED,
                ),
                uri,
            )
        }
        val finalUri = when (providerType) {
            PublicationProviderType.MEDIA_STORE -> {
                assertTrue(publisher.finalizeMediaStore(uri, finalName))
                uri
            }
            PublicationProviderType.SAF -> publisher.finalizeSaf(uri, finalName)
        }
        journal.updateFinalized(txId, finalUri.toString())
        return SeededTransaction(
            entry.copy(
                providerUri = finalUri.toString(),
                publicationPhase = PublicationPhase.FINALIZED,
                status = PublicationTransactionStatus.COMPLETED,
            ),
            finalUri,
        )
    }

    private fun storedJob(directory: File, jobId: String): DownloadJob? {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VrkaDatabase::class.java,
            roomDatabaseName(directory),
        ).allowMainThreadQueries().build()
        return try {
            database.jobDao().byId(jobId)?.let(JobJsonCodec::decodeJob)
        } finally {
            database.close()
        }
    }

    private fun storeJobs(directory: File): List<DownloadJob> {
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            VrkaDatabase::class.java,
            roomDatabaseName(directory),
        ).allowMainThreadQueries().build()
        return try {
            database.jobDao().all().map(JobJsonCodec::decodeJob)
        } finally {
            database.close()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Full production orchestration
    // ---------------------------------------------------------------------------------------

    @Test
    fun productionOrchestration_publishCommitAndFreshRestart_reconcilesWithoutDuplicates() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("orchestration")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-orch")
            val harness = createHarness(dir, ops, initialJobs = listOf(job))
            val stagingFile = File(harness.stagingRoot, "job-orch/video.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(256))
            }

            val published = harness.publisher.publishAll(
                files = listOf(stagingFile to "video.mp4"),
                destinationTreeUri = null,
                jobId = job.id,
            )
            assertTrue(
                "publishAll must not delete its staging source before the terminal commit",
                stagingFile.exists(),
            )
            val committed = harness.manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 256L,
                reportedTotalBytes = 256L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 256L,
                outputUris = published.map { it.uri.toString() },
                transactionIds = published.mapTo(HashSet()) { it.transactionId },
                attemptNumber = job.attempt,
                batchId = published.single().batchId,
            )

            assertTrue("Terminal commit must succeed when the store is healthy", committed)
            assertEquals(JobState.DONE, storedJob(dir, job.id)!!.state)
            assertTrue(harness.journal.unreconciledEntries().isEmpty())
            assertFalse("Staging must be deleted only after durable completion", stagingFile.exists())
            assertEquals("No provider insert may be repeated", 1, ops.insertCount)

            val fresh = harness.restart()
            fresh.manager.reconcilePublicationJournalOnStartup()

            assertEquals(1, ops.insertCount)
            assertEquals(JobState.DONE, storedJob(dir, job.id)!!.state)
            assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun legacyJobStorePathCannotCommitOrReconcileACompletedPublication() = runBlocking {
        val dir = tempFolder.newFolder("legacy-store-no-terminal-commit")
        val ops = TestPublicationStorageOps(dir)
        val job = sampleJob("job-legacy-no-room")
        val store = JobStore(File(dir, "download_jobs.json"))
        assertTrue(store.save(listOf(job), setOf(job.id)).success)
        val journal = PublicationJournal(File(dir, "publication_journal.json"))
        val context = TestContext(dir)
        val settings = SettingsRepository(context, MutableStateFlow(AppSettings(outputTreeUri = "")))
        val publisher = OutputPublisher(
            context = context,
            settingsRepository = settings,
            journal = journal,
            ops = ops,
            sdkInt = 34,
            mediaStoreCollection = Uri.parse(MEDIA_COLLECTION),
        )
        val stagingRoot = File(dir, "files/staging")
        val manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settings,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            customStore = store,
            customPublisher = publisher,
            customStagingRoot = stagingRoot,
            customStagingCleanupStore = StagingCleanupStore(File(dir, "staging_cleanup.json")),
            autoRecoverOnInit = false,
        )
        val seeded = seedTransaction(
            journal = journal,
            publisher = publisher,
            ops = ops,
            stagingRoot = stagingRoot,
            jobId = job.id,
            txId = "tx-legacy-no-room",
            size = 16L,
        )
        val uri = seeded.uri!!

        assertFalse(
            "A JobStore-only manager must not mark a publication DONE",
            manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Must require Room",
                downloadedBytes = 16L,
                reportedTotalBytes = 16L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 16L,
                outputUris = listOf(uri.toString()),
                transactionIds = setOf(seeded.entry.transactionId),
                attemptNumber = 1,
                batchId = seeded.entry.batchId,
            ),
        )

        manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.POSTPROCESSING, store.load(recoverActive = true).single().state)
        assertEquals(1, journal.unreconciledEntries().size)
        assertTrue("The provider output must remain available", ops.fileExists(uri))
    }

    @Test
    fun terminalSaveFailure_retainsJournalAndStaging_freshRestartRecoversToDone() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("terminal-failure")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-terminal")
            val harness = createHarness(dir, ops, initialJobs = listOf(job))
            val stagingFile = File(harness.stagingRoot, "job-terminal/video.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(128))
            }
            val seeded = seedTransaction(
                harness.journal,
                harness.publisher,
                ops,
                harness.stagingRoot,
                "job-terminal",
                "tx-terminal",
                finalName = "video.mp4",
                size = 128,
            )

            installRejectJobUpsert(harness.database)
            val committed = harness.manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 128L,
                reportedTotalBytes = 128L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 128L,
                outputUris = listOf(seeded.uri!!.toString()),
                transactionIds = setOf("tx-terminal"),
                attemptNumber = job.attempt,
                batchId = seeded.entry.batchId,
            )

            assertFalse("A save exception must not report durable completion", committed)
            assertEquals("Journal must remain unreconciled", 1, harness.journal.unreconciledEntries().size)
            assertTrue("Staging must be retained after a failed terminal commit", stagingFile.exists())
            assertEquals(JobState.POSTPROCESSING, storedJob(dir, job.id)!!.state)

            removeRejectJobUpsert(harness.database)
            val fresh = harness.restart()
            fresh.manager.reconcilePublicationJournalOnStartup()

            assertEquals(JobState.DONE, storedJob(dir, job.id)!!.state)
            assertTrue(fresh.journal.unreconciledEntries().isEmpty())
            assertFalse(stagingFile.exists())
            assertEquals(1, ops.insertCount)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun terminalPersistenceBeforeJournalReconciliation_freshRuntimeRetiresExactTransactions() {
        val dir = tempFolder.newFolder("persisted-before-reconcile")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-persisted", state = JobState.FAILED)))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-persisted",
            "tx-persisted",
            finalName = "video.mp4",
        )
        val done = sampleJob(
            "job-persisted",
            state = JobState.DONE,
            outputUris = listOf(seeded.uri!!.toString()),
        )
        val previous = harness.database.jobDao().byId(done.id)!!
        harness.database.jobDao().upsert(
            previous.copy(
                state = JobState.DONE.name,
                outputUrisJson = org.json.JSONArray(done.outputUris).toString(),
                detail = done.detail,
                progress = 1f,
                updatedAt = done.updatedAt,
                terminalAt = done.updatedAt,
                commitSequence = 1L,
                version = previous.version + 1L,
            ),
        )
        runBlocking { harness.repository.loadCache() }

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.DONE, storedJob(dir, done.id)!!.state)
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        assertEquals(1, ops.insertCount)
    }

    @Test
    fun partialReconciliationAndPreviousAttempt_neverDemotesDoneOrDeletesNewOutput() {
        val dir = tempFolder.newFolder("multi-attempt")
        val ops = TestPublicationStorageOps(dir)
        val initial = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-multi", state = JobState.FAILED)))
        val oldAttempt = seedTransaction(
            initial.journal,
            initial.publisher,
            ops,
            initial.stagingRoot,
            "job-multi",
            "tx-old",
            finalName = "video.mp4",
            phase = PublicationPhase.CREATED,
            createdAt = 1_000L,
        )
        val newAttempt = seedTransaction(
            initial.journal,
            initial.publisher,
            ops,
            initial.stagingRoot,
            "job-multi",
            "tx-new",
            finalName = "video.mp4",
            createdAt = 2_000L,
        )
        val done = sampleJob(
            "job-multi",
            state = JobState.DONE,
            outputUris = listOf(newAttempt.uri!!.toString()),
        )
        val existing = initial.database.jobDao().byId(done.id)!!
        initial.database.jobDao().upsert(
            existing.copy(
                state = JobState.DONE.name,
                outputUrisJson = org.json.JSONArray(done.outputUris).toString(),
                terminalAt = System.currentTimeMillis(),
                commitSequence = 1L,
                version = existing.version + 1L,
            ),
        )
        runBlocking { initial.repository.loadCache() }

        val fresh = initial.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.DONE, storedJob(dir, done.id)!!.state)
        assertEquals(
            "A transaction owned by a previous batch must remain preserved, not reconciled; current=${fresh.journal.unreconciledEntries().map { it.transactionId to it.status }}",
            1,
            fresh.journal.unreconciledEntries().size,
        )
        assertTrue("The finalized current output must be preserved", ops.fileExists(newAttempt.uri!!))
        assertTrue("A previous attempt's provider object must not be deleted", ops.fileExists(oldAttempt.uri!!))
    }

    @Test
    fun jobStoreRetentionKeepsLongRunningJobThatCompletesBeyond250Records() = runBlocking {
        val dir = tempFolder.newFolder("retention-manager")
        val ops = TestPublicationStorageOps(dir)
        val history = (1..280).map {
            sampleJob("history-$it", state = JobState.DONE, outputUris = emptyList(), createdAt = 1_000L + it)
        }
        val longRunning = sampleJob("long-running", state = JobState.DOWNLOADING, createdAt = 1L)
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val harness = createHarness(dir, ops, initialJobs = history + longRunning)
            val output = seedTransaction(
                harness.journal,
                harness.publisher,
                ops,
                harness.stagingRoot,
                longRunning.id,
                "tx-long-running",
                finalName = "long-running.mp4",
                size = 64L,
                writtenSize = 64L,
            )
            val committed = harness.manager.commitTerminalPublishedJob(
                jobId = longRunning.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 64L,
                reportedTotalBytes = 64L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 64L,
                outputUris = listOf(output.uri!!.toString()),
                transactionIds = setOf(output.entry.transactionId),
                attemptNumber = longRunning.attempt,
                batchId = output.entry.batchId,
            )
            assertTrue(committed)
            assertEquals(JobState.DONE, storedJob(dir, longRunning.id)!!.state)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Fresh-runtime recovery and MediaStore verification
    // ---------------------------------------------------------------------------------------

    @Test
    fun completedProviderCopyBeforeJournalUpdate_isVerifiedAndRecovered() {
        val dir = tempFolder.newFolder("completed-copy")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-complete-copy")))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-complete-copy",
            "tx-complete-copy",
            finalName = "video.mp4",
            phase = PublicationPhase.CREATED,
        )

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.DONE, storedJob(dir, "job-complete-copy")!!.state)
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        assertTrue("The completed provider output must be preserved", ops.fileExists(seeded.uri!!))
    }

    @Test
    fun mediaStorePendingRowDuringRecovery_isFinalizedAndVerified() {
        val dir = tempFolder.newFolder("pending-row")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-pending")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-pending",
            "tx-pending",
            finalName = "video.mp4",
            phase = PublicationPhase.COPIED,
        )

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        val persisted = storedJob(dir, "job-pending")!!
        assertEquals(JobState.DONE, persisted.state)
        assertEquals(1, persisted.outputUris.size)
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun mediaStoreMissingPendingColumnOnApi29_isNotAcceptedAsFinalized() {
        val dir = tempFolder.newFolder("missing-pending-column")
        val ops = TestPublicationStorageOps(dir).apply { omitPendingColumn = true }
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-column")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-column",
            "tx-column",
            finalName = "video.mp4",
            finalizeProvider = false,
        )

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertFalse(
            "A missing IS_PENDING column must not be read as finalized",
            storedJob(dir, "job-column")!!.state == JobState.DONE,
        )
        assertEquals(1, fresh.journal.unreconciledEntries().size)
    }

    @Test
    fun mediaStoreQueryException_retainsRecoveryStateUntilProviderReturns() {
        val dir = tempFolder.newFolder("query-exception")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-query")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-query",
            "tx-query",
            finalName = "video.mp4",
        )

        ops.failProviderQuery = true
        val failing = harness.restart()
        failing.manager.reconcilePublicationJournalOnStartup()
        assertFalse(storedJob(dir, "job-query")!!.state == JobState.DONE)
        assertEquals(1, failing.journal.unreconciledEntries().size)
        assertTrue(ops.mediaItems.values.any { it.displayName == "video.mp4" })

        ops.failProviderQuery = false
        val recovering = failing.restart()
        recovering.manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, storedJob(dir, "job-query")!!.state)
        assertTrue(recovering.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun mediaStoreZeroRowUpdate_isRetriedAndEventuallyFinalized() {
        val dir = tempFolder.newFolder("zero-row")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-zero")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-zero",
            "tx-zero",
            finalName = "video.mp4",
            phase = PublicationPhase.COPIED,
        )
        ops.updateAffectedRows = 0

        val failing = harness.restart()
        failing.manager.reconcilePublicationJournalOnStartup()
        assertFalse(storedJob(dir, "job-zero")!!.state == JobState.DONE)
        assertEquals(1, failing.journal.unreconciledEntries().size)

        ops.updateAffectedRows = null
        val recovering = failing.restart()
        recovering.manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, storedJob(dir, "job-zero")!!.state)
        assertTrue(recovering.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun mediaStoreIncorrectDisplayName_isRetainedAndNeverDeleted() {
        val dir = tempFolder.newFolder("wrong-name")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-name")))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-name",
            "tx-name",
            finalName = "video.mp4",
        )
        ops.mediaItems.getValue(seeded.uri!!.toString()).displayName = "user-document.mp4"

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertTrue("A mismatched row must never be deleted", ops.fileExists(seeded.uri))
        assertEquals(1, fresh.journal.unreconciledEntries().size)
        assertFalse(storedJob(dir, "job-name")!!.state == JobState.DONE)
    }

    @Test
    fun api26To28FinalizationUsesLegacyNameSemantics() {
        val dir = tempFolder.newFolder("api28")
        val ops = TestPublicationStorageOps(dir, sdkInt = 28)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-api28")), sdkInt = 28)
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-api28",
            "tx-api28",
            finalName = "video.mp4",
            phase = PublicationPhase.COPIED,
        )

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(JobState.DONE, storedJob(dir, "job-api28")!!.state)
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Cleanup failure and URI-discovery ambiguity
    // ---------------------------------------------------------------------------------------

    @Test
    fun failedDeletion_keepsCleanupPending_andRetriesInFreshRuntime() {
        val dir = tempFolder.newFolder("delete-retry")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-delete")))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-delete",
            "tx-delete",
            finalName = "video.mp4",
            phase = PublicationPhase.CREATED,
            writtenSize = 64,
        )
        ops.failDelete = true

        val failing = harness.restart()
        failing.manager.reconcilePublicationJournalOnStartup()
        assertEquals(
            PublicationTransactionStatus.CLEANUP_PENDING,
            failing.journal.entriesForJob("job-delete").single().status,
        )
        assertTrue(ops.fileExists(seeded.uri!!))
        assertFalse(storedJob(dir, "job-delete")!!.state == JobState.DONE)

        ops.failDelete = false
        val recovering = failing.restart()
        recovering.manager.reconcilePublicationJournalOnStartup()
        assertFalse("The transient pending object must be deleted on retry", ops.fileExists(seeded.uri))
        assertTrue(recovering.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun deletionException_isTreatedAsRetryRequired() {
        val dir = tempFolder.newFolder("delete-throws")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-throw")))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-throw",
            "tx-throw",
            phase = PublicationPhase.CREATED,
            writtenSize = 64,
        )
        ops.throwOnDelete = true

        val failing = harness.restart()
        failing.manager.reconcilePublicationJournalOnStartup()
        assertTrue(ops.fileExists(seeded.uri!!))
        assertEquals(1, failing.journal.unreconciledEntries().size)

        ops.throwOnDelete = false
        ops.failDelete = false
        val recovering = failing.restart()
        recovering.manager.reconcilePublicationJournalOnStartup()
        assertFalse(ops.fileExists(seeded.uri!!))
        assertTrue(recovering.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun safPermissionRevoked_retainsJournalAndPendingObject() {
        val dir = tempFolder.newFolder("saf-revoked")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-saf")))
        val seeded = seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-saf",
            "tx-saf",
            providerType = PublicationProviderType.SAF,
            phase = PublicationPhase.CREATED,
        )
        ops.permissionRevoked = true

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertTrue("Revoked permission must never be treated as absence", ops.fileExists(seeded.uri!!))
        assertEquals(1, fresh.journal.unreconciledEntries().size)
    }

    @Test
    fun pendingLookupFailure_retainsJournalWithoutDestructiveAction() {
        val dir = tempFolder.newFolder("lookup-unknown")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-lookup")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-lookup",
            "tx-lookup",
            providerType = PublicationProviderType.SAF,
            phase = PublicationPhase.CREATED,
            createProviderObject = false,
        )
        ops.failProviderQuery = true

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(1, fresh.journal.unreconciledEntries().size)
        assertFalse(storedJob(dir, "job-lookup")!!.state == JobState.DONE)
    }

    @Test
    fun genuinelyAbsentPendingObject_isReconciledWithoutDeletion() {
        val dir = tempFolder.newFolder("absent")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-absent")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-absent",
            "tx-absent",
            phase = PublicationPhase.COPIED,
            createProviderObject = false,
        )
        // Record a provider URI that no longer exists, as if the object vanished.
        harness.journal.updateProviderCreated("tx-absent", "content://media/external/downloads/9999")

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        assertTrue(ops.deletedUris.isEmpty())
        assertEquals(JobState.FAILED, storedJob(dir, "job-absent")!!.state)
    }

    @Test
    fun uriPointingAtAnotherRowIsNeverDeletedAndRetainsTheJournal() {
        val dir = tempFolder.newFolder("other-row")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-other")))
        val entry = pendingEntry(
            "job-other",
            "tx-other",
            harness.stagingRoot,
            finalName = "video.mp4",
        )
        harness.journal.recordIntent(entry)
        val userUri = ops.insertMediaStore(
            Uri.parse(MEDIA_COLLECTION),
            ContentValues(),
            displayName = "user-file.mp4",
            isPending = false,
        )!!
        harness.journal.updateProviderCreated("tx-other", userUri.toString())

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertTrue("An unrelated provider row must never be deleted", ops.fileExists(userUri))
        assertEquals(1, fresh.journal.unreconciledEntries().size)
    }

    // ---------------------------------------------------------------------------------------
    // Strict journal validation and quarantine
    // ---------------------------------------------------------------------------------------

    @Test
    fun unknownJournalSchema_isPreservedAndRecoveryRefused() {
        val dir = tempFolder.newFolder("unknown-schema")
        val ops = TestPublicationStorageOps(dir)
        val journalFile = File(dir, "publication_journal.json")
        val original = """{"schemaVersion":99,"entries":[]}"""
        journalFile.writeText(original)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-schema")))

        harness.manager.reconcilePublicationJournalOnStartup()

        val load = harness.journal.loadWithStatus()
        assertEquals(PublicationJournalLoadState.UNSUPPORTED_SCHEMA, load.state)
        assertEquals(original, journalFile.readText())
        assertNotNull(load.recoveryCopy)
        assertEquals(JobState.POSTPROCESSING, storedJob(dir, "job-schema")!!.state)
    }

    @Test
    fun missingPendingFilename_isCorruptAndFailClosed() {
        val journalFile = File(tempFolder.root, "missing-pending.json")
        journalFile.writeText(
            """
            {"schemaVersion":1,"entries":[{
              "transactionId":"tx-a","jobId":"job-a","outputIndex":0,"totalOutputs":1,
              "intendedFilename":"a.mp4","stagingPath":"${File(tempFolder.root, "a.mp4").absolutePath}",
              "expectedSizeBytes":10,"providerType":"MEDIA_STORE",
              "destinationIdentity":"Downloads/VRKA","publicationPhase":"INTENT_RECORDED",
              "copiedBytes":0,"status":"PENDING","createdAt":1,"updatedAt":1
            }]}
            """.trimIndent(),
        )

        val result = PublicationJournal(journalFile).loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertFalse(result.isolated)
        assertTrue(result.entries.isEmpty())
        assertNotNull(result.recoveryCopy)
    }

    @Test
    fun invalidTimestampsAreRejected() {
        val journalFile = File(tempFolder.root, "bad-time.json")
        val now = System.currentTimeMillis()
        journalFile.writeText(
            """
            {"schemaVersion":1,"entries":[{
              "transactionId":"tx-a","jobId":"job-a","outputIndex":0,"totalOutputs":1,
              "intendedFilename":"a.mp4","pendingFilename":".vrka_pending_tx-a_a.mp4",
              "stagingPath":"${File(tempFolder.root, "a.mp4").absolutePath}",
              "expectedSizeBytes":10,"providerType":"MEDIA_STORE",
              "destinationIdentity":"Downloads/VRKA","publicationPhase":"INTENT_RECORDED",
              "copiedBytes":0,"status":"PENDING","createdAt":$now,"updatedAt":${now - 10}
            }]}
            """.trimIndent(),
        )

        val result = PublicationJournal(journalFile).loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertFalse(result.isolated)
    }

    @Test
    fun duplicateTransactionIdsFailClosed() {
        val journalFile = File(tempFolder.root, "duplicate-tx.json")
        val staging = File(tempFolder.root, "a.mp4").absolutePath
        fun record(jobId: String) = """
            {"transactionId":"tx-dup","jobId":"$jobId","outputIndex":0,"totalOutputs":1,
             "intendedFilename":"a.mp4","pendingFilename":".vrka_pending_tx-dup_a.mp4",
             "stagingPath":"$staging","expectedSizeBytes":10,"providerType":"MEDIA_STORE",
             "destinationIdentity":"Downloads/VRKA","publicationPhase":"INTENT_RECORDED",
             "copiedBytes":0,"status":"PENDING","createdAt":1,"updatedAt":1}
        """.trimIndent()
        journalFile.writeText("""{"schemaVersion":1,"entries":[${record("job-a")},${record("job-b")}]}""")

        val result = PublicationJournal(journalFile).loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertFalse(result.isolated)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun mixedValidAndInvalidJobs_isolateTheValidJobOnly() {
        val dir = tempFolder.newFolder("isolation")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-good"), sampleJob("job-bad")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-good",
            "tx-good",
            finalName = "good.mp4",
        )

        val journalObj = JSONObject(File(dir, "publication_journal.json").readText())
        journalObj.getJSONArray("entries").put(
            JSONObject()
                .put("transactionId", "tx-bad")
                .put("jobId", "job-bad")
                .put("outputIndex", 1)
                .put("totalOutputs", 1)
                .put("intendedFilename", "bad.mp4")
                .put("pendingFilename", ".vrka_pending_tx-bad_bad.mp4")
                .put("stagingPath", File(dir, "bad.mp4").absolutePath)
                .put("expectedSizeBytes", 10)
                .put("providerType", "MEDIA_STORE")
                .put("destinationIdentity", "Downloads/VRKA")
                .put("publicationPhase", "INTENT_RECORDED")
                .put("copiedBytes", 0)
                .put("status", "PENDING")
                .put("createdAt", System.currentTimeMillis())
                .put("updatedAt", System.currentTimeMillis()),
        )
        File(dir, "publication_journal.json").writeText(journalObj.toString())

        val fresh = harness.restart()
        val load = fresh.journal.loadWithStatus()
        assertEquals(PublicationJournalLoadState.CORRUPT, load.state)
        assertTrue(load.isolated)
        assertEquals(listOf("job-good"), load.entries.map { it.jobId }.distinct())

        fresh.manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, storedJob(dir, "job-good")!!.state)
        assertFalse(storedJob(dir, "job-bad")!!.state == JobState.DONE)
        assertTrue(
            "The corrupt original must be preserved",
            (dir.listFiles() ?: emptyArray()).any { it.name.startsWith("publication_journal.corrupt-") },
        )
    }

    @Test
    fun repeatedQuarantinePreservesBoundedCopiesAndRecoversAfterCleanJournal() {
        val dir = tempFolder.newFolder("quarantine-repeat")
        val journalFile = File(dir, "publication_journal.json")
        repeat(5) {
            journalFile.writeText("""{"schemaVersion":1,"entries":[{"jobId":"job-x"}]}""")
            PublicationJournal(journalFile).loadWithStatus()
            Thread.sleep(5)
        }
        val copies = (dir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("publication_journal.corrupt-") }
        assertTrue("Corrupt snapshots must remain bounded", copies.size <= 3)
        assertTrue(copies.isNotEmpty())

        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops, initialJobs = listOf(sampleJob("job-clean")))
        seedTransaction(
            harness.journal,
            harness.publisher,
            ops,
            harness.stagingRoot,
            "job-clean",
            "tx-clean",
            finalName = "clean.mp4",
        )
        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()
        assertEquals(JobState.DONE, storedJob(dir, "job-clean")!!.state)
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun markReconciledIsScopedToExactTransactions() {
        val journalFile = File(tempFolder.root, "scoped.json")
        val journal = PublicationJournal(journalFile)
        val now = System.currentTimeMillis()
        val base = PublicationEntry(
            transactionId = "tx-old",
            jobId = "job-scope",
            outputIndex = 0,
            totalOutputs = 1,
            intendedFilename = "a.mp4",
            stagingPath = File(tempFolder.root, "a.mp4").absolutePath,
            expectedSizeBytes = 10,
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.INTENT,
            createdAt = now,
            updatedAt = now,
        )
        journal.recordIntent(base)
        journal.recordIntent(
            base.copy(
                transactionId = "tx-new",
                pendingFilename = PublicationUriRules.pendingNameFor("tx-new", "a.mp4"),
                createdAt = now + 1,
                updatedAt = now + 1,
            ),
        )
        journal.markFailed("tx-old", "earlier attempt failed")
        journal.updateProviderCreated("tx-new", "content://media/external/downloads/7")
        journal.updateDataCopied("tx-new", 10)
        journal.updateValidated("tx-new")
        journal.updateFinalized("tx-new")

        journal.markReconciled(setOf("tx-new"))

        val byId = journal.allEntries().associateBy { it.transactionId }
        assertEquals(PublicationTransactionStatus.RECONCILED, byId.getValue("tx-new").status)
        assertEquals(
            "A previous attempt must not be retired by a successful retry",
            PublicationTransactionStatus.FAILED,
            byId.getValue("tx-old").status,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Staging cleanup durability (PUB-05)
    // ---------------------------------------------------------------------------------------

    @Test
    fun stagingCleanupFailure_isRecordedAndRetriedOnFreshRuntime() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("staging-retry")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-staging")
            var allowDelete = false
            val harness = createHarness(
                dir,
                ops,
                initialJobs = listOf(job),
                stagingDelete = { directory -> if (allowDelete) directory.deleteRecursively() else false },
            )
            val stagingDir = File(harness.stagingRoot, job.id).apply { mkdirs() }
            val stagingFile = File(stagingDir, "video.mp4").apply { writeBytes(ByteArray(16)) }
            val seeded = seedTransaction(
                harness.journal,
                harness.publisher,
                ops,
                harness.stagingRoot,
                job.id,
                "tx-staging",
                finalName = "video.mp4",
                size = 16,
            )

            val committed = harness.manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 16L,
                reportedTotalBytes = 16L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 16L,
                outputUris = listOf(seeded.uri!!.toString()),
                transactionIds = setOf("tx-staging"),
                attemptNumber = job.attempt,
                batchId = seeded.entry.batchId,
            )
            assertTrue("The publication is durable even when staging cleanup fails", committed)
            assertTrue(stagingFile.exists())
            assertEquals(listOf(job.id), harness.cleanupStore.pending().map { it.jobId })

            allowDelete = true
            val fresh = harness.restart(stagingDelete = { directory -> directory.deleteRecursively() })
            fresh.manager.reconcilePublicationJournalOnStartup()

            assertFalse("A recorded cleanup-pending job is retried at startup", stagingFile.exists())
            assertTrue(fresh.cleanupStore.pending().isEmpty())
            assertEquals(JobState.DONE, storedJob(dir, job.id)!!.state)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun stagingIsNotDeletedWhileTheJournalRemainsUnreconciled() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("staging-retained")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-retain")
            val harness = createHarness(dir, ops, initialJobs = listOf(job))
            val stagingDir = File(harness.stagingRoot, job.id).apply { mkdirs() }
            val stagingFile = File(stagingDir, "video.mp4").apply { writeBytes(ByteArray(16)) }
            val seeded = seedTransaction(
                harness.journal,
                harness.publisher,
                ops,
                harness.stagingRoot,
                job.id,
                "tx-retain",
                finalName = "video.mp4",
                size = 16,
            )

            installRejectJobUpsert(harness.database)
            val committed = harness.manager.commitTerminalPublishedJob(
                jobId = job.id,
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 16L,
                reportedTotalBytes = 16L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 16L,
                outputUris = listOf(seeded.uri!!.toString()),
                transactionIds = setOf("tx-retain"),
            )
            assertFalse(committed)
            assertTrue(stagingFile.exists())
            assertTrue(harness.cleanupStore.pending().isEmpty())
            removeRejectJobUpsert(harness.database)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Concurrency and cancellation
    // ---------------------------------------------------------------------------------------

    @Test
    fun concurrentTerminalCommitsCannotLoseTheNewerSnapshot() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("concurrent")
            val ops = TestPublicationStorageOps(dir)
            val first = sampleJob("job-first", createdAt = 1L)
            val second = sampleJob("job-second", createdAt = 2L)
            val harness = createHarness(dir, ops, initialJobs = listOf(first, second))
            val firstOutput = seedTransaction(
                harness.journal, harness.publisher, ops, harness.stagingRoot,
                first.id, "tx-concurrent-first", finalName = "first.mp4", size = 1L, writtenSize = 1L,
            )
            val secondOutput = seedTransaction(
                harness.journal, harness.publisher, ops, harness.stagingRoot,
                second.id, "tx-concurrent-second", finalName = "second.mp4", size = 1L, writtenSize = 1L,
            )
            val transactionEntered = CountDownLatch(1)
            val releaseTransaction = CountDownLatch(1)
            val lockTransaction = async(Dispatchers.IO) {
                harness.database.withTransaction {
                    transactionEntered.countDown()
                    releaseTransaction.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("The test must hold the Room transaction", transactionEntered.await(5, TimeUnit.SECONDS))
            val firstResult = AtomicBoolean(false)
            val secondResult = AtomicBoolean(false)
            val callsStarted = CountDownLatch(2)
            val firstThread = Thread {
                callsStarted.countDown()
                firstResult.set(
                    runBlocking {
                        harness.manager.commitTerminalPublishedJob(
                            jobId = first.id,
                            detail = "Saved",
                            downloadedBytes = 1L,
                            reportedTotalBytes = 1L,
                            reportedTotalIsExact = true,
                            actualFinalFileBytes = 1L,
                            outputUris = listOf(firstOutput.uri!!.toString()),
                            transactionIds = setOf(firstOutput.entry.transactionId),
                            attemptNumber = first.attempt,
                            batchId = firstOutput.entry.batchId,
                        )
                    },
                )
            }.apply { start() }
            val secondThread = Thread {
                callsStarted.countDown()
                secondResult.set(
                    runBlocking {
                        harness.manager.commitTerminalPublishedJob(
                            jobId = second.id,
                            detail = "Saved",
                            downloadedBytes = 1L,
                            reportedTotalBytes = 1L,
                            reportedTotalIsExact = true,
                            actualFinalFileBytes = 1L,
                            outputUris = listOf(secondOutput.uri!!.toString()),
                            transactionIds = setOf(secondOutput.entry.transactionId),
                            attemptNumber = second.attempt,
                            batchId = secondOutput.entry.batchId,
                        )
                    },
                )
            }.apply { start() }

            assertTrue("Both commits must start while the Room transaction is held", callsStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("The first commit must wait for Room ownership", firstResult.get())
            assertFalse("The second commit must wait for Room ownership", secondResult.get())
            releaseTransaction.countDown()
            firstThread.join(10_000)
            secondThread.join(10_000)
            lockTransaction.await()

            assertTrue("The first Room terminal transaction must commit", firstResult.get())
            assertTrue("The second Room terminal transaction must commit", secondResult.get())
            val durable = storeJobs(dir).associateBy { it.id }
            assertEquals(JobState.DONE, durable.getValue(first.id).state)
            assertEquals(JobState.DONE, durable.getValue(second.id).state)
            assertNotEquals(
                "Concurrent Room commits must retain separate durable commit ordering",
                harness.database.jobDao().byId(first.id)!!.commitSequence,
                harness.database.jobDao().byId(second.id)!!.commitSequence,
            )
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun cancellationDuringTerminalPersistenceStillCompletesDurably() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("cancel")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-cancel")
            val harness = createHarness(dir, ops, initialJobs = listOf(job))
            val output = seedTransaction(
                harness.journal,
                harness.publisher,
                ops,
                harness.stagingRoot,
                job.id,
                "tx-cancel",
                finalName = "cancel.mp4",
                size = 1L,
                writtenSize = 1L,
            )
            val transactionEntered = CountDownLatch(1)
            val releaseTransaction = CountDownLatch(1)
            val lockTransaction = async(Dispatchers.IO) {
                harness.database.withTransaction {
                    transactionEntered.countDown()
                    releaseTransaction.await(5, TimeUnit.SECONDS)
                }
            }
            assertTrue("The test must hold the Room transaction", transactionEntered.await(5, TimeUnit.SECONDS))

            val work = async(start = CoroutineStart.UNDISPATCHED) {
                harness.manager.commitTerminalPublishedJob(
                    jobId = job.id,
                    detail = "Saved",
                    downloadedBytes = 1L,
                    reportedTotalBytes = 1L,
                    reportedTotalIsExact = true,
                    actualFinalFileBytes = 1L,
                    outputUris = listOf(output.uri!!.toString()),
                    transactionIds = setOf(output.entry.transactionId),
                    attemptNumber = job.attempt,
                    batchId = output.entry.batchId,
                )
            }
            Thread.sleep(100)
            work.cancel()
            releaseTransaction.countDown()
            runCatching { work.await() }
            lockTransaction.await()

            assertEquals(
                "Cancellation during the Room write must not interrupt the durable terminal commit",
                JobState.DONE,
                storedJob(dir, job.id)!!.state,
            )
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun crashBetweenPublishAndTerminalCommit_freshRuntimeRecoversWithoutDuplicates() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val dir = tempFolder.newFolder("crash-before-commit")
            val ops = TestPublicationStorageOps(dir)
            val job = sampleJob("job-crash")
            val harness = createHarness(dir, ops, initialJobs = listOf(job))
            val stagingFile = File(harness.stagingRoot, "job-crash/video.mp4").apply {
                parentFile!!.mkdirs()
                writeBytes(ByteArray(200))
            }

            harness.publisher.publishAll(
                files = listOf(stagingFile to "video.mp4"),
                destinationTreeUri = null,
                jobId = job.id,
            )
            assertTrue("Staging survives the crash window before terminal commit", stagingFile.exists())
            assertEquals(1, ops.insertCount)

            // Simulate process death: discard the manager, publisher, store, and journal instances.
            val fresh = harness.restart()
            fresh.manager.reconcilePublicationJournalOnStartup()

            assertEquals(1, ops.insertCount)
            assertEquals(JobState.DONE, storedJob(dir, job.id)!!.state)
            assertTrue(fresh.journal.unreconciledEntries().isEmpty())
            assertFalse(stagingFile.exists())
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    @Test
    fun publishFailureWithUndeletableObject_marksCleanupPendingAndFreshRecoveryRetries() {
        val dir = tempFolder.newFolder("publish-cleanup")
        val ops = TestPublicationStorageOps(dir)
        val harness = createHarness(dir, ops)
        ops.byteLimit = 10L
        ops.failDelete = true
        val stagingFile = File(dir, "big.mp4").apply { writeBytes(ByteArray(100)) }

        var failed = false
        try {
            harness.publisher.publishAll(
                files = listOf(stagingFile to "big.mp4"),
                destinationTreeUri = null,
                jobId = "job-publish-fail",
            )
        } catch (_: IOException) {
            failed = true
        }
        assertTrue("The partial copy must fail publication", failed)
        assertEquals(
            PublicationTransactionStatus.CLEANUP_PENDING,
            harness.journal.entriesForJob("job-publish-fail").single().status,
        )
        assertEquals(1, ops.mediaItems.size)

        ops.byteLimit = null
        ops.failDelete = false
        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertTrue("The retried cleanup must remove the partial provider object", ops.mediaItems.isEmpty())
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        assertEquals(JobState.FAILED, storedJob(dir, "job-publish-fail")!!.state)
    }

    @Test
    fun verifiedBytesNeverDeletedOnFinalizationFailure_retainedInCopiedAndRecoveredOnStartup() {
        val dir = tempFolder.newFolder("finalize-fail-preserve")
        val ops = TestPublicationStorageOps(dir)
        val job = sampleJob("job-finalize-preserve")
        val harness = createHarness(dir, ops, initialJobs = listOf(job))
        val stagingFile = File(dir, "final_video.mp4").apply { writeBytes(ByteArray(1024) { 0x3c }) }

        // Simulate finalization update returning 0 affected rows (e.g. OEM MediaProvider glitch)
        ops.updateAffectedRows = 0
        var failed = false
        try {
            harness.publisher.publishAll(
                files = listOf(stagingFile to "final_video.mp4"),
                destinationTreeUri = null,
                jobId = "job-finalize-preserve",
            )
        } catch (_: IOException) {
            failed = true
        }
        assertTrue("Publication must fail when finalization update affects 0 rows", failed)

        // INVARIANT 1: Verified bytes must NEVER be deleted because finalization failed
        assertEquals("Completed, verified bytes must remain in storage", 1, ops.mediaItems.size)

        // INVARIANT 2: Transaction is retained in COPIED phase with exact providerUri
        val entries = harness.journal.entriesForJob("job-finalize-preserve")
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(PublicationPhase.COPIED, entry.publicationPhase)
        assertNotNull(entry.providerUri)

        // Recovery on startup: Provider issue resolved, recovery successfully finalizes without redownload
        ops.updateAffectedRows = null
        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals("Job must be promoted to DONE via journal recovery", JobState.DONE, storedJob(dir, "job-finalize-preserve")!!.state)
        assertEquals("Output file is preserved without data loss or duplication", 1, ops.mediaItems.size)
        assertTrue("All journal entries reconciled", fresh.journal.unreconciledEntries().isEmpty())
    }

    @Test
    fun finalizedJournalEntryWithMissingProviderRow_isRejectedAndDemotesDone() {
        val dir = tempFolder.newFolder("missing-row")
        val ops = TestPublicationStorageOps(dir)
        val uri = "content://media/external/downloads/424242"
        val job = sampleJob("job-missing", state = JobState.DONE, outputUris = listOf(uri))
        val harness = createHarness(dir, ops, initialJobs = listOf(job))
        val entry = pendingEntry("job-missing", "tx-missing", harness.stagingRoot, finalName = "video.mp4")
        harness.journal.recordIntent(entry)
        harness.journal.updateProviderCreated("tx-missing", uri)
        harness.journal.updateDataCopied("tx-missing", 128)
        harness.journal.updateValidated("tx-missing")
        harness.journal.updateFinalized("tx-missing", uri)

        val fresh = harness.restart()
        fresh.manager.reconcilePublicationJournalOnStartup()

        assertEquals(
            "A DONE record whose provider row vanished must be demoted",
            JobState.FAILED,
            storedJob(dir, "job-missing")!!.state,
        )
        assertTrue(fresh.journal.unreconciledEntries().isEmpty())
        assertTrue(ops.deletedUris.isEmpty())
    }

    @Test
    fun incompleteBatchTotalsFailClosed() {
        val journalFile = File(tempFolder.root, "bad-totals.json")
        val staging = File(tempFolder.root, "a.mp4").absolutePath
        fun record(index: Int) = """
            {"transactionId":"tx-$index","jobId":"job-total","outputIndex":$index,"totalOutputs":3,
             "intendedFilename":"a.mp4","pendingFilename":".vrka_pending_tx-${index}_a.mp4",
             "stagingPath":"$staging","expectedSizeBytes":10,"providerType":"MEDIA_STORE",
             "destinationIdentity":"Downloads/VRKA","publicationPhase":"INTENT_RECORDED",
             "copiedBytes":0,"status":"PENDING","createdAt":1,"updatedAt":1}
        """.trimIndent()
        journalFile.writeText("""{"schemaVersion":1,"entries":[${record(0)},${record(1)}]}""")

        val result = PublicationJournal(journalFile).loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertFalse(result.isolated)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun safProviderUriOutsideDeclaredTreeFailsClosed() {
        val journalFile = File(tempFolder.root, "outside-tree.json")
        val staging = File(tempFolder.root, "a.mp4").absolutePath
        journalFile.writeText(
            """
            {"schemaVersion":1,"entries":[{
              "transactionId":"tx-tree","jobId":"job-tree","outputIndex":0,"totalOutputs":1,
              "intendedFilename":"a.mp4","pendingFilename":".vrka_pending_tx-tree_a.mp4",
              "stagingPath":"$staging","expectedSizeBytes":10,"providerType":"SAF",
              "destinationIdentity":"content://saf/tree/root",
              "providerUri":"content://saf/tree/other/document/9",
              "publicationPhase":"PROVIDER_OBJECT_CREATED","copiedBytes":0,
              "status":"IN_PROGRESS","createdAt":1,"updatedAt":1
            }]}
            """.trimIndent(),
        )

        val result = PublicationJournal(journalFile).loadWithStatus()

        assertEquals(PublicationJournalLoadState.CORRUPT, result.state)
        assertFalse(result.isolated)
        assertTrue(result.entries.isEmpty())
        assertNotNull(result.recoveryCopy)
    }
}
