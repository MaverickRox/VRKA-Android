package com.mvrk.vrka

import android.os.Build

/**
 * Destination rules for the declared API range.
 *
 * API 29+ can publish through the scoped `MediaStore.Downloads` collection with no extra
 * permission. API 26-28 would fall back to `MediaStore.Files` on external storage, which
 * needs `WRITE_EXTERNAL_STORAGE`; VRKA does not request that broad permission. Those versions
 * therefore require a user-selected SAF tree before a download is accepted.
 */
internal object DestinationPolicy {
    const val SCOPED_STORAGE_MIN_API = Build.VERSION_CODES.Q

    fun requiresSafDestination(sdkInt: Int): Boolean = sdkInt < SCOPED_STORAGE_MIN_API

    /**
     * @param hasPersistedWritePermission result of checking the persisted URI permission for
     * a non-blank tree URI. Ignored when the tree is blank.
     */
    fun acceptsDestination(
        treeUri: String?,
        sdkInt: Int,
        hasPersistedWritePermission: Boolean,
    ): Boolean = when {
        !treeUri.isNullOrBlank() -> hasPersistedWritePermission
        else -> !requiresSafDestination(sdkInt)
    }
}
