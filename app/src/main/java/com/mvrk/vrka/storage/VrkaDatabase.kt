package com.mvrk.vrka.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        JobEntity::class,
        AttemptEntity::class,
        PublicationBatchEntity::class,
        PublicationOutputEntity::class,
        CleanupTaskEntity::class,
        MigrationLedgerEntity::class,
        MigrationRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VrkaDatabase : RoomDatabase() {
    abstract fun jobDao(): JobDao
    abstract fun attemptDao(): AttemptDao
    abstract fun publicationBatchDao(): PublicationBatchDao
    abstract fun publicationOutputDao(): PublicationOutputDao
    abstract fun cleanupTaskDao(): CleanupTaskDao
    abstract fun migrationDao(): MigrationDao

    companion object {
        const val NAME = "vrka-storage.db"

        fun build(context: Context): VrkaDatabase =
            Room.databaseBuilder(context, VrkaDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.execSQL("PRAGMA foreign_keys = ON")
                        db.query("PRAGMA busy_timeout = 5000").use { }
                        db.execSQL("PRAGMA synchronous = FULL")
                    }
                })
                .build()
    }
}
