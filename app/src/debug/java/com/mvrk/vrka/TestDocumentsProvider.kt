package com.mvrk.vrka

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException

enum class TestSafRenameMode {
    IN_PLACE,
    NEW_ID,
    UNSUPPORTED,
    NULL_RETURN,
    THROW,
    MODIFY_NAME,
    DUPLICATE,
}

data class TestSafDoc(
    val id: String,
    val parentId: String,
    var name: String,
    val mimeType: String,
    val file: File,
)

/**
 * Test-only SAF provider used by the emulator protocol tests. It implements the public
 * DocumentsContract call/query contract directly, so providers that replace document IDs or
 * fail individual capabilities can be simulated without a platform-signature permission.
 */
class TestDocumentsProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "com.mvrk.vrka.phase0docs"
        private const val ROOT_ID = "root"
        private const val METHOD_CREATE = "android:createDocument"
        private const val METHOD_RENAME = "android:renameDocument"
        private const val METHOD_DELETE = "android:deleteDocument"
        private const val EXTRA_URI = "uri"

        val TREE_URI: Uri = Uri.parse("content://$AUTHORITY/tree/$ROOT_ID")

        var renameMode = TestSafRenameMode.IN_PLACE
        var failChildQuery = false
        var failReadback = false
        var failDelete = false
        var permissionRevoked = false
        var omitChildIdentityColumns = false

        private var nextId = 1000L
        private val docs = LinkedHashMap<String, TestSafDoc>()
        private var storageRoot: File? = null

        fun reset(context: Context) {
            renameMode = TestSafRenameMode.IN_PLACE
            failChildQuery = false
            failReadback = false
            failDelete = false
            permissionRevoked = false
            omitChildIdentityColumns = false
            docs.values.forEach { it.file.delete() }
            docs.clear()
            nextId = 1000L
            storageRoot = File(context.cacheDir, "phase0-saf").apply {
                deleteRecursively()
                mkdirs()
            }
        }

        fun docsSnapshot(): List<TestSafDoc> = docs.values.toList()

        fun clear() {
            docs.values.forEach { it.file.delete() }
            docs.clear()
        }

        fun findByName(name: String): TestSafDoc? = docs.values.firstOrNull { it.name == name }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (permissionRevoked) throw SecurityException("Test SAF permission revoked")
        val segments = uri.pathSegments
        val childrenIndex = segments.indexOf("children")
        val documentIndex = segments.indexOf("document")
        return when {
            childrenIndex >= 0 -> {
                if (failChildQuery) throw IllegalStateException("Test child query failure")
                childCursor(projection)
            }
            documentIndex >= 0 && documentIndex + 1 < segments.size -> {
                val id = segments[documentIndex + 1]
                val doc = if (id == ROOT_ID) rootDoc() else docs[id]
                if (doc == null) emptyCursor(projection) else docCursor(projection, listOf(doc))
            }
            else -> null
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (permissionRevoked) throw SecurityException("Test SAF permission revoked")
        val target = bundleUri(extras)
        return when (method) {
            METHOD_CREATE -> {
                val parentId = target?.pathSegments?.lastOrNull() ?: ROOT_ID
                val mimeType = extras?.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    ?: "application/octet-stream"
                val displayName = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: "untitled"
                val id = "doc-${nextId++}"
                val file = File(requireRoot(), id).apply { createNewFile() }
                docs[id] = TestSafDoc(id, parentId, displayName, mimeType, file)
                Bundle().apply { putParcelable(EXTRA_URI, documentUri(id)) }
            }
            METHOD_RENAME -> {
                val id = target?.pathSegments?.lastOrNull()
                    ?: throw FileNotFoundException("Missing document URI")
                val displayName = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: throw IllegalArgumentException("Missing display name")
                rename(id, displayName)
            }
            METHOD_DELETE -> {
                val id = target?.pathSegments?.lastOrNull()
                    ?: throw FileNotFoundException("Missing document URI")
                if (failDelete) throw IllegalStateException("Test delete failure")
                val doc = docs.remove(id) ?: throw FileNotFoundException(id)
                doc.file.delete()
                Bundle.EMPTY
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (permissionRevoked) throw SecurityException("Test SAF permission revoked")
        if (failReadback && mode.startsWith("r")) throw IllegalStateException("Test readback failure")
        val id = uri.pathSegments.lastOrNull() ?: throw FileNotFoundException(uri.toString())
        val doc = docs[id] ?: throw FileNotFoundException(id)
        return ParcelFileDescriptor.open(doc.file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String? =
        docs[uri.pathSegments.lastOrNull()]?.mimeType

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    @Suppress("DEPRECATION")
    private fun bundleUri(extras: Bundle?): Uri? =
        extras?.getParcelable(EXTRA_URI) as? Uri

    private fun rename(documentId: String, displayName: String): Bundle? {
        val doc = docs[documentId] ?: throw FileNotFoundException(documentId)
        return when (renameMode) {
            TestSafRenameMode.IN_PLACE -> {
                doc.name = displayName
                bundleWith(documentUri(documentId))
            }
            TestSafRenameMode.NEW_ID -> {
                val newId = "doc-${nextId++}"
                val newFile = File(requireRoot(), newId).apply { createNewFile() }
                doc.file.copyTo(newFile, overwrite = true)
                val newDoc = TestSafDoc(newId, doc.parentId, displayName, doc.mimeType, newFile)
                docs.remove(documentId)
                docs[newId] = newDoc
                doc.file.delete()
                bundleWith(documentUri(newId))
            }
            TestSafRenameMode.UNSUPPORTED -> throw UnsupportedOperationException("Rename not supported")
            TestSafRenameMode.NULL_RETURN -> {
                doc.name = displayName
                Bundle.EMPTY
            }
            TestSafRenameMode.THROW -> throw IllegalStateException("Rename failed")
            TestSafRenameMode.MODIFY_NAME -> {
                doc.name = "provider-adjusted-$displayName"
                bundleWith(documentUri(documentId))
            }
            TestSafRenameMode.DUPLICATE -> {
                doc.name = displayName
                val duplicateId = "doc-${nextId++}"
                val duplicateFile = File(requireRoot(), duplicateId).apply { createNewFile() }
                docs[duplicateId] = TestSafDoc(duplicateId, doc.parentId, displayName, doc.mimeType, duplicateFile)
                bundleWith(documentUri(documentId))
            }
        }
    }

    private fun bundleWith(uri: Uri): Bundle = Bundle().apply { putParcelable(EXTRA_URI, uri) }

    private fun requireRoot(): File = storageRoot ?: File(
        context?.cacheDir ?: error("No context"),
        "phase0-saf",
    ).apply { mkdirs() }.also { storageRoot = it }

    private fun rootDoc(): TestSafDoc =
        TestSafDoc(ROOT_ID, "", "VRKA Test Documents", DocumentsContract.Document.MIME_TYPE_DIR, requireRoot())

    private fun documentUri(id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(TREE_URI, id)

    private fun childCursor(projection: Array<out String>?): Cursor {
        val columns = projection?.toMutableList() ?: mutableListOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        if (omitChildIdentityColumns) {
            columns.remove(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            columns.remove(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        }
        return docCursor(columns.toTypedArray(), docs.values)
    }

    private fun emptyCursor(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME))

    private fun docCursor(projection: Array<out String>?, docs: Collection<TestSafDoc>): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
        val cursor = MatrixCursor(columns)
        for (doc in docs) {
            cursor.addRow(
                Array<Any?>(columns.size) { index ->
                    when (val column = columns[index]) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mimeType
                        DocumentsContract.Document.COLUMN_SIZE -> doc.file.length()
                        DocumentsContract.Document.COLUMN_FLAGS -> {
                            var flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                            if (doc.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                            } else if (renameMode != TestSafRenameMode.UNSUPPORTED) {
                                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                            }
                            flags
                        }
                        else -> null
                    }
                },
            )
        }
        return cursor
    }
}
