package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Proxy

class PublicationJournalRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------------------------------------------------------------------------------------------
    // Test Doubles & Controlled Operations (Real Production-Path Testing)
    // ---------------------------------------------------------------------------------------------

    class StoredMediaStoreItem(
        val uri: Uri,
        val collection: Uri,
        val values: ContentValues,
        var isPending: Boolean = true,
        var displayName: String = "",
        val file: File,
    )

    class StoredSafDoc(
        val uri: Uri,
        val tree: Uri,
        var displayName: String,
        val mimeType: String,
        val file: File,
    )

    class TestPublicationStorageOps(private val tempDir: File) : PublicationStorageOps {
        val mediaStoreItems = mutableMapOf<String, StoredMediaStoreItem>()
        val safDocs = mutableMapOf<String, StoredSafDoc>()
        private var nextId = 1000L

        var failInsertMediaStore: Boolean = false
        var crashAfterMediaStoreInsertBeforeUriReturned: Boolean = false
        var failCreateSafDoc: Boolean = false
        var crashAfterSafCreateBeforeUriReturned: Boolean = false
        var outputStreamByteLimit: Long? = null
        var failMediaStoreUpdate: Boolean = false
        var mediaStoreUpdateAffectedRows: Int? = null
        var failSafRename: Boolean = false
        var failDelete: Boolean = false
        val deletedUris = mutableListOf<Uri>()

        private fun uriKey(uri: Uri): String = uri.toString()

        override fun insertMediaStore(
            collection: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Uri? {
            if (failInsertMediaStore) return null
            val id = nextId++
            val uri = Uri.parse("content://media/external/downloads/$id")
            val name = displayName ?: runCatching { values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }.getOrNull() ?: "unnamed"
            val pending = isPending ?: (runCatching { values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) }.getOrNull() == 1)
            val destFile = File(tempDir, "ms_$id.tmp").apply { createNewFile() }
            val item = StoredMediaStoreItem(uri, collection, ContentValues(values), pending, name, destFile)
            mediaStoreItems[uriKey(uri)] = item
            if (crashAfterMediaStoreInsertBeforeUriReturned) {
                throw RuntimeException("Crash immediately after provider insert before URI returned")
            }
            return uri
        }

        override fun openOutputStream(uri: Uri, mode: String): OutputStream? {
            val key = uriKey(uri)
            val file = mediaStoreItems[key]?.file ?: safDocs[key]?.file ?: return null
            val baseOut = file.outputStream()
            val limit = outputStreamByteLimit
            return if (limit != null) {
                object : OutputStream() {
                    private var bytesWritten = 0L
                    override fun write(b: Int) {
                        if (bytesWritten >= limit) throw IOException("Simulated I/O failure after $bytesWritten bytes")
                        baseOut.write(b)
                        bytesWritten++
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        val remaining = limit - bytesWritten
                        if (remaining <= 0) throw IOException("Simulated I/O failure after $bytesWritten bytes")
                        val toWrite = minOf(len.toLong(), remaining).toInt()
                        baseOut.write(b, off, toWrite)
                        bytesWritten += toWrite
                        if (toWrite < len) throw IOException("Simulated I/O failure during write after $bytesWritten bytes")
                    }
                    override fun flush() = baseOut.flush()
                    override fun close() = baseOut.close()
                }
            } else {
                baseOut
            }
        }

        override fun updateMediaStore(
            uri: Uri,
            values: ContentValues,
            displayName: String?,
            isPending: Boolean?,
        ): Int {
            if (failMediaStoreUpdate) return 0
            val item = mediaStoreItems[uriKey(uri)] ?: return 0
            val name = displayName ?: runCatching { values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) }.getOrNull()
            val pending = isPending ?: if (runCatching { values.containsKey(MediaStore.MediaColumns.IS_PENDING) }.getOrDefault(false)) {
                runCatching { values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) }.getOrNull() == 1
            } else null
            if (mediaStoreUpdateAffectedRows != null) {
                val rows = mediaStoreUpdateAffectedRows!!
                if (rows > 0) {
                    if (pending != null) item.isPending = pending
                    if (name != null) item.displayName = name
                }
                return rows
            }
            if (pending != null) item.isPending = pending
            if (name != null) item.displayName = name
            return 1
        }

        override fun queryMediaStore(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Cursor? {
            val item = mediaStoreItems[uriKey(uri)] ?: return null
            val cols = projection ?: arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                OpenableColumns.SIZE,
            )
            val rowMap = mutableMapOf<String, Any?>()
            cols.forEach { col ->
                when (col) {
                    MediaStore.MediaColumns.DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> rowMap[col] = item.displayName
                    MediaStore.MediaColumns.IS_PENDING -> rowMap[col] = if (item.isPending) 1 else 0
                    OpenableColumns.SIZE -> rowMap[col] = item.file.length()
                    MediaStore.MediaColumns._ID -> rowMap[col] = uri.lastPathSegment?.toLongOrNull() ?: 1L
                    else -> rowMap[col] = null
                }
            }
            return createMockCursor(cols, listOf(rowMap))
        }

        override fun queryMediaStoreCollection(
            collection: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
        ): Cursor? {
            val cols = projection ?: arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.IS_PENDING,
                OpenableColumns.SIZE,
            )
            val rows = mutableListOf<Map<String, Any?>>()
            mediaStoreItems.values.filter { uriKey(it.collection) == uriKey(collection) }.forEach { item ->
                if (selection != null && selection.contains(MediaStore.MediaColumns.DISPLAY_NAME)) {
                    val arg = selectionArgs?.firstOrNull()
                    if (arg != null && item.displayName != arg) return@forEach
                }
                val rowMap = mutableMapOf<String, Any?>()
                cols.forEach { col ->
                    when (col) {
                        MediaStore.MediaColumns.DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> rowMap[col] = item.displayName
                        MediaStore.MediaColumns.IS_PENDING -> rowMap[col] = if (item.isPending) 1 else 0
                        OpenableColumns.SIZE -> rowMap[col] = item.file.length()
                        MediaStore.MediaColumns._ID -> rowMap[col] = item.uri.lastPathSegment?.toLongOrNull() ?: 1L
                        else -> rowMap[col] = null
                    }
                }
                rows += rowMap
            }
            return createMockCursor(cols, rows)
        }

        override fun deleteUri(uri: Uri): Boolean {
            if (failDelete) return false
            deletedUris += uri
            val key = uriKey(uri)
            val removedMs = mediaStoreItems.remove(key)
            removedMs?.file?.delete()
            val removedSaf = safDocs.remove(key)
            removedSaf?.file?.delete()
            return true
        }

        override fun fileExists(uri: Uri): Boolean {
            val key = uriKey(uri)
            return mediaStoreItems.containsKey(key) || safDocs.containsKey(key)
        }

        override fun getFileSize(uri: Uri): Long {
            val key = uriKey(uri)
            return mediaStoreItems[key]?.file?.length() ?: safDocs[key]?.file?.length() ?: -1L
        }

        override fun queryDisplayName(uri: Uri): String? {
            val key = uriKey(uri)
            return mediaStoreItems[key]?.displayName ?: safDocs[key]?.displayName
        }

        override fun findPendingMediaStoreUri(pendingFilename: String): Uri? =
            mediaStoreItems.values.firstOrNull { it.displayName == pendingFilename }?.uri

        override fun existingSafNames(tree: Uri): Set<String> =
            safDocs.values.filter { uriKey(it.tree) == uriKey(tree) }.map { it.displayName }.toSet()

        override fun findPendingSafUri(tree: Uri, pendingFilename: String): Uri? =
            safDocs.values.firstOrNull { uriKey(it.tree) == uriKey(tree) && it.displayName == pendingFilename }?.uri

        override fun createSafDocument(tree: Uri, mimeType: String, pendingFilename: String): Uri? {
            if (failCreateSafDoc) return null
            val id = nextId++
            val uri = Uri.parse("content://saf/tree/document/$id")
            val destFile = File(tempDir, "saf_$id.tmp").apply { createNewFile() }
            val doc = StoredSafDoc(uri, tree, pendingFilename, mimeType, destFile)
            safDocs[uriKey(uri)] = doc
            if (crashAfterSafCreateBeforeUriReturned) {
                throw RuntimeException("Crash immediately after SAF create before URI returned")
            }
            return uri
        }

        override fun renameSafDocument(uri: Uri, finalName: String): Uri? {
            if (failSafRename) return null
            val doc = safDocs[uriKey(uri)] ?: return null
            doc.displayName = finalName
            return uri
        }

        override fun deleteSafDocument(uri: Uri): Boolean = deleteUri(uri)
    }

    class TestJobStoreFileOps(
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

    companion object {
        fun createMockCursor(
            columns: Array<String>,
            rows: List<Map<String, Any?>>,
        ): Cursor {
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
                    "getColumnIndex" -> {
                        val col = args[0] as String
                        columns.indexOf(col)
                    }
                    "getString" -> {
                        val colIdx = args[0] as Int
                        val colName = columns[colIdx]
                        rows[rowIndex][colName]?.toString()
                    }
                    "getInt" -> {
                        val colIdx = args[0] as Int
                        val colName = columns[colIdx]
                        (rows[rowIndex][colName] as? Number)?.toInt() ?: 0
                    }
                    "getLong" -> {
                        val colIdx = args[0] as Int
                        val colName = columns[colIdx]
                        (rows[rowIndex][colName] as? Number)?.toLong() ?: 0L
                    }
                    "isNull" -> {
                        val colIdx = args[0] as Int
                        val colName = columns[colIdx]
                        rows[rowIndex][colName] == null
                    }
                    else -> null
                }
            } as Cursor
        }

        class TestContext(baseFilesDir: File) : android.content.ContextWrapper(null) {
            private val files = File(baseFilesDir, "files").apply { mkdirs() }
            private val cache = File(baseFilesDir, "cache").apply { mkdirs() }
            private val noBackup = File(baseFilesDir, "noBackup").apply { mkdirs() }

            private var editorProxy: android.content.SharedPreferences.Editor? = null

            init {
                editorProxy = Proxy.newProxyInstance(
                    android.content.SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences.Editor::class.java),
                ) { _, editorMethod, _ ->
                    when (editorMethod.name) {
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

        fun createTestContext(rootDir: File): Context = TestContext(rootDir)
    }

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
    ) = DownloadJob(
        id = id,
        request = sampleRequest(),
        state = state,
        title = "Test Media $id",
        detail = "Publishing to storage",
        publishStarted = publishStarted,
        outputUris = outputUris,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
    )

    private fun createHarness(
        testDir: File,
        storageOps: TestPublicationStorageOps,
        initialJobs: List<DownloadJob> = emptyList(),
        jobStoreFileOps: JobStoreFileOps = DefaultJobStoreFileOps,
    ): Pair<VrkaDownloadManager, PublicationJournal> {
        val jobsFile = File(testDir, "download_jobs.json")
        val journalFile = File(testDir, "pub_journal.json")
        val store = JobStore(jobsFile, jobStoreFileOps)
        if (initialJobs.isNotEmpty()) {
            store.save(initialJobs)
        }
        val journal = PublicationJournal(journalFile)
        val context = createTestContext(testDir)
        val settingsRepo = SettingsRepository(context, MutableStateFlow(AppSettings(outputTreeUri = "content://saf/tree")))
        val publisher = OutputPublisher(
            context = context,
            settingsRepository = settingsRepo,
            journal = journal,
            ops = storageOps,
        )
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = VrkaDownloadManager(
            context = context,
            settingsRepository = settingsRepo,
            scope = testScope,
            customStore = store,
            customPublisher = publisher,
        )
        return manager to journal
    }

    // ---------------------------------------------------------------------------------------------
    // Core Journal Roundtrip & Persistence Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun journalRoundtripPersistenceAndSchema() {
        val journalFile = File(tempFolder.root, "publication_journal.json")
        val journal = PublicationJournal(journalFile)

        val entry = PublicationEntry(
            transactionId = "tx-1",
            jobId = "job-1",
            outputIndex = 0,
            totalOutputs = 1,
            intendedFilename = "video.mp4",
            stagingPath = "/staging/job-1/video.mp4",
            expectedSizeBytes = 1048576L,
            providerType = PublicationProviderType.MEDIA_STORE,
            destinationIdentity = "Downloads/VRKA",
            publicationPhase = PublicationPhase.INTENT_RECORDED,
        )

        journal.recordIntent(entry)
        val loaded1 = journal.allEntries()
        assertEquals(1, loaded1.size)
        assertEquals("tx-1", loaded1[0].transactionId)
        assertEquals(PublicationPhase.INTENT_RECORDED, loaded1[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.PENDING, loaded1[0].status)
        assertEquals(".vrka_pending_tx-1_video.mp4", loaded1[0].pendingFilename)

        journal.updateProviderCreated("tx-1", "content://media/external/downloads/100")
        val loaded2 = journal.allEntries()
        assertEquals("content://media/external/downloads/100", loaded2[0].providerUri)
        assertEquals(PublicationPhase.PROVIDER_OBJECT_CREATED, loaded2[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.IN_PROGRESS, loaded2[0].status)

        journal.updateDataCopied("tx-1", 1048576L)
        val loaded3 = journal.allEntries()
        assertEquals(1048576L, loaded3[0].copiedBytes)
        assertEquals(PublicationPhase.DATA_COPIED, loaded3[0].publicationPhase)

        journal.updateFinalized("tx-1")
        val loaded4 = journal.allEntries()
        assertEquals(PublicationPhase.FINALIZED, loaded4[0].publicationPhase)
        assertEquals(PublicationTransactionStatus.COMPLETED, loaded4[0].status)

        journal.markReconciled("job-1")
        val loaded5 = journal.allEntries()
        assertEquals(PublicationTransactionStatus.RECONCILED, loaded5[0].status)
        assertTrue(journal.unreconciledEntries().isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // 15 Comprehensive Production-Path Boundary & Failure Injection Scenarios (TEST-01)
    // ---------------------------------------------------------------------------------------------

    // 1. MediaStore insert failure / crash before URI saved
    @Test
    fun boundary01_mediaStore_insertBeforeUriPersistence_orphanedObjectCleanedUp() {
        val testDir = tempFolder.newFolder("b01")
        val ops = TestPublicationStorageOps(testDir)
        val txId = "tx-b01"
        val pendingName = ".vrka_pending_${txId}_video.mp4"

        val orphanedUri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, pendingName) },
            displayName = pendingName,
            isPending = true,
        )!!
        val unrelatedUri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "video.mp4") },
            displayName = "video.mp4",
            isPending = false,
        )!!

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = txId,
                jobId = "job-b01",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "video.mp4",
                pendingFilename = pendingName,
                stagingPath = File(testDir, "video.mp4").absolutePath,
                expectedSizeBytes = 5000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
                providerUri = null,
            ),
        )

        val job = sampleJob("job-b01", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()

        assertFalse("Orphaned pending provider object must be deleted", ops.fileExists(orphanedUri))
        assertTrue("Unrelated user file must never be deleted", ops.fileExists(unrelatedUri))
        assertEquals(PublicationTransactionStatus.RECONCILED, journal.entriesForJob("job-b01").single().status)
        assertTrue(journal.unreconciledEntries().isEmpty())
    }

    // 2. Partial output copy failure (throws IOException after N bytes)
    @Test
    fun boundary02_partialOutputCopyFailure_cleansUpAndMarksFailed() {
        val testDir = tempFolder.newFolder("b02")
        val ops = TestPublicationStorageOps(testDir)
        ops.outputStreamByteLimit = 50L
        val (manager, journal) = createHarness(testDir, ops)

        val staging = File(testDir, "big_video.mp4").apply { writeBytes(ByteArray(200)) }

        val failed = try {
            manager.publisher.publishAll(
                listOf(staging to "big_video.mp4"),
                destinationTreeUri = "content://saf/tree",
                jobId = "job-b02",
            )
            false
        } catch (e: IOException) {
            true
        }
        assertTrue("Publication must fail when stream throws IOException", failed)
        assertEquals("Partial provider files must be deleted on error", 1, ops.deletedUris.distinct().size)
        assertEquals(PublicationTransactionStatus.FAILED, journal.entriesForJob("job-b02").single().status)
    }

    // 3. Completed byte copy before journal update
    @Test
    fun boundary03_completedByteCopyBeforeJournalUpdate_recoversToDone() {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val testDir = tempFolder.newFolder("b03")
            val ops = TestPublicationStorageOps(testDir)
            val txId = "tx-b03"
            val pendingName = ".vrka_pending_${txId}_full.mp4"
            val staging = File(testDir, "full.mp4").apply { writeBytes(ByteArray(1000)) }

            val uri = ops.insertMediaStore(
                Uri.parse("content://media/external/downloads"),
                ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, pendingName) },
                displayName = pendingName,
                isPending = true,
            )!!
            ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(1000)) }

            val journalFile = File(testDir, "pub_journal.json")
            val preJournal = PublicationJournal(journalFile)
            preJournal.recordIntent(
                PublicationEntry(
                    transactionId = txId,
                    jobId = "job-b03",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "full.mp4",
                    pendingFilename = pendingName,
                    stagingPath = staging.absolutePath,
                    expectedSizeBytes = 1000L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                ),
            )
            preJournal.updateProviderCreated(txId, uri.toString())
            preJournal.updateDataCopied(txId, 1000L)

            val job = sampleJob("job-b03", state = JobState.POSTPROCESSING)
            val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

            manager.reconcilePublicationJournalOnStartup()
            val recoveredEntry = journal.entriesForJob("job-b03").single()
            assertEquals(PublicationPhase.RECORDED, recoveredEntry.publicationPhase)
            assertEquals(PublicationTransactionStatus.RECONCILED, recoveredEntry.status)
            val finalJob = JobStore(File(testDir, "download_jobs.json")).load(recoverActive = true).single()
            assertEquals(JobState.DONE, finalJob.state)
            assertEquals(listOf(uri.toString()), finalJob.outputUris)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    // 4. MediaStore finalization failure / zero affected rows (PUB-02)
    @Test
    fun boundary04_mediaStore_finalizationFailureZeroRows_preservesJournalForRetry() {
        val testDir = tempFolder.newFolder("b04")
        val ops = TestPublicationStorageOps(testDir)
        ops.mediaStoreUpdateAffectedRows = 0

        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, ".vrka_pending_tx_final.mp4")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
            displayName = ".vrka_pending_tx_final.mp4",
            isPending = true,
        )!!
        ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(500)) }

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = "tx-b04",
                jobId = "job-b04",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "final.mp4",
                pendingFilename = ".vrka_pending_tx_final.mp4",
                stagingPath = "/staging/final.mp4",
                expectedSizeBytes = 500L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        preJournal.updateProviderCreated("tx-b04", uri.toString())
        preJournal.updateDataCopied("tx-b04", 500L)

        val job = sampleJob("job-b04", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        val finalizationSuccess = manager.publisher.finalizeMediaStore(uri, "final.mp4")
        assertFalse("Zero affected rows with pending still set must be rejected as failure", finalizationSuccess)

        manager.reconcilePublicationJournalOnStartup()

        val entryAfterRecovery = journal.entriesForJob("job-b04").single()
        assertFalse("Journal must NOT be reconciled when finalization fails", entryAfterRecovery.status == PublicationTransactionStatus.RECONCILED)
        assertEquals("Journal must remain unreconciled for retry", 1, journal.unreconciledEntries().size)
    }

    // 5. SAF rename/finalization failure
    @Test
    fun boundary05_saf_renameFinalizationFailure_handledSafely() {
        val testDir = tempFolder.newFolder("b05")
        val ops = TestPublicationStorageOps(testDir)
        ops.failSafRename = true
        val (manager, _) = createHarness(testDir, ops)

        val tree = Uri.parse("content://saf/tree")
        val uri = ops.createSafDocument(tree, "video/mp4", ".vrka_pending_tx_saf.mp4")!!

        val finalUri = manager.publisher.finalizeSaf(uri, "saf_final.mp4")
        assertEquals(uri, finalUri)
    }

    // 6. First output publication in multi-output batch
    @Test
    fun boundary06_multiOutputBatch_firstSucceedsSecondFails_failsClosed() {
        val testDir = tempFolder.newFolder("b06")
        val ops = TestPublicationStorageOps(testDir)

        val staging1 = File(testDir, "track1.mp3").apply { writeBytes(ByteArray(500)) }
        val staging2 = File(testDir, "track2.mp3").apply { writeBytes(ByteArray(500)) }

        val uri1 = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "track1.mp3") },
            displayName = "track1.mp3",
            isPending = false,
        )!!
        ops.openOutputStream(uri1, "w")!!.use { it.write(ByteArray(500)) }

        val uri2 = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, ".vrka_pending_2_track2.mp3") },
            displayName = ".vrka_pending_2_track2.mp3",
            isPending = true,
        )!!

        val batch = listOf(
            PublicationEntry(
                transactionId = "tx-b06-1",
                jobId = "job-b06",
                outputIndex = 0,
                totalOutputs = 2,
                intendedFilename = "track1.mp3",
                pendingFilename = ".vrka_pending_1_track1.mp3",
                stagingPath = staging1.absolutePath,
                expectedSizeBytes = 500L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.FINALIZED,
                copiedBytes = 500L,
                providerUri = uri1.toString(),
            ),
            PublicationEntry(
                transactionId = "tx-b06-2",
                jobId = "job-b06",
                outputIndex = 1,
                totalOutputs = 2,
                intendedFilename = "track2.mp3",
                pendingFilename = ".vrka_pending_2_track2.mp3",
                stagingPath = staging2.absolutePath,
                expectedSizeBytes = 500L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                providerUri = uri2.toString(),
            ),
        )
        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordBatchIntents(batch)
        preJournal.updateProviderCreated("tx-b06-1", uri1.toString())
        preJournal.updateDataCopied("tx-b06-1", 500L)
        preJournal.updateFinalized("tx-b06-1")
        preJournal.updateProviderCreated("tx-b06-2", uri2.toString())

        val job = sampleJob("job-b06", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()

        val jobsFile = File(testDir, "download_jobs.json")
        val finalJob = JobStore(jobsFile).load().single()
        assertEquals(JobState.FAILED, finalJob.state)
        assertEquals(listOf(uri1.toString()), finalJob.outputUris)
        assertFalse("Partial unfinalized track2 must be deleted", ops.fileExists(uri2))
        assertTrue("Finalized track1 must be retained", ops.fileExists(uri1))
    }

    // 7. Terminal JobStore save failure (PUB-01)
    @Test
    fun boundary07_terminalJobStoreSaveFailure_retainsJournalAndStaging_reconcilesOnRestart() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val testDir = tempFolder.newFolder("b07")
            val ops = TestPublicationStorageOps(testDir)
            val uri = ops.insertMediaStore(
                Uri.parse("content://media/external/downloads"),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "video.mp4")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                displayName = "video.mp4",
                isPending = false,
            )!!
            ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(200)) }

            val fileOps = TestJobStoreFileOps()
            val job = sampleJob("job-b07", state = JobState.POSTPROCESSING)
            val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job), jobStoreFileOps = fileOps)

            val stagingDir = File(testDir, "files/staging/job-b07").apply { mkdirs() }
            val stagingFile = File(stagingDir, "video.mp4").apply { writeBytes(ByteArray(200)) }

            journal.recordIntent(
                PublicationEntry(
                    transactionId = "tx-b07",
                    jobId = "job-b07",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    pendingFilename = ".vrka_pending_tx-b07_video.mp4",
                    stagingPath = stagingFile.absolutePath,
                    expectedSizeBytes = 200L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                ),
            )
            journal.updateProviderCreated("tx-b07", uri.toString())
            journal.updateDataCopied("tx-b07", 200L)
            journal.updateFinalized("tx-b07")

            fileOps.failMove = true
            val committed = manager.commitTerminalPublishedJob(
                jobId = "job-b07",
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 200L,
                reportedTotalBytes = 200L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 200L,
                outputUris = listOf(uri.toString()),
            )
            assertFalse("commitTerminalPublishedJob must return false when JobStore save fails", committed)
            assertEquals("Journal MUST NOT be marked reconciled on save failure", 1, journal.unreconciledEntries().size)
            assertTrue("Staging file MUST NOT be cleaned up when terminal persistence fails", stagingFile.exists())

            fileOps.failMove = false
            manager.reconcilePublicationJournalOnStartup()

            assertEquals("On next startup with working store, journal is reconciled", 0, journal.unreconciledEntries().size)
            val jobsFile = File(testDir, "download_jobs.json")
            val loaded = JobStore(jobsFile).load(recoverActive = true).single()
            assertEquals(JobState.DONE, loaded.state)
            assertEquals(listOf(uri.toString()), loaded.outputUris)
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    // 8. Journal persistence failure
    @Test
    fun boundary08_journalPersistenceFailure_handledSafely() {
        val testDir = tempFolder.newFolder("b08")
        val journalFile = File(testDir, "journal.json")
        val journalOps = TestJobStoreFileOps(failWrite = true)
        val journal = PublicationJournal(journalFile, journalOps)

        var caught = false
        try {
            journal.recordIntent(
                PublicationEntry(
                    transactionId = "tx-b08",
                    jobId = "job-b08",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "v.mp4",
                    pendingFilename = ".pending_v.mp4",
                    stagingPath = "/staging/v.mp4",
                    expectedSizeBytes = 100L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                ),
            )
        } catch (e: Exception) {
            caught = true
        }
        assertTrue("Writing to unwritable journal must fail safely", caught)
    }

    // 9. Interruption before journal reconciliation
    @Test
    fun boundary09_interruptionBeforeJournalReconciliation_startupReconcilesCleanly() {
        val testDir = tempFolder.newFolder("b09")
        val ops = TestPublicationStorageOps(testDir)
        val uri = "content://media/external/downloads/999"
        val job = sampleJob("job-b09", state = JobState.DONE, outputUris = listOf(uri))

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = "tx-b09",
                jobId = "job-b09",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "v.mp4",
                pendingFilename = ".pending_v.mp4",
                stagingPath = "/staging/v.mp4",
                expectedSizeBytes = 300L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        preJournal.updateProviderCreated("tx-b09", uri)
        preJournal.updateDataCopied("tx-b09", 300L)
        preJournal.updateFinalized("tx-b09")

        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()

        assertTrue("Journal must be marked reconciled when job is already verified DONE", journal.unreconciledEntries().isEmpty())
        val jobsFile = File(testDir, "download_jobs.json")
        val loaded = JobStore(jobsFile).load().single()
        assertEquals(JobState.DONE, loaded.state)
    }

    // 10. Interruption after durable terminal JobStore persistence (6-step verification)
    @Test
    fun boundary10_durablePersistenceOrdering_stagingCleanedOnlyAfterReconciliation() = runBlocking {
        DownloadTransferRuntime.markTransferJobInvoked()
        try {
            val testDir = tempFolder.newFolder("b10")
            val ops = TestPublicationStorageOps(testDir)
            val uri = ops.insertMediaStore(
                Uri.parse("content://media/external/downloads"),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "video.mp4")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                },
                displayName = "video.mp4",
                isPending = false,
            )!!
            ops.openOutputStream(uri, "w")!!.use { it.write(ByteArray(100)) }

            val stagingDir = File(testDir, "files/staging/job-b10").apply { mkdirs() }
            val stagingFile = File(stagingDir, "video.mp4").apply { writeBytes(ByteArray(100)) }

            val journalFile = File(testDir, "pub_journal.json")
            val preJournal = PublicationJournal(journalFile)
            preJournal.recordIntent(
                PublicationEntry(
                    transactionId = "tx-b10",
                    jobId = "job-b10",
                    outputIndex = 0,
                    totalOutputs = 1,
                    intendedFilename = "video.mp4",
                    pendingFilename = ".vrka_pending_tx-b10_video.mp4",
                    stagingPath = stagingFile.absolutePath,
                    expectedSizeBytes = 100L,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                ),
            )
            preJournal.updateProviderCreated("tx-b10", uri.toString())
            preJournal.updateDataCopied("tx-b10", 100L)
            preJournal.updateFinalized("tx-b10")

            val job = sampleJob("job-b10", state = JobState.POSTPROCESSING)
            val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

            val committed = manager.commitTerminalPublishedJob(
                jobId = "job-b10",
                detail = "Saved to Downloads/VRKA",
                downloadedBytes = 100L,
                reportedTotalBytes = 100L,
                reportedTotalIsExact = true,
                actualFinalFileBytes = 100L,
                outputUris = listOf(uri.toString()),
            )
            assertTrue("When JobStore succeeds, commitTerminalPublishedJob returns true", committed)
            assertEquals("Journal is reconciled", 0, journal.unreconciledEntries().size)
            assertFalse("Staging is cleaned up after successful durable commit", stagingFile.exists())
        } finally {
            DownloadTransferRuntime.resetForTesting()
        }
    }

    // 11. Failed partial-output deletion (PUB-03)
    @Test
    fun boundary11_failedPartialOutputDeletion_marksCleanupPending_preservesJournal() {
        val testDir = tempFolder.newFolder("b11")
        val ops = TestPublicationStorageOps(testDir)
        ops.failDelete = true

        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, ".vrka_pending_b11.mp4") },
            displayName = ".vrka_pending_b11.mp4",
            isPending = true,
        )!!

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = "tx-b11",
                jobId = "job-b11",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "b11.mp4",
                pendingFilename = ".vrka_pending_b11.mp4",
                stagingPath = "/staging/b11.mp4",
                expectedSizeBytes = 5000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                providerUri = uri.toString(),
            ),
        )

        val job = sampleJob("job-b11", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()

        val entry = journal.entriesForJob("job-b11").single()
        assertEquals(PublicationTransactionStatus.CLEANUP_PENDING, entry.status)
        assertEquals("Journal must NOT reconcile CLEANUP_PENDING entry", 1, journal.unreconciledEntries().size)

        val jobsFile = File(testDir, "download_jobs.json")
        val loadedJob = JobStore(jobsFile).load().single()
        assertEquals(JobState.FAILED, loadedJob.state)
        assertTrue("Detail must indicate pending cleanup", loadedJob.detail.contains("cleanup pending"))
    }

    // 12. Repeated cleanup and recovery attempts (PUB-03)
    @Test
    fun boundary12_repeatedCleanupAttempts_subsequentPassDeletesAndReconciles() {
        val testDir = tempFolder.newFolder("b12")
        val ops = TestPublicationStorageOps(testDir)
        ops.failDelete = true

        val uri = ops.insertMediaStore(
            Uri.parse("content://media/external/downloads"),
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, ".vrka_pending_b12.mp4") },
            displayName = ".vrka_pending_b12.mp4",
            isPending = true,
        )!!

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = "tx-b12",
                jobId = "job-b12",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "b12.mp4",
                pendingFilename = ".vrka_pending_b12.mp4",
                stagingPath = "/staging/b12.mp4",
                expectedSizeBytes = 3000L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                providerUri = uri.toString(),
            ),
        )

        val job = sampleJob("job-b12", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()
        assertEquals(PublicationTransactionStatus.CLEANUP_PENDING, journal.entriesForJob("job-b12").single().status)

        ops.failDelete = false
        manager.reconcilePublicationJournalOnStartup()

        val entryPass2 = journal.entriesForJob("job-b12").single()
        assertEquals(PublicationTransactionStatus.RECONCILED, entryPass2.status)
        assertEquals(0, journal.unreconciledEntries().size)
    }

    // 13. Corrupt and unknown journal values (PUB-04)
    @Test
    fun boundary13_corruptAndUnknownJournalValues_strictlyRejected_boundedBackups() {
        val testDir = tempFolder.newFolder("b13")
        val journalFile = File(testDir, "pub_journal.json")

        val malformedJson = """
            {
              "version": 1,
              "entries": [
                {
                  "transactionId": "tx-bad",
                  "jobId": "job-bad",
                  "outputIndex": 0,
                  "totalOutputs": 1,
                  "intendedFilename": "bad.mp4",
                  "pendingFilename": ".pending_bad.mp4",
                  "stagingPath": "/staging/bad.mp4",
                  "expectedSizeBytes": 100,
                  "providerType": "UNSUPPORTED_PROVIDER_TYPE",
                  "destinationIdentity": "custom",
                  "publicationPhase": "INTENT_RECORDED",
                  "status": "PENDING"
                }
              ]
            }
        """.trimIndent()
        journalFile.writeText(malformedJson)

        val journal = PublicationJournal(journalFile)
        val result = journal.loadWithStatus()

        assertEquals("Unknown provider type MUST be rejected as CORRUPT", PublicationJournalLoadState.CORRUPT, result.state)
        assertTrue(result.entries.isEmpty())
        assertNotNull(result.recoveryCopy)
        assertTrue(result.recoveryCopy!!.exists())

        for (i in 1..5) {
            journalFile.writeText("""{"version": 1, "entries": "invalid_array_$i"}""")
            journal.loadWithStatus()
            Thread.sleep(10)
        }
        val corruptFiles = testDir.listFiles { f -> f.name.startsWith("pub_journal.corrupt-") } ?: emptyArray()
        assertEquals("Corrupt snapshots must be bounded to at most 3", 3, corruptFiles.size)
    }

    // 14. Repeated application restart during reconciliation (idempotence)
    @Test
    fun boundary14_repeatedApplicationRestart_isIdempotent() {
        val testDir = tempFolder.newFolder("b14")
        val ops = TestPublicationStorageOps(testDir)
        val uri = "content://media/external/downloads/1414"
        val job = sampleJob("job-b14", state = JobState.DONE, outputUris = listOf(uri))

        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordIntent(
            PublicationEntry(
                transactionId = "tx-b14",
                jobId = "job-b14",
                outputIndex = 0,
                totalOutputs = 1,
                intendedFilename = "idem.mp4",
                pendingFilename = ".pending_idem.mp4",
                stagingPath = "/staging/idem.mp4",
                expectedSizeBytes = 1200L,
                providerType = PublicationProviderType.MEDIA_STORE,
                destinationIdentity = "Downloads/VRKA",
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        preJournal.updateProviderCreated("tx-b14", uri)
        preJournal.updateDataCopied("tx-b14", 1200L)
        preJournal.updateFinalized("tx-b14")

        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()
        assertEquals(0, journal.unreconciledEntries().size)

        manager.reconcilePublicationJournalOnStartup()
        assertEquals(0, journal.unreconciledEntries().size)

        manager.reconcilePublicationJournalOnStartup()
        assertEquals(0, journal.unreconciledEntries().size)

        val jobsFile = File(testDir, "download_jobs.json")
        val stored = JobStore(jobsFile).load().single()
        assertEquals(JobState.DONE, stored.state)
        assertEquals(listOf(uri), stored.outputUris)
    }

    // 15. Multi-output batch partial publication with SAF / MediaStore
    @Test
    fun boundary15_multiOutputBatch_partialPublicationSaf_failsClosedCleanly() {
        val testDir = tempFolder.newFolder("b15")
        val ops = TestPublicationStorageOps(testDir)
        val tree = Uri.parse("content://saf/tree")

        val uri1 = ops.createSafDocument(tree, "video/mp4", ".vrka_pending_1.mp4")!!
        ops.openOutputStream(uri1, "w")!!.use { it.write(ByteArray(100)) }
        ops.renameSafDocument(uri1, "track1.mp4")

        val uri2 = ops.createSafDocument(tree, "video/mp4", ".vrka_pending_2.mp4")!!

        val batch = listOf(
            PublicationEntry(
                transactionId = "tx-b15-1",
                jobId = "job-b15",
                outputIndex = 0,
                totalOutputs = 3,
                intendedFilename = "track1.mp4",
                pendingFilename = ".vrka_pending_1.mp4",
                stagingPath = "/staging/track1.mp4",
                expectedSizeBytes = 100L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = tree.toString(),
                publicationPhase = PublicationPhase.FINALIZED,
                copiedBytes = 100L,
                providerUri = uri1.toString(),
            ),
            PublicationEntry(
                transactionId = "tx-b15-2",
                jobId = "job-b15",
                outputIndex = 1,
                totalOutputs = 3,
                intendedFilename = "track2.mp4",
                pendingFilename = ".vrka_pending_2.mp4",
                stagingPath = "/staging/track2.mp4",
                expectedSizeBytes = 200L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = tree.toString(),
                publicationPhase = PublicationPhase.PROVIDER_OBJECT_CREATED,
                providerUri = uri2.toString(),
            ),
            PublicationEntry(
                transactionId = "tx-b15-3",
                jobId = "job-b15",
                outputIndex = 2,
                totalOutputs = 3,
                intendedFilename = "track3.mp4",
                pendingFilename = ".vrka_pending_3.mp4",
                stagingPath = "/staging/track3.mp4",
                expectedSizeBytes = 300L,
                providerType = PublicationProviderType.SAF,
                destinationIdentity = tree.toString(),
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            ),
        )
        val journalFile = File(testDir, "pub_journal.json")
        val preJournal = PublicationJournal(journalFile)
        preJournal.recordBatchIntents(batch)
        preJournal.updateProviderCreated("tx-b15-1", uri1.toString())
        preJournal.updateDataCopied("tx-b15-1", 100L)
        preJournal.updateFinalized("tx-b15-1", uri1.toString())
        preJournal.updateProviderCreated("tx-b15-2", uri2.toString())

        val job = sampleJob("job-b15", state = JobState.POSTPROCESSING)
        val (manager, journal) = createHarness(testDir, ops, initialJobs = listOf(job))

        manager.reconcilePublicationJournalOnStartup()

        val jobsFile = File(testDir, "download_jobs.json")
        val finalJob = JobStore(jobsFile).load().single()
        assertEquals(JobState.FAILED, finalJob.state)
        assertEquals(listOf(uri1.toString()), finalJob.outputUris)
        assertTrue("Completed track 1 document must be preserved", ops.fileExists(uri1))
        assertFalse("Incomplete track 2 pending document must be deleted", ops.fileExists(uri2))
    }
}
