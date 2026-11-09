package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.OutputStream
import java.net.URLConnection
import java.util.UUID

internal data class PublishedOutput(
    val uri: Uri,
    val bytes: Long,
    val extension: String,
    val transactionId: String = "",
)

/**
 * Result of locating a provider object. A provider that cannot be queried must never be
 * reported as "absent": absence authorizes cleanup and reconciliation, an unknown result
 * only authorizes a later retry.
 */
internal sealed interface UriLookupResult {
    data class Found(val uri: Uri) : UriLookupResult
    object DefinitelyAbsent : UriLookupResult
    data class Unknown(val detail: String) : UriLookupResult
}

internal enum class ProviderObjectStatus {
    /** Row exists, final display name is applied, and (API 29+) IS_PENDING is cleared. */
    VERIFIED,

    /** Row exists under this transaction's unique pending name, or is still pending. */
    PENDING,

    /** The row does not exist. */
    ABSENT,

    /** The row exists but belongs to a different name or location. */
    MISMATCH,

    /** The provider could not be queried, or required columns are unavailable. */
    UNKNOWN,
}

internal data class ProviderInspection(
    val status: ProviderObjectStatus,
    val displayName: String? = null,
    val detail: String = "",
)

/** Ownership of a provider object for destructive cleanup purposes. */
internal enum class ObjectOwnership {
    OWNED,
    NOT_OWNED,
    ABSENT,
    UNKNOWN,
}

internal enum class CleanupOutcome {
    DELETED,
    ALREADY_ABSENT,
    RETRY_REQUIRED,
}

internal data class ProviderFinalization(
    val inspection: ProviderInspection,
    val uri: Uri,
)

internal interface PublicationStorageOps {
    fun insertMediaStore(collection: Uri, values: ContentValues, displayName: String? = null, isPending: Boolean? = null): Uri?
    fun openOutputStream(uri: Uri, mode: String = "w"): OutputStream?
    fun updateMediaStore(uri: Uri, values: ContentValues, displayName: String? = null, isPending: Boolean? = null): Int
    fun queryMediaStore(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?): android.database.Cursor?
    fun queryMediaStoreCollection(collection: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?): android.database.Cursor?
    fun deleteUri(uri: Uri): Boolean
    fun fileExists(uri: Uri): Boolean
    fun getFileSize(uri: Uri): Long
    fun queryDisplayName(uri: Uri): String?
    fun findPendingMediaStoreUri(pendingFilename: String): UriLookupResult

    // SAF
    fun existingSafNames(tree: Uri): Set<String>
    fun findPendingSafUri(tree: Uri, pendingFilename: String): UriLookupResult
    fun createSafDocument(tree: Uri, mimeType: String, pendingFilename: String): Uri?
    fun renameSafDocument(uri: Uri, finalName: String): Uri?
    fun deleteSafDocument(uri: Uri): Boolean
}

internal class DefaultPublicationStorageOps(private val context: Context) : PublicationStorageOps {
    override fun insertMediaStore(collection: Uri, values: ContentValues, displayName: String?, isPending: Boolean?): Uri? =
        context.contentResolver.insert(collection, values)

    override fun openOutputStream(uri: Uri, mode: String): OutputStream? =
        context.contentResolver.openOutputStream(uri, mode)

    override fun updateMediaStore(uri: Uri, values: ContentValues, displayName: String?, isPending: Boolean?): Int =
        context.contentResolver.update(uri, values, null, null)

    override fun queryMediaStore(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): android.database.Cursor? =
        context.contentResolver.query(uri, projection, selection, selectionArgs, null)

    override fun queryMediaStoreCollection(
        collection: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): android.database.Cursor? =
        context.contentResolver.query(collection, projection, selection, selectionArgs, null)

