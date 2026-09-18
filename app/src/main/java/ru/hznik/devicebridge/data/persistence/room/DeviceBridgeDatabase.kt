package ru.hznik.devicebridge.data.persistence.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HistoryRecordEntity::class,
        TrustedBrowserEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DeviceBridgeDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    abstract fun trustedBrowserDao(): TrustedBrowserDao
}
