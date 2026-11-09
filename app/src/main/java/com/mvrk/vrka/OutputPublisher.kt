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
import java.net.URLConnection
import java.util.UUID

internal data class PublishedOutput(
    val uri: Uri,
    val bytes: Long,
    val extension: String,
)

internal class OutputPublisher(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    journal: PublicationJournal? = null,
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
        ) { context.getString(R.string.error_destination_required) }

        val effectiveJobId = jobId.ifBlank { "standalone-${System.currentTimeMillis()}" }
        val published = if (destination != null) {
            publishToTreeBatch(files, Uri.parse(destination), effectiveJobId, shouldContinue, onPublished)
        } else {
            publishToDownloadsBatch(files, effectiveJobId, shouldContinue, onPublished)
        }
        files.forEach { (file, _) -> if (!file.delete()) file.deleteOnExit() }
        published
    }

    fun delete(uriText: String): Boolean = runCatching {
        context.contentResolver.delete(Uri.parse(uriText), null, null) > 0
    }.getOrDefault(false)

    fun nodeName(uriText: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(uriText),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    fun fileExists(uriText: String): Boolean = runCatching {
        val uri = Uri.parse(uriText)
        val cursorCount = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { it.count }
        }.getOrNull()
        if (cursorCount != null && cursorCount > 0) return true
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    private fun publishToDownloads(
        file: File,
        name: String,
        transactionId: String,
    ): PublishedOutput {
        val resolver = context.contentResolver
        val mime = mimeType(name)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/VRKA",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = checkNotNull(resolver.insert(collection, values)) {
            "Android could not create the Downloads entry."
        }
        activeJournal.updateProviderCreated(transactionId, uri.toString())
        try {
            val bytes = resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output) { "Android could not open the Downloads entry." }
                file.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (file.length() > 0L && bytes != file.length()) {
                throw java.io.IOException("Publication size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
            }
            activeJournal.updateDataCopied(transactionId, bytes)
            activeJournal.updateValidated(transactionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            activeJournal.updateFinalized(transactionId)
            return PublishedOutput(uri, bytes, file.extension)
        } catch (error: Throwable) {
            activeJournal.markFailed(transactionId, error.message.orEmpty())
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishToDownloadsBatch(
        files: List<Pair<File, String>>,
        jobId: String,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val created = mutableListOf<Uri>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, requestedName) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val safeName = SafeOutputNames.sanitize(requestedName, file.extension)
                val transactionId = UUID.randomUUID().toString()
                currentTransactions += transactionId
                val expectedBytes = file.length()
                val entry = PublicationEntry(
                    transactionId = transactionId,
                    jobId = jobId,
                    outputIndex = index,
                    totalOutputs = files.size,
                    intendedFilename = safeName,
                    stagingPath = file.absolutePath,
                    expectedSizeBytes = expectedBytes,
                    providerType = PublicationProviderType.MEDIA_STORE,
                    destinationIdentity = "Downloads/VRKA",
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                )
                activeJournal.recordIntent(entry)

                val result = publishToDownloads(
                    file = file,
                    name = safeName,
                    transactionId = transactionId,
                )
                created += result.uri
                onPublished(index, files.size)
                result
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                activeJournal.markFailed(txId, error.message.orEmpty())
            }
            created.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            throw error
        }
    }

    private fun publishToTreeBatch(
        files: List<Pair<File, String>>,
        tree: Uri,
        jobId: String,
        shouldContinue: () -> Boolean,
        onPublished: (index: Int, total: Int) -> Unit,
    ): List<PublishedOutput> {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val existing = existingNames(tree)
        val created = mutableListOf<Uri>()
        val currentTransactions = mutableListOf<String>()
        try {
            return files.mapIndexed { index, (file, requestedName) ->
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Publication cancelled")
                val safeName = SafeOutputNames.sanitize(requestedName, file.extension)
                val name = OutputNameAllocator.next(existing, safeName)
                existing += name
                val transactionId = UUID.randomUUID().toString()
                currentTransactions += transactionId
                val expectedBytes = file.length()
                val entry = PublicationEntry(
                    transactionId = transactionId,
                    jobId = jobId,
                    outputIndex = index,
                    totalOutputs = files.size,
                    intendedFilename = name,
                    stagingPath = file.absolutePath,
                    expectedSizeBytes = expectedBytes,
                    providerType = PublicationProviderType.SAF,
                    destinationIdentity = tree.toString(),
                    publicationPhase = PublicationPhase.INTENT_RECORDED,
                )
                activeJournal.recordIntent(entry)

                val uri = checkNotNull(
                    DocumentsContract.createDocument(resolver, parent, mimeType(name), name),
                ) { "The selected folder refused the new file." }
                created += uri
                activeJournal.updateProviderCreated(transactionId, uri.toString())

                val bytes = try {
                    resolver.openOutputStream(uri, "w").use { output ->
                        checkNotNull(output) { "The selected folder could not be written." }
                        file.inputStream().buffered().use { input -> input.copyTo(output) }
                    }
                } catch (error: Throwable) {
                    activeJournal.markFailed(transactionId, error.message.orEmpty())
                    DocumentsContract.deleteDocument(resolver, uri)
                    throw error
                }
                if (file.length() > 0L && bytes != file.length()) {
                    val mismatch = java.io.IOException("SAF output size mismatch: expected ${file.length()} bytes, wrote $bytes bytes")
                    activeJournal.markFailed(transactionId, mismatch.message.orEmpty())
                    DocumentsContract.deleteDocument(resolver, uri)
                    throw mismatch
                }
                activeJournal.updateDataCopied(transactionId, bytes)
                activeJournal.updateValidated(transactionId)
                activeJournal.updateFinalized(transactionId)
                onPublished(index, files.size)
                PublishedOutput(uri, bytes, file.extension)
            }
        } catch (error: Throwable) {
            currentTransactions.forEach { txId ->
                activeJournal.markFailed(txId, error.message.orEmpty())
            }
            created.forEach { uri -> runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
            throw error
        }
    }

    private fun existingNames(tree: Uri): MutableSet<String> {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val existing = mutableSetOf<String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val column = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            )
            while (cursor.moveToNext() && existing.size < MAX_EXISTING_NAMES) {
                if (column >= 0) cursor.getString(column)?.let(existing::add)
            }
        }
        return existing
    }

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