    override fun deleteUri(uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    override fun fileExists(uri: Uri): Boolean = runCatching {
        val cursorCount = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { it.count }
        }.getOrNull()
        if (cursorCount != null && cursorCount > 0) return true
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    override fun getFileSize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    override fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    override fun findPendingMediaStoreUri(pendingFilename: String): UriLookupResult {
        if (pendingFilename.isBlank()) return UriLookupResult.DefinitelyAbsent
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(pendingFilename)
        val cursor = queryMediaStoreCollection(collection, projection, selection, selectionArgs)
            ?: return UriLookupResult.Unknown("Provider returned no cursor for pending lookup")
        cursor.use {
            val matches = mutableListOf<Uri>()
            while (it.moveToNext()) {
                if (!it.isNull(0)) {
                    matches += android.content.ContentUris.withAppendedId(collection, it.getLong(0))
                }
            }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single())
                else -> UriLookupResult.Unknown("Multiple provider rows match the transaction pending name")
            }
        }
    }

    override fun existingSafNames(tree: Uri): Set<String> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val existing = mutableSetOf<String>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext() && existing.size < 10_000) {
                if (column >= 0) cursor.getString(column)?.let(existing::add)
            }
        }
        return existing
    }

    override fun findPendingSafUri(tree: Uri, pendingFilename: String): UriLookupResult {
        if (pendingFilename.isBlank()) return UriLookupResult.DefinitelyAbsent
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return UriLookupResult.Unknown("Provider returned no cursor for SAF lookup")
        cursor.use {
            val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) {
                return UriLookupResult.Unknown("SAF provider omitted document identity columns")
            }
            val matches = mutableListOf<Uri>()
            while (it.moveToNext()) {
                if (it.getString(nameCol) == pendingFilename) {
                    val docId = it.getString(idCol) ?: continue
                    matches += DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                }
            }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single())
                else -> UriLookupResult.Unknown("Multiple SAF documents match the transaction pending name")
            }
        }
    }

    override fun createSafDocument(tree: Uri, mimeType: String, pendingFilename: String): Uri? = runCatching {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        DocumentsContract.createDocument(context.contentResolver, parent, mimeType, pendingFilename)
    }.getOrNull()

    override fun renameSafDocument(uri: Uri, finalName: String): Uri? = runCatching {
        DocumentsContract.renameDocument(context.contentResolver, uri, finalName)
    }.getOrNull()

    override fun deleteSafDocument(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.getOrDefault(false)
}

