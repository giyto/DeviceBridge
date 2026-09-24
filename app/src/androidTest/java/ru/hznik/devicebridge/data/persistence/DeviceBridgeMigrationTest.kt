package ru.hznik.devicebridge.data.persistence

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.hznik.devicebridge.data.persistence.room.DEVICE_BRIDGE_MIGRATIONS
import ru.hznik.devicebridge.data.persistence.room.DeviceBridgeDatabase
import ru.hznik.devicebridge.data.persistence.room.MIGRATION_1_2
import ru.hznik.devicebridge.data.persistence.room.PartialUploadEntity

@RunWith(AndroidJUnit4::class)
class DeviceBridgeMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DeviceBridgeDatabase::class.java,
    )

    @Test
    fun migration1To2KeepsHistoryAndAddsPartialUploads() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO history_records (record_id, operation_id, kind, direction, " +
                    "browser_label, timestamp_epoch_millis, terminal_status) VALUES " +
                    "('record-1', 'operation-1', 'FILE', 'BROWSER_TO_ANDROID', 'Chrome', 100, 'COMPLETED')",
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT record_id FROM history_records").use { cursor ->
                assertEquals(1, cursor.count)
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, DeviceBridgeDatabase::class.java, DB_NAME)
            .addMigrations(*DEVICE_BRIDGE_MIGRATIONS)
            .build()
        try {
            runBlocking {
                val dao = database.partialUploadDao()
                dao.upsert(partial(bytes = 10))
                dao.upsert(partial(bytes = 20))
                assertEquals(20L, dao.find("a".repeat(64), 100, "movie.mp4", TREE)?.bytesRetained)
                assertEquals(1, dao.getAll().size)
                assertEquals(1, dao.delete(DOCUMENT))
                assertEquals(0, dao.getAll().size)
            }
        } finally {
            database.close()
        }
    }

    private fun partial(bytes: Long) = PartialUploadEntity(
        partialDocumentUri = DOCUMENT,
        sha256 = "a".repeat(64),
        sizeBytes = 100,
        displayName = "movie.mp4",
        mimeType = "video/mp4",
        treeUri = TREE,
        bytesRetained = bytes,
        updatedAtEpochMillis = 1_000,
    )

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val TREE = "content://documents/tree/primary%3ADownload"
        const val DOCUMENT = "content://documents/tree/primary%3ADownload/document/movie.part"
    }
}
