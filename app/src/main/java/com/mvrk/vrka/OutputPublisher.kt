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
    fun findPendingMediaStoreUri(pendingFilename: String): Uri?

    // SAF
    fun existingSafNames(tree: Uri): Set<String>
    fun findPendingSafUri(tree: Uri, pendingFilename: String): Uri?
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

    override fun findPendingMediaStoreUri(pendingFilename: String): Uri? = runCatching {
        if (pendingFilename.isBlank()) return null
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(pendingFilename)
        queryMediaStoreCollection(collection, projection, selection, selectionArgs)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val id = cursor.getLong(0)
                android.content.ContentUris.withAppendedId(collection, id)
            } else {
                null
            }
        }
    }.getOrNull()

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

    override fun findPendingSafUri(tree: Uri, pendingFilename: String): Uri? = runCatching {
        if (pendingFilename.isBlank()) return null
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameCol >= 0 && idCol >= 0) {
                    val name = cursor.getString(nameCol)
                    if (name == pendingFilename) {
                        val docId = cursor.getString(idCol)
                        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    }
                }
            }
            null
        }
    }.getOrNull()

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
            destination != null || !DestinationPolicy.requiresSafDestination(Build.VERSION.SDK_INT),
        ) { runCatching { context.getString(R.string.error_destination_required) }.getOrNull() ?: "A destination folder is required." }

        val effectiveJobId = jobId.ifBlank { "standalone-${System.currentTimeMillis()}" }
        val batchEntries = prepareBatchEntries(files, destination, effectiveJobId)
        activeJournal.recordBatchIntents(batchEntries)

        val published = if (destination != null) {
            publishToTreeBatch(files, Uri.parse(destination), batchEntries, shouldContinue, onPublished)
        } else {
            publishToDownloadsBatch(files, batchEntries, shouldContinue, onPublished)
        }
        files.forEach { (file, _) -> if (!file.delete()) file.deleteOnExit() }
        published
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

    fun findPendingMediaStoreUri(pendingFilename: String): Uri? =
        ops.findPendingMediaStoreUri(pendingFilename)

    fun findPendingSafUri(tree: Uri, pendingFilename: String): Uri? =
        ops.findPendingSafUri(tree, pendingFilename)

    /**
     * Checks and verifies MediaStore row finalization.
     * Updates DISPLAY_NAME and clears IS_PENDING (on API 29+).
     * If the update returns 0 affected rows, it checks whether the row was independently verified
     * as already finalized. Returns true ONLY when the resulting row is confirmed finalized.
     */
    fun finalizeMediaStore(uri: Uri, finalName: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        }
        val affected = runCatching { ops.updateMediaStore(uri, values, displayName = finalName, isPending = false) }.getOrDefault(0)
        if (affected > 0) {
            return verifyMediaStoreFinalized(uri, finalName)
        }
        return verifyMediaStoreFinalized(uri, finalName)
    }

    fun verifyMediaStoreFinalized(uri: Uri, expectedFinalName: String): Boolean = runCatching {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING)
        } else {
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        }
        ops.queryMediaStore(uri, projection, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (nameCol < 0) return false
            val currentName = cursor.getString(nameCol)
            if (currentName != expectedFinalName) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pendingCol = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                if (pendingCol >= 0) {
                    val isPending = cursor.getInt(pendingCol)
                    if (isPending != 0) return false
                }
            }
            true
        } ?: false
    }.getOrDefault(false)

    fun finalizeSaf(uri: Uri, finalName: String): Uri {
        val renamed = ops.renameSafDocument(uri, finalName)
        val target = renamed ?: uri
        if (ops.fileExists(target)) {
            return target
        }
        return uri
    }

    private fun publishToDownloads(
        file: File,
        entry: PublicationEntry,
    ): PublishedOutput {
        val mime = mimeType(entry.intendedFilename)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.pendingFilename)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            return PublishedOutput(uri, bytes, file.extension)
        } catch (error: Throwable) {
            activeJournal.markFailed(entry.transactionId, error.message.orEmpty())
            ops.deleteUri(uri)
            throw error
        }
    }

    private fun publishToDownloadsBatch(
        files: List<Pair<File, String>>,
        batchEntries: List<PublicationEntry>,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val created = mutableListOf<Uri>()
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
                created += result.uri
                onPublished(index, files.size)
                result
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                activeJournal.markFailed(txId, error.message.orEmpty())
            }
            created.forEach { uri -> runCatching { ops.deleteUri(uri) } }
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
        val created = mutableListOf<Uri>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, _) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val entry = batchEntries[index]
                currentTransactions += entry.transactionId

                val uri = checkNotNull(
                    ops.createSafDocument(tree, mimeType(entry.intendedFilename), entry.pendingFilename),
                ) { "The selected folder refused the new file." }
                created += uri
                activeJournal.updateProviderCreated(entry.transactionId, uri.toString())

                val bytes = try {
                    checkNotNull(ops.openOutputStream(uri, "w")) { "The selected folder could not be written." }.use { output ->
                        file.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                } catch (error: Throwable) {
                    activeJournal.markFailed(entry.transactionId, error.message.orEmpty())
                    ops.deleteSafDocument(uri)
                    throw error
                }
                if (file.length() > 0L && bytes != file.length()) {
                    val mismatch = java.io.IOException("SAF output size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
                    activeJournal.markFailed(entry.transactionId, mismatch.message.orEmpty())
                    ops.deleteSafDocument(uri)
                    throw mismatch
                }
                activeJournal.updateDataCopied(entry.transactionId, bytes)
                activeJournal.updateValidated(entry.transactionId)
                val finalUri = finalizeSaf(uri, entry.intendedFilename)
                activeJournal.updateFinalized(entry.transactionId, finalUri.toString())
                onPublished(index, files.size)
                PublishedOutput(finalUri, bytes, file.extension)
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                activeJournal.markFailed(txId, error.message.orEmpty())
            }
            created.forEach { uri -> runCatching { ops.deleteSafDocument(uri) } }
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