internal class OutputPublisher(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    journal: PublicationJournal? = null,
    internal val ops: PublicationStorageOps = DefaultPublicationStorageOps(context),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val mediaStoreCollection: Uri? = null,
) {
    private val publishLock = Any()
    val activeJournal: PublicationJournal by lazy {
        journal ?: PublicationJournal(File(context.filesDir, "publication_journal.json"))
    }

    fun publish(
        file: File,
        requestedName: String = file.name,
        destinationTreeUri: String? = null,
        jobId: String = "",
    ): PublishedOutput = publishAll(listOf(file to requestedName), destinationTreeUri, jobId = jobId).single()

    /**
     * Publishes a batch while querying a SAF destination folder only once. The previous
     * one-file-at-a-time path repeated the full child listing for every playlist output.
     */
    fun publishAll(
        files: List<Pair<File, String>>,
        destinationTreeUri: String? = null,
        jobId: String = "",
        shouldContinue: () -> Boolean = { true },
        onPublished: (index: Int, total: Int) -> Unit = { _, _ -> },
    ): List<PublishedOutput> = synchronized(publishLock) {
        if (files.isEmpty()) return@synchronized emptyList()
        files.forEach { (file, _) -> require(file.isFile) { "Completed output is missing." } }
        val destination = destinationTreeUri?.takeIf { it.isNotBlank() }
            ?: settingsRepository.settings.value.outputTreeUri.takeIf { it.isNotBlank() }
        require(
            destination != null || !DestinationPolicy.requiresSafDestination(sdkInt),
        ) { runCatching { context.getString(R.string.error_destination_required) }.getOrNull() ?: "A destination folder is required." }

        val effectiveJobId = jobId.ifBlank { "standalone-${System.currentTimeMillis()}" }
        val batchEntries = prepareBatchEntries(files, destination, effectiveJobId)
        activeJournal.recordBatchIntents(batchEntries)

        return if (destination != null) {
            publishToTreeBatch(files, Uri.parse(destination), batchEntries, shouldContinue, onPublished)
        } else {
            publishToDownloadsBatch(files, batchEntries, shouldContinue, onPublished)
        }
        // Staging files are intentionally not deleted here. They remain the durable recovery
        // source until the manager confirms the terminal JobStore commit and journal
        // reconciliation, then removes the whole per-job staging directory.
    }

    private fun prepareBatchEntries(
        files: List<Pair<File, String>>,
        destinationTreeUri: String?,
        jobId: String,
    ): List<PublicationEntry> {
        val existingSafNames = if (destinationTreeUri != null) {
            existingNames(Uri.parse(destinationTreeUri))
        } else {
            null
        }
        val providerType = if (destinationTreeUri != null) {
            PublicationProviderType.SAF
        } else {
            PublicationProviderType.MEDIA_STORE
        }
        val destIdentity = destinationTreeUri ?: "Downloads/VRKA"

        return files.mapIndexed { index, (file, requestedName) ->
            val safeName = SafeOutputNames.sanitize(requestedName, file.extension)
            val finalName = if (existingSafNames != null) {
                val allocated = OutputNameAllocator.next(existingSafNames, safeName)
                existingSafNames += allocated
                allocated
            } else {
                safeName
            }
            val txId = UUID.randomUUID().toString()
            val pendingName = ".vrka_pending_${txId}_$finalName"
            PublicationEntry(
                transactionId = txId,
                jobId = jobId,
                outputIndex = index,
                totalOutputs = files.size,
                intendedFilename = finalName,
                pendingFilename = pendingName,
                stagingPath = file.absolutePath,
                expectedSizeBytes = file.length(),
                providerType = providerType,
                destinationIdentity = destIdentity,
                publicationPhase = PublicationPhase.INTENT_RECORDED,
            )
        }
    }

    fun delete(uriText: String): Boolean = runCatching {
        val uri = Uri.parse(uriText)
        ops.deleteSafDocument(uri) || ops.deleteUri(uri)
    }.getOrDefault(false)

    fun nodeName(uriText: String): String? = ops.queryDisplayName(Uri.parse(uriText))

    fun fileExists(uriText: String): Boolean = ops.fileExists(Uri.parse(uriText))

    fun getFileSize(uriText: String): Long = ops.getFileSize(Uri.parse(uriText))

    fun findPendingMediaStoreUri(pendingFilename: String): UriLookupResult = try {
        ops.findPendingMediaStoreUri(pendingFilename)
    } catch (error: Throwable) {
        UriLookupResult.Unknown(error.message.orEmpty().take(200))
    }

    fun findPendingSafUri(tree: Uri, pendingFilename: String): UriLookupResult = try {
        ops.findPendingSafUri(tree, pendingFilename)
    } catch (error: Throwable) {
        UriLookupResult.Unknown(error.message.orEmpty().take(200))
    }

    /**
     * Verifies the actual provider row, not URI existence. On API 29+ a missing IS_PENDING
     * column is treated as unverifiable (never as finalized), because the platform supports
     * the column and its absence means the provider did not answer the required question.
     */
    fun inspectMediaStoreOutput(
        uri: Uri,
        expectedFinalName: String,
        expectedPendingName: String? = null,
    ): ProviderInspection {
        if (!PublicationUriRules.isMediaStoreUri(uri)) {
            return ProviderInspection(ProviderObjectStatus.MISMATCH, detail = "URI is not a MediaStore row")
        }
        val projection = if (sdkInt >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        } else {
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        }
        val cursor = try {
            ops.queryMediaStore(uri, projection, null, null)
        } catch (error: Throwable) {
            return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = error.message.orEmpty().take(200))
        } ?: return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "Provider returned no cursor")
        cursor.use {
            if (!it.moveToFirst()) return ProviderInspection(ProviderObjectStatus.ABSENT)
            val nameColumn = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (nameColumn < 0) {
                return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "DISPLAY_NAME column missing")
            }
            val name = it.getString(nameColumn)
                ?: return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "DISPLAY_NAME is null")
            if (sdkInt >= Build.VERSION_CODES.Q) {
                val pendingColumn = it.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                if (pendingColumn < 0) {
                    return ProviderInspection(
                        ProviderObjectStatus.UNKNOWN,
                        displayName = name,
                        detail = "IS_PENDING column missing on API ${sdkInt}",
                    )
                }
                val pending = it.getInt(pendingColumn) != 0
                if (pending) {
                    return ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
                }
                if (name == expectedPendingName) {
                    return ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
                }
            } else if (expectedPendingName != null && name == expectedPendingName) {
                return ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
            }
            return when (name) {
                expectedFinalName -> ProviderInspection(ProviderObjectStatus.VERIFIED, displayName = name)
                else -> ProviderInspection(ProviderObjectStatus.MISMATCH, displayName = name)
            }
        }
    }

    fun inspectSafOutput(
        uri: Uri,
        expectedFinalName: String,
        expectedPendingName: String?,
        tree: Uri,
    ): ProviderInspection {
        if (!PublicationUriRules.isDocumentUnderTree(uri, tree)) {
            return ProviderInspection(ProviderObjectStatus.MISMATCH, detail = "Document is outside the journal SAF tree")
        }
        val cursor = try {
            ops.queryMediaStore(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null)
        } catch (error: Throwable) {
            return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = error.message.orEmpty().take(200))
        } ?: return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "Provider returned no cursor")
        cursor.use {
            if (!it.moveToFirst()) return ProviderInspection(ProviderObjectStatus.ABSENT)
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn < 0) {
                return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "DISPLAY_NAME column missing")
            }
            val name = it.getString(nameColumn)
                ?: return ProviderInspection(ProviderObjectStatus.UNKNOWN, detail = "DISPLAY_NAME is null")
            return when (name) {
                expectedFinalName -> ProviderInspection(ProviderObjectStatus.VERIFIED, displayName = name)
                expectedPendingName -> ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
                else -> ProviderInspection(ProviderObjectStatus.MISMATCH, displayName = name)
            }
        }
    }

    fun inspectOutput(entry: PublicationEntry): ProviderInspection {
        val uriText = entry.providerUri ?: return ProviderInspection(ProviderObjectStatus.ABSENT)
        val uri = runCatching { Uri.parse(uriText) }.getOrNull()
            ?: return ProviderInspection(ProviderObjectStatus.MISMATCH, detail = "Unparseable provider URI")
        return when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE ->
                inspectMediaStoreOutput(uri, entry.intendedFilename, entry.pendingFilename)
            PublicationProviderType.SAF -> {
                val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                    ?: return ProviderInspection(ProviderObjectStatus.MISMATCH)
                inspectSafOutput(uri, entry.intendedFilename, entry.pendingFilename, tree)
            }
        }
    }

    /**
     * Finalizes the provider row and re-reads it. The returned inspection is authoritative:
     * an update call succeeding is never sufficient proof of a finalized output.
     */
    fun finalizeEntry(entry: PublicationEntry): ProviderFinalization {
        val uriText = entry.providerUri
            ?: return ProviderFinalization(ProviderInspection(ProviderObjectStatus.ABSENT), Uri.EMPTY)
        val uri = runCatching { Uri.parse(uriText) }.getOrNull()
            ?: return ProviderFinalization(ProviderInspection(ProviderObjectStatus.MISMATCH), Uri.EMPTY)
        return when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE -> {
                finalizeMediaStore(uri, entry.intendedFilename)
                ProviderFinalization(
                    inspectMediaStoreOutput(uri, entry.intendedFilename, entry.pendingFilename),
                    uri,
                )
            }
            PublicationProviderType.SAF -> {
                val target = finalizeSaf(uri, entry.intendedFilename)
                val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                val inspection = if (tree == null) {
                    ProviderInspection(ProviderObjectStatus.MISMATCH)
                } else {
                    inspectSafOutput(target, entry.intendedFilename, entry.pendingFilename, tree)
                }
                ProviderFinalization(inspection, target)
            }
        }
    }

    /**
     * Destructive cleanup is authorized only for a row carrying this transaction's pending
     * name and only when that row is the single object discovered under that unique name.
     * A finalized display name or an unrelated row is never deleted.
     */
    fun pendingObjectOwnership(entry: PublicationEntry): ObjectOwnership {
        if (entry.providerUri == null) return ObjectOwnership.ABSENT
        val inspection = inspectOutput(entry)
        if (inspection.status == ProviderObjectStatus.ABSENT) return ObjectOwnership.ABSENT
        if (inspection.status == ProviderObjectStatus.UNKNOWN) return ObjectOwnership.UNKNOWN
        if (inspection.status != ProviderObjectStatus.PENDING ||
            inspection.displayName != entry.pendingFilename
        ) {
            return ObjectOwnership.NOT_OWNED
        }
        val providerUri = runCatching { Uri.parse(entry.providerUri) }.getOrNull()
            ?: return ObjectOwnership.NOT_OWNED
        val discovered = when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE ->
                findPendingMediaStoreUri(entry.pendingFilename)
            PublicationProviderType.SAF -> {
                val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                    ?: return ObjectOwnership.NOT_OWNED
                findPendingSafUri(tree, entry.pendingFilename)
            }
        }
        return when (discovered) {
            is UriLookupResult.Found -> {
                if (discovered.uri == providerUri) ObjectOwnership.OWNED else ObjectOwnership.NOT_OWNED
            }
            UriLookupResult.DefinitelyAbsent -> ObjectOwnership.ABSENT
            is UriLookupResult.Unknown -> ObjectOwnership.UNKNOWN
        }
    }

    fun cleanupPendingObject(entry: PublicationEntry): CleanupOutcome {
        val uriText = entry.providerUri ?: return CleanupOutcome.ALREADY_ABSENT
        val uri = runCatching { Uri.parse(uriText) }.getOrNull()
            ?: return CleanupOutcome.RETRY_REQUIRED
        return when (pendingObjectOwnership(entry)) {
            ObjectOwnership.ABSENT -> CleanupOutcome.ALREADY_ABSENT
            ObjectOwnership.NOT_OWNED,
            ObjectOwnership.UNKNOWN,
            -> CleanupOutcome.RETRY_REQUIRED
            ObjectOwnership.OWNED -> {
                val deleted = try {
                    when (entry.providerType) {
                        PublicationProviderType.MEDIA_STORE -> ops.deleteUri(uri)
                        PublicationProviderType.SAF -> ops.deleteSafDocument(uri)
                    }
                } catch (error: Throwable) {
                    false
                }
                if (deleted) {
                    CleanupOutcome.DELETED
                } else {
                    when (inspectOutput(entry).status) {
                        ProviderObjectStatus.ABSENT -> CleanupOutcome.ALREADY_ABSENT
                        else -> CleanupOutcome.RETRY_REQUIRED
                    }
                }
            }
        }
    }

    /**
     * Checks and verifies MediaStore row finalization.
     * Updates DISPLAY_NAME and clears IS_PENDING (on API 29+).
     * Returns true ONLY when the resulting row is confirmed finalized.
     */
    fun finalizeMediaStore(uri: Uri, finalName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        }
        runCatching { ops.updateMediaStore(uri, values, displayName = finalName, isPending = false) }
        return verifyMediaStoreFinalized(uri, finalName)
    }

    fun verifyMediaStoreFinalized(uri: Uri, expectedFinalName: String): Boolean =
        inspectMediaStoreOutput(uri, expectedFinalName).status == ProviderObjectStatus.VERIFIED

    fun finalizeSaf(uri: Uri, finalName: String): Uri =
        ops.renameSafDocument(uri, finalName) ?: uri

    private fun publishToDownloads(
        file: File,
        entry: PublicationEntry,
    ): PublishedOutput {
        val mime = mimeType(entry.intendedFilename)
        val collection = mediaStoreCollection ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.pendingFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VRKA",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(ops.insertMediaStore(collection, values, displayName = entry.pendingFilename, isPending = true)) {
            "Android could not create the Downloads entry."
        }
        activeJournal.updateProviderCreated(entry.transactionId, uri.toString())
        try {
            val bytes = checkNotNull(ops.openOutputStream(uri, "w")) { "Android could not open the Downloads entry." }.use { output ->
                file.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (file.length() > 0L && bytes != file.length()) {
                throw java.io.IOException("Publication size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
            }
            activeJournal.updateDataCopied(entry.transactionId, bytes)
            activeJournal.updateValidated(entry.transactionId)
            val finalized = finalizeMediaStore(uri, entry.intendedFilename)
            if (!finalized) {
                throw java.io.IOException("MediaStore finalization failed for ${entry.intendedFilename}: row update affected 0 rows or verification failed")
            }
            activeJournal.updateFinalized(entry.transactionId)
            return PublishedOutput(uri, bytes, file.extension, entry.transactionId)
        } catch (error: Throwable) {
            runCatching { activeJournal.markFailed(entry.transactionId, error.message.orEmpty()) }
            val deleted = runCatching { ops.deleteUri(uri) }.getOrDefault(false)
            if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                runCatching {
                    activeJournal.markCleanupPending(
                        entry.transactionId,
                        "Could not delete partial provider output: $uri",
                    )
                }
            }
            throw error
        }
    }

    private fun publishToDownloadsBatch(
        files: List<Pair<File, String>>,
        batchEntries: List<PublicationEntry>,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val created = mutableListOf<Pair<Uri, String>>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, _) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val entry = batchEntries[index]
                currentTransactions += entry.transactionId

                val result = publishToDownloads(
                    file = file,
                    entry = entry,
                )
                created += result.uri to entry.transactionId
                onPublished(index, files.size)
                result
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                runCatching { activeJournal.markFailed(txId, error.message.orEmpty()) }
            }
            created.forEach { (uri, txId) ->
                val deleted = runCatching { ops.deleteUri(uri) }.getOrDefault(false)
                if (!deleted) {
                    val entry = batchEntries.firstOrNull { it.transactionId == txId }
                    val absent = entry != null &&
                        inspectOutput(entry.copy(providerUri = uri.toString())).status == ProviderObjectStatus.ABSENT
                    if (!absent) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                txId,
                                "Could not delete partial provider output: $uri",
                            )
                        }
                    }
                }
            }
            throw error
        }
    }

    private fun publishToTreeBatch(
        files: List<Pair<File, String>>,
        tree: Uri,
        batchEntries: List<PublicationEntry>,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val created = mutableListOf<Pair<Uri, String>>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, _) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val entry = batchEntries[index]
                currentTransactions += entry.transactionId

                val uri = checkNotNull(
                    ops.createSafDocument(tree, mimeType(entry.intendedFilename), entry.pendingFilename),
                ) { "The selected folder refused the new file." }
                created += uri to entry.transactionId
                activeJournal.updateProviderCreated(entry.transactionId, uri.toString())

                val bytes = try {
                    checkNotNull(ops.openOutputStream(uri, "w")) { "The selected folder could not be written." }.use { output ->
                        file.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                } catch (error: Throwable) {
                    runCatching { activeJournal.markFailed(entry.transactionId, error.message.orEmpty()) }
                    val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                    if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                entry.transactionId,
                                "Could not delete partial SAF output: $uri",
                            )
                        }
                    }
                    throw error
                }
                if (file.length() > 0L && bytes != file.length()) {
                    val mismatch = java.io.IOException("SAF output size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
                    runCatching { activeJournal.markFailed(entry.transactionId, mismatch.message.orEmpty()) }
                    val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                    if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                entry.transactionId,
                                "Could not delete mismatched SAF output: $uri",
                            )
                        }
                    }
                    throw mismatch
                }
                activeJournal.updateDataCopied(entry.transactionId, bytes)
                activeJournal.updateValidated(entry.transactionId)
                val finalUri = finalizeSaf(uri, entry.intendedFilename)
                val inspection = inspectSafOutput(finalUri, entry.intendedFilename, entry.pendingFilename, tree)
                if (inspection.status != ProviderObjectStatus.VERIFIED) {
                    throw java.io.IOException(
                        "SAF finalization failed for ${entry.intendedFilename}: ${inspection.status.name} ${inspection.detail}",
                    )
                }
                activeJournal.updateFinalized(entry.transactionId, finalUri.toString())
                onPublished(index, files.size)
                PublishedOutput(finalUri, bytes, file.extension, entry.transactionId)
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                runCatching { activeJournal.markFailed(txId, error.message.orEmpty()) }
            }
            created.forEach { (uri, txId) ->
                val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                if (!deleted) {
                    val entry = batchEntries.firstOrNull { it.transactionId == txId }
                    val absent = entry != null &&
                        inspectOutput(entry.copy(providerUri = uri.toString())).status == ProviderObjectStatus.ABSENT
                    if (!absent) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                txId,
                                "Could not delete partial SAF output: $uri",
                            )
                        }
                    }
                }
            }
            throw error
        }
    }

    private fun existingNames(tree: Uri): MutableSet<String> = ops.existingSafNames(tree).toMutableSet()

    private fun mimeType(name: String): String = guessMimeType(name)

    companion object {
        private const val MAX_EXISTING_NAMES = 10_000

        fun guessMimeType(name: String?): String {
            if (name.isNullOrBlank()) return "application/octet-stream"
            return URLConnection.guessContentTypeFromName(name)
                ?: when (name.substringAfterLast('.', "").lowercase()) {
                    "mkv" -> "video/x-matroska"
                    "opus" -> "audio/opus"
                    "ogg" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    "mp3" -> "audio/mpeg"
                    "flac" -> "audio/flac"
                    "m3u8" -> "application/vnd.apple.mpegurl"
                    else -> "application/octet-stream"
                }
        }

        fun hasPersistedTreePermission(context: Context, treeUri: Uri): Boolean = runCatching {
            val persisted = context.contentResolver.persistedUriPermissions
            persisted.any { it.uri == treeUri && it.isWritePermission }
        }.getOrDefault(false)

        fun isTreePermissionValid(context: Context, treeUriString: String?): Boolean {
            if (treeUriString.isNullOrBlank()) return true
            return runCatching {
                val uri = Uri.parse(treeUriString)
                hasPersistedTreePermission(context, uri)
            }.getOrDefault(false)
        }

        fun requiresSafDestination(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
            DestinationPolicy.requiresSafDestination(sdkInt)

        /** A blank destination is valid only on API 29+, where MediaStore.Downloads applies. */
        fun isDestinationValid(
            context: Context,
            treeUriString: String?,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): Boolean {
            if (treeUriString.isNullOrBlank()) return !DestinationPolicy.requiresSafDestination(sdkInt)
            return isTreePermissionValid(context, treeUriString)
        }

        fun formatDisplayPath(treeUriString: String?): String {
            if (treeUriString.isNullOrBlank()) return "Downloads/VRKA"
            return runCatching {
                val docId = if (treeUriString.contains("/tree/")) {
                    val raw = treeUriString.substringAfter("/tree/").substringBefore("?")
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } else {
                    val uri = Uri.parse(treeUriString)
                    DocumentsContract.getTreeDocumentId(uri)
                }
                val cleanPath = if (docId.contains(":")) {
                    val parts = docId.split(":", limit = 2)
                    val sub = parts.getOrNull(1).orEmpty()
                    if (sub.isBlank()) {
                        if (parts[0].equals("primary", ignoreCase = true)) "Internal Storage" else parts[0]
                    } else {
                        sub
                    }
                } else {
                    docId
                }
                cleanPath.ifBlank { "Downloads/VRKA" }
            }.getOrDefault("Downloads/VRKA")
        }
    }
}

