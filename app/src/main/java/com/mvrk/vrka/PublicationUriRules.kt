package com.mvrk.vrka

import android.net.Uri
import android.provider.MediaStore

/**
 * Structural identity rules shared by the journal decoder and every destructive provider
 * operation. A URI is only trusted after it matches the provider collection and, for SAF,
 * the exact tree that owns the publication transaction.
 */
internal object PublicationUriRules {
    const val MEDIA_STORE_DESTINATION = "Downloads/VRKA"

    private val MEDIA_STORE_COLLECTIONS = setOf("downloads", "file")
    private val MEDIA_STORE_VOLUMES = setOf("external", "external_primary")

    fun pendingNameFor(transactionId: String, intendedFilename: String): String =
        ".vrka_pending_${transactionId}_$intendedFilename"

    fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return false
        val segments = uri.pathSegments
        return segments.size == 3 &&
            segments[0] in MEDIA_STORE_VOLUMES &&
            segments[1] in MEDIA_STORE_COLLECTIONS &&
            segments[2].toLongOrNull() != null
    }

    fun isTreeUri(uri: Uri): Boolean {
        val segments = uri.pathSegments
        return uri.scheme == "content" &&
            !uri.authority.isNullOrBlank() &&
            segments.size >= 2 &&
            segments[0] == "tree" &&
            segments[1].isNotBlank()
    }

    fun isDocumentUnderTree(document: Uri, tree: Uri): Boolean {
        if (document.scheme != "content" || tree.scheme != "content") return false
        if (!isTreeUri(tree)) return false
        if (document.authority.isNullOrBlank() || document.authority != tree.authority) return false
        val documentSegments = document.pathSegments
        val treeSegments = tree.pathSegments
        return documentSegments.size == 4 &&
            documentSegments[0] == "tree" &&
            documentSegments[1] == treeSegments[1] &&
            documentSegments[2] == "document" &&
            documentSegments[3].isNotBlank()
    }
}
