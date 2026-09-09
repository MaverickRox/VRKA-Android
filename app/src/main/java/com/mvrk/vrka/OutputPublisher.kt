package com.mvrk.vrka

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.net.URLConnection

internal class OutputPublisher(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    fun publish(
        file: File,
        requestedName: String = file.name,
        destinationTreeUri: String? = null,
    ): Uri {
        require(file.isFile) { "Completed output is missing." }
        val safeName = SafeOutputNames.sanitize(requestedName, file.extension)
        val destination = destinationTreeUri?.takeIf { it.isNotBlank() }
            ?: settingsRepository.settings.value.outputTreeUri.takeIf { it.isNotBlank() }
        val uri = if (destination != null) {
            publishToTree(file, Uri.parse(destination), safeName)
        } else {
            publishToDownloads(file, safeName)
        }
        if (!file.delete()) file.deleteOnExit()
        return uri
    }

    fun delete(uriText: String): Boolean = runCatching {
        context.contentResolver.delete(Uri.parse(uriText), null, null) > 0
    }.getOrDefault(false)

    private fun publishToDownloads(file: File, name: String): Uri {
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
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output) { "Android could not open the Downloads entry." }
                file.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishToTree(file: File, tree: Uri, requestedName: String): Uri {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val name = availableName(tree, requestedName)
        val uri = checkNotNull(
            DocumentsContract.createDocument(resolver, parent, mimeType(name), name),
        ) { "The selected folder refused the new file." }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output) { "The selected folder could not be written." }
                file.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            return uri
        } catch (error: Throwable) {
            DocumentsContract.deleteDocument(resolver, uri)
            throw error
        }
    }

    private fun availableName(tree: Uri, requested: String): String {
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
            while (cursor.moveToNext() && existing.size < 10_000) {
                if (column >= 0) existing += cursor.getString(column)
            }
        }
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

    private fun mimeType(name: String): String =
        URLConnection.guessContentTypeFromName(name)
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

    companion object {
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
