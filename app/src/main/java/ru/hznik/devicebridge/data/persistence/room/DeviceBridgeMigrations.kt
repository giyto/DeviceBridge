package ru.hznik.devicebridge.data.persistence.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the table of partially received uploads kept for resuming; history is untouched. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `partial_uploads` (" +
                "`partial_document_uri` TEXT NOT NULL, `sha256` TEXT NOT NULL, " +
                "`size_bytes` INTEGER NOT NULL, `display_name` TEXT NOT NULL, " +
                "`mime_type` TEXT NOT NULL, `tree_uri` TEXT NOT NULL, " +
                "`bytes_retained` INTEGER NOT NULL, `updated_at_epoch_millis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`partial_document_uri`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_partial_uploads_sha256_size_bytes_display_name_tree_uri` " +
                "ON `partial_uploads` (`sha256`, `size_bytes`, `display_name`, `tree_uri`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_partial_uploads_updated_at_epoch_millis` " +
                "ON `partial_uploads` (`updated_at_epoch_millis`)",
        )
    }
}

val DEVICE_BRIDGE_MIGRATIONS = arrayOf(MIGRATION_1_2)
