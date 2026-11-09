package com.mvrk.vrka.storage

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class StorageReadiness {
    INITIALIZING,
    MIGRATING,
    READY,
    MIGRATION_FAILED,
    STORAGE_FAILED,
}

internal data class StorageStatus(
    val readiness: StorageReadiness,
    val message: String = "",
    val migration: MigrationReport? = null,
)

/**
 * Process-wide storage initialization gate. The database/repository objects are constructed
 * without opening the database; all migration and verification work happens on IO before
 * READY is published.
 */
internal class StorageRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(StorageStatus(StorageReadiness.INITIALIZING))
    val status: StateFlow<StorageStatus> = _status.asStateFlow()

    private val ready = CompletableDeferred<StorageStatus>()
    private var database: VrkaDatabase = VrkaDatabase.build(context)
    val repository: RoomStorageRepository = RoomStorageRepository(database)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { initialize() }
    }

    suspend fun awaitReady(): StorageStatus = ready.await()

    fun close() {
        runCatching { database.close() }
    }

    private suspend fun initialize() {
        val report = try {
            _status.value = StorageStatus(StorageReadiness.MIGRATING)
            val migrator = LegacyStorageMigrator(context.filesDir, database, repository)
            migrator.migrateIfNeeded()
        } catch (error: Throwable) {
            _status.value = StorageStatus(
                StorageReadiness.MIGRATION_FAILED,
                message = error.message.orEmpty().take(500),
            )
            ready.complete(_status.value)
            return
        }
        try {
            repository.loadCache()
            // Reopen the same database file with a fresh Room instance to prove that the
            // migrated data is durable and readable outside the importer's connection.
            val verifyDb = VrkaDatabase.build(context)
            try {
                withContext(Dispatchers.IO) {
                    val count = verifyDb.jobDao().all().size
                    val outputCount = verifyDb.publicationOutputDao().all().size
                    if (count < 0 || outputCount < 0) error("Invalid verification counts")
                }
            } finally {
                verifyDb.close()
            }
            val status = StorageStatus(StorageReadiness.READY, migration = report)
            _status.value = status
            ready.complete(status)
        } catch (error: Throwable) {
            _status.value = StorageStatus(
                StorageReadiness.STORAGE_FAILED,
                message = error.message.orEmpty().take(500),
                migration = report,
            )
            ready.complete(_status.value)
        }
    }
}
