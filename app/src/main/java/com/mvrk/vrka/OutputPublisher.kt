package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.mvrk.vrka.storage.JobJsonCodec
import com.mvrk.vrka.storage.PublicationStore
import java.io.File
import java.io.OutputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.util.UUID

internal data class PublishedOutput(
    val uri: Uri,
    val bytes: Long,
    val extension: String,
    val transactionId: String = "",
    val batchId: String = "",
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

internal data class ProviderReadback(
    val size: Long,
    val sha256: String?,
)

internal enum class ContentVerification {
    HASH_VERIFIED,
    SIZE_VERIFIED,
    MISMATCH,
    UNKNOWN,
}

internal enum class DeleteOutcome {
    DELETED,
    ALREADY_ABSENT,
    FAILED,
}

internal enum class ProviderObjectPresence {
    EXISTS,
    ABSENT,
    UNKNOWN,
}

internal data class SafDocumentFlags(
    val canWrite: Boolean?,
    val canDelete: Boolean?,
    val canRename: Boolean?,
)

internal data class SafCapabilities(
    val canCreate: Boolean?,
    val canEnumerate: Boolean,
    val hasReliableDocumentIdentity: Boolean,
    val canRename: Boolean?,
    val canReadBack: Boolean?,
    val canDelete: Boolean? = null,
    val canWrite: Boolean? = null,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("canCreate", canCreate)
        put("canEnumerate", canEnumerate)
        put("hasReliableDocumentIdentity", hasReliableDocumentIdentity)
        put("canRename", canRename)
        put("canReadBack", canReadBack)
        put("canDelete", canDelete)
        put("canWrite", canWrite)
    }.toString()

    companion object {
        fun fromJson(json: String?): SafCapabilities? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val value = org.json.JSONObject(json)
                SafCapabilities(
                    canCreate = value.optBooleanOrNull("canCreate"),
                    canEnumerate = value.optBoolean("canEnumerate", false),
                    hasReliableDocumentIdentity = value.optBoolean("hasReliableDocumentIdentity", false),
                    canRename = value.optBooleanOrNull("canRename"),
                    canReadBack = value.optBooleanOrNull("canReadBack"),
                    canDelete = value.optBooleanOrNull("canDelete"),
                    canWrite = value.optBooleanOrNull("canWrite"),
                )
            }.getOrNull()
        }

        private fun org.json.JSONObject.optBooleanOrNull(key: String): Boolean? =
            if (!has(key) || isNull(key)) null else getBoolean(key)
    }
}

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
    fun readBack(uri: Uri): ProviderReadback? = null

    fun existingMediaStoreNames(collection: Uri, relativePath: String): Set<String> {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val cursor = runCatching {
            queryMediaStoreCollection(collection, projection, null, null)
        }.getOrNull() ?: return emptySet()
        val names = HashSet<String>()
        cursor.use {
            val col = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (it.moveToNext() && names.size < 10_000) {
                if (col >= 0) it.getString(col)?.let(names::add)
            }
        }
        return names
    }

    fun probePresence(uri: Uri): ProviderObjectPresence = try {
        val cursor = queryMediaStore(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null)
        if (cursor != null) {
            cursor.use {
                if (it.count > 0 || it.moveToFirst()) ProviderObjectPresence.EXISTS else ProviderObjectPresence.ABSENT
            }
        } else {
            if (fileExists(uri)) ProviderObjectPresence.EXISTS else ProviderObjectPresence.UNKNOWN
        }
    } catch (_: java.io.FileNotFoundException) {
        ProviderObjectPresence.ABSENT
    } catch (_: SecurityException) {
        ProviderObjectPresence.UNKNOWN
    } catch (_: Throwable) {
        ProviderObjectPresence.UNKNOWN
    }

    // SAF
    fun existingSafNames(tree: Uri): Set<String>
    fun findPendingSafUri(tree: Uri, pendingFilename: String): UriLookupResult
    fun findSafDocumentUriByName(tree: Uri, displayName: String): UriLookupResult = UriLookupResult.Unknown("Provider cannot enumerate documents by name")
    fun querySafCapabilities(tree: Uri): SafCapabilities? = null
    fun querySafDocumentFlags(uri: Uri): SafDocumentFlags? = null
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

    override fun existingMediaStoreNames(collection: Uri, relativePath: String): Set<String> {
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val trimmed = relativePath.trimEnd('/')
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} = ?)"
        } else {
            null
        }
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("$trimmed/", trimmed)
        } else {
            null
        }
        val cursor = runCatching {
            queryMediaStoreCollection(collection, projection, selection, selectionArgs)
        }.getOrNull() ?: return emptySet()
        val names = HashSet<String>()
        cursor.use {
            val col = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            while (it.moveToNext() && names.size < 10_000) {
                if (col >= 0) it.getString(col)?.let(names::add)
            }
        }
        return names
    }

    override fun probePresence(uri: Uri): ProviderObjectPresence {
        val queryOutcome = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.count > 0 || cursor.moveToFirst()) ProviderObjectPresence.EXISTS else ProviderObjectPresence.ABSENT
            }
        } catch (_: java.io.FileNotFoundException) {
            return ProviderObjectPresence.ABSENT
        } catch (_: IllegalArgumentException) {
            return ProviderObjectPresence.ABSENT
        } catch (_: SecurityException) {
            return ProviderObjectPresence.UNKNOWN
        } catch (_: Throwable) {
            return ProviderObjectPresence.UNKNOWN
        }
        if (queryOutcome != null) return queryOutcome
        return try {
            context.contentResolver.openInputStream(uri)?.use { ProviderObjectPresence.EXISTS }
                ?: ProviderObjectPresence.UNKNOWN
        } catch (_: java.io.FileNotFoundException) {
            ProviderObjectPresence.ABSENT
        } catch (_: SecurityException) {
            ProviderObjectPresence.UNKNOWN
        } catch (_: Throwable) {
            ProviderObjectPresence.UNKNOWN
        }
    }

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

    override fun readBack(uri: Uri): ProviderReadback? = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.buffered().use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                size += read
            }
            ProviderReadback(size, digest.digest().joinToString("") { "%02x".format(it) })
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

    override fun findSafDocumentUriByName(tree: Uri, displayName: String): UriLookupResult {
        if (displayName.isBlank()) return UriLookupResult.DefinitelyAbsent
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ) ?: return UriLookupResult.Unknown("Provider returned no cursor for SAF name lookup")
        cursor.use {
            val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (idCol < 0 || nameCol < 0) return UriLookupResult.Unknown("SAF provider omitted identity columns")
            val matches = mutableListOf<Uri>()
            while (it.moveToNext()) {
                if (it.getString(nameCol) == displayName) {
                    it.getString(idCol)?.let { id -> matches += DocumentsContract.buildDocumentUriUsingTree(tree, id) }
                }
            }
            return when (matches.size) {
                0 -> UriLookupResult.DefinitelyAbsent
                1 -> UriLookupResult.Found(matches.single())
                else -> UriLookupResult.Unknown("Multiple SAF documents match the transaction final name")
            }
        }
    }

    override fun querySafCapabilities(tree: Uri): SafCapabilities? = runCatching {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val parentFlags = runCatching {
            context.contentResolver.query(
                parent,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getInt(0) else null
            }
        }.getOrNull()
        val canCreate = parentFlags?.let {
            it and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
            ),
            null,
            null,
            null,
        ) ?: return SafCapabilities(canCreate, false, false, null, null, null, null)
        var canEnumerate = false
        var reliableIdentity = false
        var canRename: Boolean? = null
        var canDelete: Boolean? = null
        var canWrite: Boolean? = null
        cursor.use { rows ->
            val idCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsCol = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            canEnumerate = idCol >= 0 && nameCol >= 0
            reliableIdentity = idCol >= 0
            while (rows.moveToNext()) {
                if (mimeCol >= 0 &&
                    rows.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                ) {
                    continue
                }
                if (flagsCol >= 0 && !rows.isNull(flagsCol)) {
                    val docFlags = rows.getInt(flagsCol)
                    val rowRename = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
                    val rowDelete = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                    val rowWrite = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                    canRename = if (canRename == null) rowRename else (canRename && rowRename)
                    canDelete = if (canDelete == null) rowDelete else (canDelete && rowDelete)
                    canWrite = if (canWrite == null) rowWrite else (canWrite && rowWrite)
                }
            }
        }
        // Do not read unrelated existing files during capability probing; readback is verified
        // against the document VRKA itself creates.
        SafCapabilities(canCreate, canEnumerate, reliableIdentity, canRename, null, canDelete, canWrite)
    }.getOrNull()

    override fun querySafDocumentFlags(uri: Uri): SafDocumentFlags? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return@use null
            val flags = cursor.getInt(0)
            SafDocumentFlags(
                canWrite = (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
                canDelete = (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0,
                canRename = (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0,
            )
        }
    }.getOrNull()

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
    journal: PublicationStore,
    internal val ops: PublicationStorageOps = DefaultPublicationStorageOps(context),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val mediaStoreCollection: Uri? = null,
    private val strictIntegrityAudit: Boolean = false,
) {
    private val publishLock = Any()
    val activeJournal: PublicationStore = journal

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
        attemptNumber: Int = 1,
        batchId: String = "",
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
        val effectiveBatchId = batchId.ifBlank { "batch-${UUID.randomUUID()}" }
        val tree = destination?.let { Uri.parse(it) }
        val capabilities = tree?.let { querySafCapabilities(it) }
        if (tree != null) {
            if (capabilities == null) {
                throw IllegalStateException(
                    "The selected folder cannot be queried safely for publication recovery. Choose a different folder.",
                )
            }
            if (!capabilities.canEnumerate || !capabilities.hasReliableDocumentIdentity) {
                throw IllegalStateException(
                    "The selected folder cannot enumerate its files reliably, so crash recovery would be unsafe. Choose a different folder.",
                )
            }
            if (capabilities.canCreate == false) {
                throw IllegalStateException(
                    "The selected folder does not allow creating new files. Choose a different folder.",
                )
            }
            if (capabilities.canDelete == false) {
                throw IllegalStateException(
                    "The selected folder does not support deleting incomplete temporary files, so safe publication cannot proceed. Choose a different folder.",
                )
            }
            if (capabilities.canWrite == false) {
                throw IllegalStateException(
                    "The selected folder does not grant write access. Choose a different folder.",
                )
            }
        }
        val batchEntries = prepareBatchEntries(
            files = files,
            destinationTreeUri = destination,
            capabilities = capabilities,
            jobId = effectiveJobId,
            attemptNumber = attemptNumber,
            batchId = effectiveBatchId,
        )
        activeJournal.recordBatchIntents(batchEntries)

        return if (destination != null) {
            publishToTreeBatch(files, Uri.parse(destination), capabilities, batchEntries, shouldContinue, onPublished)
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
        capabilities: SafCapabilities?,
        jobId: String,
        attemptNumber: Int,
        batchId: String,
    ): List<PublicationEntry> {
        val existingNamesSet = if (destinationTreeUri != null) {
            existingNames(Uri.parse(destinationTreeUri))
        } else {
            ops.existingMediaStoreNames(
                collection = targetMediaStoreCollection(),
                relativePath = Environment.DIRECTORY_DOWNLOADS + "/VRKA",
            ).toMutableSet()
        }
        val providerType = if (destinationTreeUri != null) {
            PublicationProviderType.SAF
        } else {
            PublicationProviderType.MEDIA_STORE
        }
        val destIdentity = destinationTreeUri ?: "Downloads/VRKA"

        return files.mapIndexed { index, (file, requestedName) ->
            val safeName = SafeOutputNames.sanitize(requestedName, file.extension)
            val allocated = OutputNameAllocator.next(existingNamesSet, safeName)
            existingNamesSet += allocated
            val finalName = allocated
            val txId = UUID.randomUUID().toString()
            val actualFinalName = if (providerType == PublicationProviderType.SAF) {
                addTransactionMarker(finalName, txId)
            } else {
                finalName
            }
            val pendingName = ".vrka_pending_${txId}_$actualFinalName"
            val needsHash = strictIntegrityAudit || file.length() <= HASH_READBACK_THRESHOLD_BYTES
            PublicationEntry(
                transactionId = txId,
                jobId = jobId,
                outputIndex = index,
                totalOutputs = files.size,
                intendedFilename = actualFinalName,
                pendingFilename = pendingName,
                stagingPath = file.absolutePath,
                expectedSizeBytes = file.length(),
                providerType = providerType,
                destinationIdentity = destIdentity,
                publicationPhase = PublicationPhase.INTENT,
                attemptNumber = attemptNumber,
                attemptId = JobJsonCodec.attemptIdFor(jobId, attemptNumber),
                batchId = batchId,
                expectedSha256 = if (needsHash) runCatching { sha256(file) }.getOrNull() else null,
                verificationLevel = "",
                providerCapabilitiesJson = capabilities?.toJson(),
            )
        }
    }

    private fun addTransactionMarker(name: String, transactionId: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val token = transactionId.replace("-", "").take(10)
        return "$stem [vrka-$token]$extension"
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(uriText: String): Boolean = deleteWithOutcome(uriText) == DeleteOutcome.DELETED

    fun deleteWithOutcome(uriText: String, reason: String = "provider_cleanup"): DeleteOutcome {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return DeleteOutcome.ALREADY_ABSENT
        Log.i("VRKA_TRACE", "CLEANUP_DELETE_CALLER stage=deleteWithOutcome providerUri=$uri deleteReason=$reason")
        val deleteAttempt = runCatching {
            if (PublicationUriRules.isMediaStoreUri(uri)) {
                ops.deleteUri(uri) || ops.deleteSafDocument(uri)
            } else {
                ops.deleteSafDocument(uri) || ops.deleteUri(uri)
            }
        }
        if (deleteAttempt.getOrDefault(false)) return DeleteOutcome.DELETED
        if (deleteAttempt.exceptionOrNull() != null) return DeleteOutcome.FAILED
        return when (ops.probePresence(uri)) {
            ProviderObjectPresence.ABSENT -> DeleteOutcome.ALREADY_ABSENT
            ProviderObjectPresence.EXISTS,
            ProviderObjectPresence.UNKNOWN,
            -> DeleteOutcome.FAILED
        }
    }

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

    fun findSafUriByName(tree: Uri, displayName: String): UriLookupResult = try {
        ops.findSafDocumentUriByName(tree, displayName)
    } catch (error: Throwable) {
        UriLookupResult.Unknown(error.message.orEmpty().take(200))
    }

    fun readBack(uri: Uri): ProviderReadback? = try {
        ops.readBack(uri)
    } catch (error: Throwable) {
        null
    }

    /**
     * Compares provider bytes with the staged source. A provider that cannot be read back
     * never yields a hash-verified or size-verified result.
     */
    fun verifyProviderContent(entry: PublicationEntry, uri: Uri): ContentVerification {
        val expectedSha = entry.expectedSha256
        if (expectedSha.isNullOrBlank() && entry.expectedSizeBytes > HASH_READBACK_THRESHOLD_BYTES) {
            val providerSize = runCatching { ops.getFileSize(uri) }.getOrDefault(-1L)
            return when {
                providerSize == entry.expectedSizeBytes && providerSize > 0L -> ContentVerification.SIZE_VERIFIED
                providerSize > 0L && providerSize != entry.expectedSizeBytes -> ContentVerification.MISMATCH
                else -> {
                    val evidence = readBack(uri) ?: return ContentVerification.UNKNOWN
                    if (evidence.size != entry.expectedSizeBytes) ContentVerification.MISMATCH
                    else if (evidence.size > 0L) ContentVerification.SIZE_VERIFIED
                    else ContentVerification.UNKNOWN
                }
            }
        }
        val evidence = readBack(uri) ?: return ContentVerification.UNKNOWN
        if (entry.expectedSizeBytes > 0 && evidence.size != entry.expectedSizeBytes) {
            return ContentVerification.MISMATCH
        }
        if (!expectedSha.isNullOrBlank()) {
            return when {
                evidence.sha256.isNullOrBlank() -> ContentVerification.SIZE_VERIFIED
                evidence.sha256.equals(expectedSha, ignoreCase = true) -> ContentVerification.HASH_VERIFIED
                else -> ContentVerification.MISMATCH
            }
        }
        return if (evidence.size > 0) ContentVerification.SIZE_VERIFIED else ContentVerification.UNKNOWN
    }

    fun querySafCapabilities(tree: Uri): SafCapabilities? = try {
        ops.querySafCapabilities(tree)
    } catch (error: Throwable) {
        null
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
                if (expectedPendingName != null && name == expectedPendingName && name != expectedFinalName) {
                    return ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
                }
            } else if (expectedPendingName != null && name == expectedPendingName && name != expectedFinalName) {
                return ProviderInspection(ProviderObjectStatus.PENDING, displayName = name)
            }
            return when {
                isMediaStoreFinalNameMatch(name, expectedFinalName) ->
                    ProviderInspection(ProviderObjectStatus.VERIFIED, displayName = name)
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
                val renamed = ops.renameSafDocument(uri, entry.intendedFilename)
                val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull()
                val lookup = tree?.let { findSafUriByName(it, entry.intendedFilename) }
                val target = renamed ?: (lookup as? UriLookupResult.Found)?.uri ?: uri
                val uniquelyIdentified = tree != null &&
                    when (lookup) {
                        is UriLookupResult.Found -> sameDocument(lookup.uri, target)
                        UriLookupResult.DefinitelyAbsent,
                        is UriLookupResult.Unknown,
                        null,
                        -> false
                    }
                val inspection = if (tree == null || !uniquelyIdentified) {
                    ProviderInspection(
                        ProviderObjectStatus.UNKNOWN,
                        detail = "Finalized SAF document is not uniquely identifiable by its transaction name",
                    )
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
        val nameMatches = when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE ->
                isMediaStoreFinalNameMatch(inspection.displayName.orEmpty(), entry.intendedFilename) ||
                    inspection.displayName == entry.pendingFilename
            PublicationProviderType.SAF ->
                inspection.displayName == entry.pendingFilename
        }
        if (inspection.status != ProviderObjectStatus.PENDING || !nameMatches) {
            return ObjectOwnership.NOT_OWNED
        }
        val providerUri = runCatching { Uri.parse(entry.providerUri) }.getOrNull()
            ?: return ObjectOwnership.NOT_OWNED
        val discovered = when (entry.providerType) {
            PublicationProviderType.MEDIA_STORE -> {
                val byIntended = findPendingMediaStoreUri(entry.intendedFilename)
                if (byIntended is UriLookupResult.Found) byIntended
                else findPendingMediaStoreUri(entry.pendingFilename)
            }
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
        Log.i(
            "VRKA_TRACE",
            "CLEANUP_DELETE_CALLER stage=cleanupPendingObject jobId=${entry.jobId} attempt=${entry.attemptNumber} txId=${entry.transactionId} providerUri=$uri deleteReason=cleanup_pending_object",
        )
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
     * Checks and verifies MediaStore row finalization idempotently.
     * Updates DISPLAY_NAME and clears IS_PENDING (on API 29+). If the row update returns 0 rows
     * or throws because the provider already finalized or deduplicated the name, re-inspection is
     * authoritative; unresolved errors are propagated explicitly rather than swallowed.
     */
    fun finalizeMediaStore(uri: Uri, finalName: String): Boolean {
        val values = ContentValues().apply {
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        }
        val updateResult = runCatching {
            ops.updateMediaStore(uri, values, displayName = null, isPending = false)
        }
        if (verifyMediaStoreFinalized(uri, finalName)) {
            return true
        }
        val fallbackResult = runCatching {
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            ops.updateMediaStore(uri, values, displayName = finalName, isPending = false)
        }
        if (verifyMediaStoreFinalized(uri, finalName)) {
            return true
        }
        updateResult.exceptionOrNull()?.let { throw it }
        fallbackResult.exceptionOrNull()?.let { throw it }
        return false
    }

    fun verifyMediaStoreFinalized(uri: Uri, expectedFinalName: String): Boolean =
        inspectMediaStoreOutput(uri, expectedFinalName).status == ProviderObjectStatus.VERIFIED

    private fun isMediaStoreFinalNameMatch(observedName: String, expectedFinalName: String): Boolean {
        if (observedName.isBlank() || observedName.startsWith(".vrka_pending_")) return false
        if (observedName == expectedFinalName) return true
        val expectedNormalized = java.text.Normalizer.normalize(expectedFinalName, java.text.Normalizer.Form.NFC)
        val observedNormalized = java.text.Normalizer.normalize(observedName, java.text.Normalizer.Form.NFC)
        if (observedNormalized == expectedNormalized) return true
        val expectedDot = expectedNormalized.lastIndexOf('.')
        val observedDot = observedNormalized.lastIndexOf('.')
        val expectedStem = if (expectedDot > 0) expectedNormalized.substring(0, expectedDot) else expectedNormalized
        val expectedExt = if (expectedDot > 0) expectedNormalized.substring(expectedDot) else ""
        val observedStem = if (observedDot > 0) observedNormalized.substring(0, observedDot) else observedNormalized
        val observedExt = if (observedDot > 0) observedNormalized.substring(observedDot) else ""
        if (!observedExt.equals(expectedExt, ignoreCase = true)) return false
        if (observedStem == expectedStem) return true
        val escapedStem = Regex.escape(expectedStem)
        return Regex("^$escapedStem(?: \\(\\d+\\)|_\\d+)$").matches(observedStem)
    }

    fun finalizeSaf(uri: Uri, finalName: String): Uri =
        ops.renameSafDocument(uri, finalName) ?: uri

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        return runCatching {
            DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second) &&
                first.authority == second.authority
        }.getOrDefault(false)
    }

    fun hasTransactionTag(filename: String, transactionId: String): Boolean {
        val token = transactionId.replace("-", "").take(10)
        return token.isNotBlank() && filename.contains("[vrka-$token]")
    }

    /** SAF recovery depends on the transaction marker naming exactly one document. */
    fun safFinalIdentityIsUnique(entry: PublicationEntry, uri: Uri): Boolean {
        if (entry.providerType != PublicationProviderType.SAF) return true
        if (!hasTransactionTag(entry.intendedFilename, entry.transactionId)) return false
        val tree = runCatching { Uri.parse(entry.destinationIdentity) }.getOrNull() ?: return false
        return when (val found = findSafUriByName(tree, entry.intendedFilename)) {
            is UriLookupResult.Found -> sameDocument(found.uri, uri)
            UriLookupResult.DefinitelyAbsent -> false
            is UriLookupResult.Unknown -> false
        }
    }

    /**
     * Verifies provider integrity using tiered guarantees:
     * - Files <= 32 MiB, strict-audit runs, or providers omitting SIZE require full read-back SHA-256 (`HASH_VERIFIED`).
     * - Files > 32 MiB with verified stream byte count and matching provider OpenableColumns.SIZE record `SIZE_VERIFIED`
     *   and never falsely promote to `HASH_VERIFIED`.
     */
    private fun verifyProviderReadBack(entry: PublicationEntry, uri: Uri, file: File) {
        val providerSize = runCatching { ops.getFileSize(uri) }.getOrDefault(-1L)
        if (providerSize > 0L && providerSize != file.length()) {
            throw java.io.IOException(
                "Provider size mismatch for ${entry.intendedFilename}: expected ${file.length()} bytes, found $providerSize bytes",
            )
        }
        val requiresHash = strictIntegrityAudit ||
            file.length() <= HASH_READBACK_THRESHOLD_BYTES ||
            providerSize <= 0L
        if (!requiresHash) {
            activeJournal.updateVerification(entry.transactionId, "SIZE_VERIFIED", null)
            return
        }
        val expectedSha = entry.expectedSha256?.takeIf { it.isNotBlank() }
            ?: runCatching { sha256(file) }.getOrNull()
        val evidence = readBack(uri)
            ?: throw java.io.IOException(
                "Provider readback failed for ${entry.intendedFilename}: the stored bytes are unavailable",
            )
        if (evidence.size != file.length()) {
            throw java.io.IOException(
                "Provider readback size mismatch for ${entry.intendedFilename}: expected ${file.length()} bytes, found ${evidence.size} bytes",
            )
        }
        val level = when {
            expectedSha.isNullOrBlank() -> "SIZE_VERIFIED"
            evidence.sha256.isNullOrBlank() -> "SIZE_VERIFIED"
            evidence.sha256.equals(expectedSha, ignoreCase = true) -> "HASH_VERIFIED"
            else -> throw java.io.IOException(
                "Provider readback hash mismatch for ${entry.intendedFilename}",
            )
        }
        activeJournal.updateVerification(entry.transactionId, level, evidence.sha256)
    }

    private fun targetMediaStoreCollection(): Uri =
        mediaStoreCollection ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }

    private fun publishToDownloads(
        file: File,
        entry: PublicationEntry,
    ): PublishedOutput {
        val mime = mimeType(entry.intendedFilename)
        val collection = targetMediaStoreCollection()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.intendedFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (sdkInt >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VRKA",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(ops.insertMediaStore(collection, values, displayName = entry.intendedFilename, isPending = true)) {
            "Android could not create the Downloads entry."
        }
        activeJournal.updateProviderCreated(entry.transactionId, uri.toString())
        var verifiedContent = false
        try {
            val bytes = checkNotNull(ops.openOutputStream(uri, "w")) { "Android could not open the Downloads entry." }.use { output ->
                file.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (file.length() > 0L && bytes != file.length()) {
                throw java.io.IOException("Publication size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
            }
            activeJournal.updateDataCopied(entry.transactionId, bytes)
            verifyProviderReadBack(entry, uri, file)
            verifiedContent = true
            activeJournal.updateValidated(entry.transactionId)
            val finalized = finalizeMediaStore(uri, entry.intendedFilename)
            val inspection = inspectMediaStoreOutput(uri, entry.intendedFilename, entry.pendingFilename)
            if (!finalized || inspection.status != ProviderObjectStatus.VERIFIED) {
                throw java.io.IOException(
                    "MediaStore finalization failed for ${entry.intendedFilename}: ${inspection.status.name} ${inspection.detail}".trim(),
                )
            }
            val observedName = inspection.displayName
            if (!observedName.isNullOrBlank() && observedName != entry.intendedFilename) {
                activeJournal.updateIntendedFilename(entry.transactionId, observedName)
            }
            activeJournal.updateFinalized(entry.transactionId)
            return PublishedOutput(uri, bytes, file.extension, entry.transactionId, entry.batchId)
        } catch (error: Throwable) {
            runCatching { activeJournal.markFailed(entry.transactionId, error.message.orEmpty()) }
            if (!verifiedContent) {
                val deleted = runCatching { ops.deleteUri(uri) }.getOrDefault(false)
                if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                    runCatching {
                        activeJournal.markCleanupPending(
                            entry.transactionId,
                            "Could not delete partial provider output: $uri",
                        )
                    }
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
        capabilities: SafCapabilities?,
        batchEntries: List<PublicationEntry>,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val createdByTx = linkedMapOf<String, Uri>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, _) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val entry = batchEntries[index]
                currentTransactions += entry.transactionId

                val mime = mimeType(entry.intendedFilename)
                var directFinalName = capabilities?.canRename == false
                var uri: Uri = checkNotNull(
                    ops.createSafDocument(
                        tree,
                        mime,
                        if (directFinalName) entry.intendedFilename else entry.pendingFilename,
                    ),
                ) { "The selected folder refused the new file." }

                val initialFlags = ops.querySafDocumentFlags(uri)
                if (initialFlags?.canDelete == false || initialFlags?.canWrite == false) {
                    val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                    if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                entry.transactionId,
                                "Created SAF file refuses delete/write and could not be removed: $uri",
                            )
                        }
                    }
                    throw IllegalStateException(
                        "The selected folder does not allow safe temporary-file deletion or writing. Choose a different folder.",
                    )
                }

                if (!directFinalName && initialFlags?.canRename == false) {
                    val pendingDeleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                    if (!pendingDeleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                entry.transactionId,
                                "Could not delete zero-byte non-renameable pending SAF file: $uri",
                            )
                        }
                        throw java.io.IOException("Provider refused rename and failed to remove empty temporary file.")
                    }
                    directFinalName = true
                    uri = checkNotNull(ops.createSafDocument(tree, mime, entry.intendedFilename)) {
                        "The selected folder refused the new file."
                    }
                    val directFlags = ops.querySafDocumentFlags(uri)
                    if (directFlags?.canDelete == false || directFlags?.canWrite == false) {
                        runCatching { ops.deleteSafDocument(uri) }
                        throw IllegalStateException(
                            "The selected folder does not allow safe deletion or writing on created files. Choose a different folder.",
                        )
                    }
                }

                createdByTx[entry.transactionId] = uri
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
                try {
                    verifyProviderReadBack(entry, uri, file)
                } catch (error: Throwable) {
                    runCatching { activeJournal.markFailed(entry.transactionId, error.message.orEmpty()) }
                    val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                    if (!deleted && inspectOutput(entry.copy(providerUri = uri.toString())).status != ProviderObjectStatus.ABSENT) {
                        runCatching {
                            activeJournal.markCleanupPending(
                                entry.transactionId,
                                "Could not delete unverified SAF output: $uri",
                            )
                        }
                    }
                    throw error
                }
                activeJournal.updateValidated(entry.transactionId)

                val finalization = if (directFinalName) {
                    val lookup = findSafUriByName(tree, entry.intendedFilename)
                    val unique = when (lookup) {
                        is UriLookupResult.Found -> sameDocument(lookup.uri, uri)
                        UriLookupResult.DefinitelyAbsent,
                        is UriLookupResult.Unknown,
                        -> false
                    }
                    val inspection = if (!unique) {
                        ProviderInspection(
                            ProviderObjectStatus.UNKNOWN,
                            detail = "Direct SAF document is not uniquely identifiable by its transaction name",
                        )
                    } else {
                        inspectSafOutput(uri, entry.intendedFilename, entry.pendingFilename, tree)
                    }
                    ProviderFinalization(inspection, uri)
                } else {
                    finalizeEntry(entry.copy(providerUri = uri.toString())).also { result ->
                        createdByTx[entry.transactionId] = result.uri
                    }
                }
                if (finalization.inspection.status != ProviderObjectStatus.VERIFIED) {
                    throw java.io.IOException(
                        "SAF finalization failed for ${entry.intendedFilename}: ${finalization.inspection.status.name} ${finalization.inspection.detail}",
                    )
                }
                val finalUri = finalization.uri
                createdByTx[entry.transactionId] = finalUri
                activeJournal.updateFinalized(entry.transactionId, finalUri.toString())
                onPublished(index, files.size)
                PublishedOutput(finalUri, bytes, file.extension, entry.transactionId, entry.batchId)
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                runCatching { activeJournal.markFailed(txId, error.message.orEmpty()) }
            }
            createdByTx.forEach { (txId, uri) ->
                val entry = batchEntries.firstOrNull { it.transactionId == txId }
                if (entry != null && !safDeletionIsSafe(entry, tree)) {
                    // Duplicate or unqueryable final-name candidates are never deleted.
                    runCatching {
                        activeJournal.markCleanupPending(
                            txId,
                            "Could not verify unique ownership of the partial SAF output: $uri",
                        )
                    }
                    return@forEach
                }
                val deleted = runCatching { ops.deleteSafDocument(uri) }.getOrDefault(false)
                if (!deleted) {
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

    private fun safDeletionIsSafe(entry: PublicationEntry, tree: Uri): Boolean =
        when (findSafUriByName(tree, entry.intendedFilename)) {
            is UriLookupResult.Found -> true
            UriLookupResult.DefinitelyAbsent -> when (findPendingSafUri(tree, entry.pendingFilename)) {
                is UriLookupResult.Found,
                UriLookupResult.DefinitelyAbsent,
                -> true
                is UriLookupResult.Unknown -> false
            }
            is UriLookupResult.Unknown -> false
        }

    private fun existingNames(tree: Uri): MutableSet<String> = ops.existingSafNames(tree).toMutableSet()

    private fun mimeType(name: String): String = guessMimeType(name)

    companion object {
        internal const val HASH_READBACK_THRESHOLD_BYTES = 32L * 1024L * 1024L
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
    private const val MAX_STEM_BYTES = 120

    fun sanitize(requestedName: String, actualExtension: String): String {
        val extension = actualExtension
            .lowercase()
            .filter(Char::isLetterOrDigit)
            .take(10)
        val normalized = java.text.Normalizer.normalize(
            requestedName.substringBeforeLast('.', requestedName),
            java.text.Normalizer.Form.NFC,
        )
        val rawStem = normalized
            .map { character ->
                if (character.code < 32 || character in "\\/:*?\"<>|\r\n\t") '_' else character
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