internal object OutputNameAllocator {
    fun next(existing: MutableSet<String>, requested: String): String {
        if (requested !in existing) return requested
        val dot = requested.lastIndexOf('.')
        val stem = if (dot > 0) requested.substring(0, dot) else requested
        val extension = if (dot > 0) requested.substring(dot) else ""
        for (index in 2..999) {
            val candidate = "$stem ($index)$extension"
            if (candidate !in existing) return candidate
        }
        return "$stem-${System.currentTimeMillis().toString(36).takeLast(8)}$extension"
    }
}

internal object SafeOutputNames {
    private const val MAX_STEM_BYTES = 160

    fun sanitize(requestedName: String, actualExtension: String): String {
        val extension = actualExtension
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .take(10)
        val rawStem = requestedName
            .substringBeforeLast('.', requestedName)
            .map { character ->
                if (character.code < 32 || character in "\\/:*?\"<>|") '_' else character
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.')
        val fallback = "VRKA-" + System.currentTimeMillis().toString(36).takeLast(8)
        val stem = truncateUtf8(rawStem.ifBlank { fallback }, MAX_STEM_BYTES)
            .trim(' ', '.')
            .ifBlank { fallback }
        return if (extension.isBlank()) stem else "$stem.$extension"
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end -= 1
        }
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end)
    }
}
